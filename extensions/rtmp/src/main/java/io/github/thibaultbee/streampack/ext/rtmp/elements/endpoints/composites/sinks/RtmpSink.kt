/*
 * Copyright (C) 2022 Thibault B.
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
package io.github.thibaultbee.streampack.ext.rtmp.elements.endpoints.composites.sinks

import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.data.Packet
import io.github.thibaultbee.streampack.core.elements.encoders.VideoCodecConfig
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaSinkType
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.AbstractSink
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.ClosedException
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.SinkConfiguration
import io.github.thibaultbee.streampack.core.logger.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import video.api.rtmpdroid.Rtmp
import java.util.concurrent.Executors

class RtmpSink(
    private val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor()
        .asCoroutineDispatcher()
) : AbstractSink() {
    override val supportedSinkTypes: List<MediaSinkType> = listOf(MediaSinkType.RTMP)

    private var socket: Rtmp? = null
    private var isOnError = false

    private val supportedVideoCodecs = mutableListOf<String>()

    private val _isOpenFlow = MutableStateFlow(false)
    override val isOpenFlow = _isOpenFlow.asStateFlow()

    override fun configure(config: SinkConfiguration) {
        val videoConfig = config.streamConfigs.firstOrNull { it is VideoCodecConfig }
        if (videoConfig != null) {
            supportedVideoCodecs.clear()
            supportedVideoCodecs += listOf(videoConfig.mimeType)
        }
    }

    override suspend fun openImpl(mediaDescriptor: MediaDescriptor) {
        withContext(dispatcher) {
            isOnError = false
            socket = Rtmp().apply {
                /**
                 * TODO: Add supportedVideoCodecs to Rtmp
                 * The workaround could be to split connect and connect message.
                 * The first would be call here and the connect message would be send in
                 * [startStream] (when configure has been called).
                 */
                // supportedVideoCodecs = this@RtmpSink.supportedVideoCodecs
                connect("${mediaDescriptor.uri} live=1 flashver=FMLE/3.0\\20(compatible;\\20FMSc/1.0)")
            }
            _isOpenFlow.emit(true)
        }
    }

    override suspend fun write(packet: Packet) =
        withContext(dispatcher) {
            if (isOnError) {
                Logger.e(TAG, "Write failed: Sink is in error state")
                return@withContext -1
            }

            if (!(isOpenFlow.value)) {
                Logger.e(TAG, "Write failed: Socket is not connected, dropping packet")
                return@withContext -1
            }

            val socket = requireNotNull(socket) { "Socket is not initialized" }

            try {
                Logger.d(TAG, "Writing packet of size ${packet.buffer.remaining()} bytes")
                return@withContext socket.write(packet.buffer)
            } catch (t: Throwable) {
                isOnError = true
                Logger.e(TAG, "Error while writing packet to socket: ${t.message}", t)
                close()
                throw ClosedException(t)
            }
        }

    override suspend fun startStream() {
        withContext(dispatcher) {
            Logger.i(TAG, "Starting RTMP stream")
            val socket = requireNotNull(socket) { "Socket is not initialized" }
            try {
                socket.connectStream()
                Logger.i(TAG, "RTMP stream started successfully")
            } catch (t: Throwable) {
                Logger.e(TAG, "Failed to start RTMP stream: ${t.message}", t)
                throw t
            }
        }
    }

    override suspend fun stopStream() {
        Logger.i(TAG, "Stopping RTMP stream")
        // No need to stop stream
    }

    override suspend fun close() {
        Logger.i(TAG, "Closing RTMP sink")
        withContext(dispatcher) {
            try {
                socket?.close()
                Logger.i(TAG, "RTMP socket closed successfully")
            } catch (t: Throwable) {
                Logger.e(TAG, "Error while closing RTMP socket: ${t.message}", t)
            }
            _isOpenFlow.emit(false)
        }
    }

    companion object {
        private const val TAG = "RtmpSink"
    }
}
