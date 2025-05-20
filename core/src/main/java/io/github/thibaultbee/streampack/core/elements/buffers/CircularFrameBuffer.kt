package io.github.thibaultbee.streampack.core.elements.buffers

import io.github.thibaultbee.streampack.core.elements.data.Frame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

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
    // Use PriorityBlockingQueue to maintain frame order by timestamp
    private val buffer = PriorityBlockingQueue<Frame>(capacity) { f1, f2 ->
        f1.ptsInUs.compareTo(f2.ptsInUs)
    }
    
    private val isFull = AtomicBoolean(false)
    private var lastFrameTime: Long = 0
    private var frameInterval: Long = 0 // Will be set based on frame rate or sample rate
    private var targetBitrate: Int = 0 // Track target bitrate for adaptive buffer sizing
    
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
     * Sets the target bitrate for adaptive buffer sizing
     */
    fun setTargetBitrate(bitrate: Int) {
        targetBitrate = bitrate
        // Adjust buffer capacity based on bitrate
        // For higher bitrates, we want to keep more frames to maintain quality
        val newCapacity = if (isAudio) {
            capacity // Keep audio buffer size fixed
        } else {
            // For video, scale buffer size with bitrate
            // Base capacity is 30 frames (1 second at 30fps)
            // Scale up to 60 frames for high bitrates
            val baseCapacity = 30
            val maxCapacity = 60
            val minBitrate = 2_000_000 // 2 Mbps
            val maxBitrate = 8_000_000 // 8 Mbps
            val scaledCapacity = baseCapacity + ((bitrate - minBitrate) * (maxCapacity - baseCapacity) / (maxBitrate - minBitrate))
            scaledCapacity.coerceIn(baseCapacity, maxCapacity)
        }
        // Resize buffer if needed
        if (newCapacity != capacity) {
            val newBuffer = PriorityBlockingQueue<Frame>(newCapacity) { f1, f2 ->
                f1.ptsInUs.compareTo(f2.ptsInUs)
            }
            buffer.drainTo(newBuffer)
            buffer.clear()
            buffer.addAll(newBuffer)
        }
    }

    /**
     * Adds a frame to the buffer. If the buffer is full, drops frames intelligently.
     * For video, prioritizes keeping key frames and drops non-key frames when possible.
     * 
     * @param frame The frame to add
     * @return true if the frame was added successfully, false if the buffer is full and the frame was dropped
     */
    fun offer(frame: Frame): Boolean {
        if (buffer.size >= capacity) {
            if (isAudio) {
                // For audio, drop the oldest frame
                buffer.poll()?.close()
            } else {
                // For video, try to drop non-key frames first
                val iterator = buffer.iterator()
                var dropped = false
                while (iterator.hasNext()) {
                    val existingFrame = iterator.next()
                    if (!existingFrame.isKeyFrame) {
                        iterator.remove()
                        existingFrame.close()
                        dropped = true
                        break
                    }
                }
                if (!dropped) {
                    // If no non-key frames found, drop the oldest frame
                    buffer.poll()?.close()
                }
            }
        }

        // Adjust frame timing if needed
        if (lastFrameTime > 0 && frameInterval > 0) {
            val expectedTime = lastFrameTime + frameInterval
            if (frame.ptsInUs < expectedTime) {
                // Frame is too early, adjust its timestamp
                frame.ptsInUs = expectedTime
            }
        }
        
        lastFrameTime = frame.ptsInUs
        val result = buffer.offer(frame)
        updateBufferUsage()
        return result
    }

    /**
     * Retrieves and removes the oldest frame from the buffer.
     * 
     * @return The oldest frame, or null if the buffer is empty
     */
    fun poll(): Frame? {
        val frame = buffer.poll()
        updateBufferUsage()
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
        updateBufferUsage()
    }

    private fun updateBufferUsage() {
        _bufferUsageFlow.value = buffer.size.toFloat() / capacity
    }
} 