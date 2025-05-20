package io.github.thibaultbee.streampack.core.elements.buffers

import io.github.thibaultbee.streampack.core.elements.data.Frame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import io.github.thibaultbee.streampack.core.logger.Logger

/**
 * A circular buffer implementation for frames to handle back pressure between encoder and muxer.
 * Maintains frame order and proper timing.
 * 
 * @param capacity The maximum number of frames the buffer can hold
 * @param isAudio Whether this buffer is for audio frames
 */
class CircularFrameBuffer(
    private val capacity: Int,
    private val isAudio: Boolean = false
) {
    companion object {
        private const val TAG = "CircularFrameBuffer"
    }
    
    private var lastPollTime: Long = 0
    private var emptyPollCount: Int = 0
    private var lastEmptyPollLogTime: Long = 0
    private var lastOfferTime: Long = 0
    private var maxBufferSize: Int = 0
    
    // Use PriorityBlockingQueue to maintain frame order by timestamp
    private val buffer = PriorityBlockingQueue<Frame>(capacity) { f1, f2 ->
        f1.ptsInUs.compareTo(f2.ptsInUs)
    }
    
    private val isFull = AtomicBoolean(false)
    private var lastFrameTime: Long = 0
    private var frameInterval: Long = 0 // Will be set based on frame rate or sample rate
    
    private val _bufferUsageFlow = MutableStateFlow(0f)
    val bufferUsageFlow: StateFlow<Float> = _bufferUsageFlow.asStateFlow()

    /**
     * Sets the target frame rate or sample rate to calculate proper frame intervals
     */
    fun setFrameRate(rate: Int) {
        frameInterval = if (isAudio) {
            // For audio, calculate interval based on samples per frame
            // Assuming 48kHz sample rate and 1024 samples per frame
            (1000000L * 1024) / rate
        } else {
            // For video, calculate interval based on frame rate
            (1000000L / rate)
        }
    }

    /**
     * Adds a frame to the buffer. If the buffer is full, the oldest frame will be dropped.
     * Maintains proper timing by adjusting frame timestamps.
     * 
     * @param frame The frame to add
     * @return true if the frame was added successfully, false if the buffer is full and the frame was dropped
     */
    fun offer(frame: Frame): Boolean {
        val currentTime = System.nanoTime()
        val timeSinceLastOffer = if (lastOfferTime > 0) (currentTime - lastOfferTime) / 1_000_000.0 else 0.0
        lastOfferTime = currentTime

        if (buffer.size >= capacity) {
            // Buffer is full, drop the oldest frame
            val droppedFrame = buffer.poll()
            droppedFrame?.close()
            Logger.d(TAG, "[${Thread.currentThread().name}] Buffer full (${buffer.size}/$capacity), dropped oldest frame")
        }

        // Adjust frame timing if needed
        if (lastFrameTime > 0 && frameInterval > 0) {
            val expectedTime = lastFrameTime + frameInterval
            if (frame.ptsInUs < expectedTime) {
                // Frame is too early, adjust its timestamp
                val oldTime = frame.ptsInUs
                frame.ptsInUs = expectedTime
                Logger.d(TAG, "[${Thread.currentThread().name}] Adjusted frame timing: $oldTime -> ${frame.ptsInUs} (interval: $frameInterval)")
            }
        }
        
        lastFrameTime = frame.ptsInUs
        val result = buffer.offer(frame)
        updateBufferUsage()
        
        // Track maximum buffer size
        if (buffer.size > maxBufferSize) {
            maxBufferSize = buffer.size
            Logger.d(TAG, "[${Thread.currentThread().name}] New max buffer size: $maxBufferSize/$capacity (${(maxBufferSize.toFloat()/capacity)*100}%)")
        }
        
        Logger.d(TAG, "[${Thread.currentThread().name}] Frame offered: size=${buffer.size}/$capacity, usage=${_bufferUsageFlow.value}, pts=${frame.ptsInUs}, timeSinceLastOffer=${timeSinceLastOffer}ms")
        return result
    }

    /**
     * Retrieves and removes the oldest frame from the buffer.
     * 
     * @return The oldest frame, or null if the buffer is empty
     */
    fun poll(): Frame? {
        val currentTime = System.nanoTime()
        val frame = buffer.poll()
        updateBufferUsage()
        
        if (frame == null) {
            emptyPollCount++
            // Log empty polls every second to avoid log spam
            if (currentTime - lastEmptyPollLogTime > 1_000_000_000) { // 1 second in nanoseconds
                Logger.d(TAG, "[${Thread.currentThread().name}] Empty polls in last second: $emptyPollCount")
                emptyPollCount = 0
                lastEmptyPollLogTime = currentTime
            }
        } else {
            val timeSinceLastPoll = if (lastPollTime > 0) (currentTime - lastPollTime) / 1_000_000.0 else 0.0
            val timeSinceFrameCreation = (currentTime / 1_000_000.0) - (frame.ptsInUs / 1000.0)
            Logger.d(TAG, "[${Thread.currentThread().name}] Frame polled: size=${buffer.size}/$capacity, usage=${_bufferUsageFlow.value}, pts=${frame.ptsInUs}, timeSinceLastPoll=${timeSinceLastPoll}ms, latency=${timeSinceFrameCreation}ms")
            lastPollTime = currentTime
        }
        return frame
    }

    /**
     * Retrieves but does not remove the oldest frame from the buffer.
     * 
     * @return The oldest frame, or null if the buffer is empty
     */
    fun peek(): Frame? = buffer.peek()

    /**
     * Returns the current number of frames in the buffer.
     */
    fun size(): Int = buffer.size

    /**
     * Returns true if the buffer is empty.
     */
    fun isEmpty(): Boolean = buffer.isEmpty()

    /**
     * Returns true if the buffer is full.
     */
    fun isFull(): Boolean = buffer.size >= capacity

    /**
     * Clears all frames from the buffer.
     */
    fun clear() {
        buffer.forEach { it.close() }
        buffer.clear()
        lastFrameTime = 0
        maxBufferSize = 0
        updateBufferUsage()
    }

    private fun updateBufferUsage() {
        _bufferUsageFlow.value = buffer.size.toFloat() / capacity
    }
} 