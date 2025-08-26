/*
 * Copyright (C) 2024 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.ext.srt.elements.endpoints.composites.sinks

import io.github.thibaultbee.srtdroid.core.enums.Boundary
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.models.MsgCtrl
import io.github.thibaultbee.srtdroid.core.models.SrtUrl.Mode
import io.github.thibaultbee.srtdroid.core.models.Stats
import io.github.thibaultbee.srtdroid.ktx.CoroutineSrtSocket
import io.github.thibaultbee.srtdroid.ktx.connect
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.data.Packet
import io.github.thibaultbee.streampack.core.elements.data.SrtPacket
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaSinkType
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.AbstractSink
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.ClosedException
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.SinkConfiguration
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.ext.srt.data.mediadescriptor.SrtMediaDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

const val SEND_MBPS_CAL_INTERVAL_MS: Int = 1000;
const val SEND_MBPS_RETRIEVAL_GUARD_MS: Int = 5000;

class SrtSink : AbstractSink() {
    override val supportedSinkTypes: List<MediaSinkType> = listOf(MediaSinkType.SRT)

    private var socket: CoroutineSrtSocket? = null
    private var completionException: Throwable? = null
    private var isOnError: Boolean = false
    private var connectionStartTime: Long = 0


    private var lastSendMbpsCalcTime: Long = 0
    private var lastByteSentTotal: Long = 0
    private var internalSendRateMbps: Double = 0.0;

    private var bitrate = 0L

    /**
     * Get SRT stats
     */
    override val metrics: Stats
        get() = socket?.bistats(clear = true, instantaneous = true)
            ?: throw IllegalStateException("Socket is not initialized")

    override val sendRateMbps: Double
        get() = getSendRateWithTimeCheck()

    private fun getSendRateWithTimeCheck(): Double {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastSendMbpsCalcTime <= SEND_MBPS_RETRIEVAL_GUARD_MS) {
            return internalSendRateMbps;
        }

        return 0.0;
    }

    private val _isOpenFlow = MutableStateFlow(false)
    override val isOpenFlow = _isOpenFlow.asStateFlow()

    override fun configure(config: SinkConfiguration) {
        bitrate = config.streamConfigs.sumOf { it.startBitrate.toLong() }
    }

    override suspend fun openImpl(mediaDescriptor: MediaDescriptor) =
        open(SrtMediaDescriptor(mediaDescriptor))

    private suspend fun open(mediaDescriptor: SrtMediaDescriptor) {
        Logger.i(TAG, "Opening SRT connection to ${mediaDescriptor.srtUrl}")
        if (mediaDescriptor.srtUrl.mode != null) {
            require(mediaDescriptor.srtUrl.mode == Mode.CALLER) { "Invalid mode: ${mediaDescriptor.srtUrl.mode}. Only caller supported." }
        }
        if (mediaDescriptor.srtUrl.payloadSize != null) {
            require(mediaDescriptor.srtUrl.payloadSize == PAYLOAD_SIZE)
        }
        if (mediaDescriptor.srtUrl.transtype != null) {
            require(mediaDescriptor.srtUrl.transtype == Transtype.LIVE)
        }

        socket = CoroutineSrtSocket()
        socket?.let {
            // Forces this value. Only works if they are null in [srtUrl]
            it.setSockFlag(SockOpt.PAYLOADSIZE, PAYLOAD_SIZE)
            it.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
            completionException = null
            isOnError = false
            connectionStartTime = System.currentTimeMillis()
            lastSendMbpsCalcTime = connectionStartTime
            it.socketContext.invokeOnCompletion { t ->
                val connectionDuration = System.currentTimeMillis() - connectionStartTime
                completionException = t
                Logger.e(TAG, "SRT socket completion after ${connectionDuration}ms with error: ${t?.message}", t)
                runBlocking {
                    this@SrtSink.close()
                }
            }
            try {
                it.connect(mediaDescriptor.srtUrl)
                Logger.i(TAG, "SRT connection established successfully")
                // Log initial connection stats
                val stats = it.bistats(clear = true, instantaneous = true)
                Logger.i(TAG, "Initial SRT stats - RTT: ${stats.msRTT}ms, Bandwidth: ${stats.mbpsBandwidth} mb/s")
            } catch (t: Throwable) {
                Logger.e(TAG, "Failed to establish SRT connection: ${t.message}", t)
                throw t
            }
        }
        _isOpenFlow.emit(true)
    }

    private fun buildMsgCtrl(packet: Packet): MsgCtrl {
        val boundary = if (packet is SrtPacket) {
            when {
                packet.isFirstPacketFrame && packet.isLastPacketFrame -> Boundary.SOLO
                packet.isFirstPacketFrame -> Boundary.FIRST
                packet.isLastPacketFrame -> Boundary.LAST
                else -> Boundary.SUBSEQUENT
            }
        } else {
            null
        }
        val msgCtrl = if (packet.ts == 0L) {
            if (boundary != null) {
                MsgCtrl(boundary = boundary)
            } else {
                MsgCtrl()
            }
        } else {
            if (boundary != null) {
                MsgCtrl(srcTime = packet.ts, boundary = boundary)
            } else {
                MsgCtrl(srcTime = packet.ts)
            }
        }
        Logger.d(TAG, "Built MsgCtrl - Boundary: $boundary, Timestamp: ${packet.ts}, " +
            "FirstPacket: ${if (packet is SrtPacket) packet.isFirstPacketFrame else "N/A"}, " +
            "LastPacket: ${if (packet is SrtPacket) packet.isLastPacketFrame else "N/A"}")
        return msgCtrl
    }

    override suspend fun write(packet: Packet): Int {
        if (isOnError) {
            Logger.e(TAG, "Write failed: Sink is in error state")
            return -1
        }

        // Pick up completionException if any
        completionException?.let {
            isOnError = true
            Logger.e(TAG, "Write failed: Completion exception occurred: ${it.message}", it)
            throw ClosedException(it)
        }

        val socket = requireNotNull(socket) { "SrtEndpoint is not initialized" }

        try {

            val stats = socket.bistats(clear = true, instantaneous = true)
            val currentTime = System.currentTimeMillis()
            val byteSentTotal: Long = stats.byteSentTotal

            // Log RTT if high
//            if (stats.msRTT > 1000) {
//                Logger.w(TAG, "High RTT detected: ${stats.msRTT}ms, Bandwidth: ${stats.mbpsBandwidth} mb/s")
//            }

            // Log significant bandwidth change
//            if (Math.abs(stats.mbpsBandwidth - lastBandwidth) > 0.5) {
//                Logger.w(TAG, "Significant bandwidth change: ${lastBandwidth} -> ${stats.mbpsBandwidth} mb/s")
//                lastBandwidth = stats.mbpsBandwidth
//            }

            if (currentTime - lastSendMbpsCalcTime >= SEND_MBPS_CAL_INTERVAL_MS) {
                val bytesSentDelta = byteSentTotal - lastByteSentTotal
                val timeDeltaSeconds = (currentTime - lastSendMbpsCalcTime) / 1000.0

                if (timeDeltaSeconds > 0) {
                    val bitsPerSecond = (bytesSentDelta * 8) / timeDeltaSeconds
                    val mbps = bitsPerSecond / 1_000_000.0
                    internalSendRateMbps = mbps;

                    Logger.i(TAG, "Calculated Bandwidth: %.2f Mbps over last %.1f seconds".format(internalSendRateMbps, timeDeltaSeconds))
                }

                lastByteSentTotal = byteSentTotal
                lastSendMbpsCalcTime = currentTime
            }
            
            // Log packet details before sending
//            Logger.d(TAG, "Preparing to write packet - Size: ${packet.buffer.remaining()} bytes, " +
//                "Timestamp: ${packet.ts}, " +
//                "Type: ${packet.javaClass.simpleName}, " +
//                "Buffer position: ${packet.buffer.position()}, " +
//                "Buffer limit: ${packet.buffer.limit()}")
//
            // Create message control
            val msgCtrl = buildMsgCtrl(packet)
            
            // Log socket state before sending
//            Logger.d(TAG, "Socket state before send - Connected: ${socket.isConnected}, " +
//                "Send buffer available: ${stats.byteAvailSndBuf}, " +
//                "Receive buffer available: ${stats.byteAvailRcvBuf}")
            
            // Send the packet
            val bytesSent = socket.send(packet.buffer, msgCtrl)
            
            // Log send result
//            Logger.d(TAG, "Packet write result - Bytes sent: $bytesSent, " +
//                "Buffer remaining: ${packet.buffer.remaining()}, " +
//                "Buffer position: ${packet.buffer.position()}")
            
            if (bytesSent <= 0) {
                Logger.w(TAG, "Warning: No bytes sent for packet")
            }
            
            return bytesSent
        } catch (t: Throwable) {
            isOnError = true
            if (completionException != null) {
                Logger.e(TAG, "Write failed: Socket already closed with exception: ${completionException?.message}", completionException)
                throw ClosedException(completionException!!)
            }
            Logger.e(TAG, "Write failed: Error while sending packet: ${t.message}", t)
            close()
            throw ClosedException(t)
        }
    }

    override suspend fun startStream() {
        Logger.i(TAG, "Starting SRT stream")
        val socket = requireNotNull(socket) { "SrtEndpoint is not initialized" }
        require(socket.isConnected) { "SrtEndpoint should be connected at this point" }

        try {
            socket.setSockFlag(SockOpt.MAXBW, 0L)
            socket.setSockFlag(SockOpt.INPUTBW, bitrate)
            Logger.i(TAG, "SRT stream started successfully with bitrate: $bitrate ")
        } catch (t: Throwable) {
            Logger.e(TAG, "Failed to start SRT stream: ${t.message}", t)
            throw t
        }
    }

    override suspend fun stopStream() {
        Logger.i(TAG, "Stopping SRT stream")
    }

    override suspend fun close() {
        Logger.i(TAG, "Closing SRT sink")
        try {
            socket?.close()
            Logger.i(TAG, "SRT socket closed successfully")
        } catch (t: Throwable) {
            Logger.e(TAG, "Error while closing SRT socket: ${t.message}", t)
        }
        _isOpenFlow.emit(false)
    }


    companion object {
        private const val TAG = "SrtSink"

        private const val PAYLOAD_SIZE = 1316
    }
}