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
 */
class CircularFrameBuffer(private val capacity: Int) {
    // Use PriorityBlockingQueue to maintain frame order by timestamp
    private val buffer = PriorityBlockingQueue<Frame>(capacity) { f1, f2 ->
        f1.ptsInUs.compareTo(f2.ptsInUs)
    }
    
    private val isFull = AtomicBoolean(false)
    private var lastFrameTime: Long = 0
    private var frameInterval: Long = 0 // Will be set based on frame rate
    
    private val _bufferUsageFlow = MutableStateFlow(0f)
    val bufferUsageFlow: StateFlow<Float> = _bufferUsageFlow.asStateFlow()

    /**
     * Sets the target frame rate to calculate proper frame intervals
     */
    fun setFrameRate(frameRate: Int) {
        frameInterval = (1000000L / frameRate) // Convert to microseconds
    }

    /**
     * Adds a frame to the buffer. If the buffer is full, the oldest frame will be dropped.
     * Maintains proper timing by adjusting frame timestamps.
     * 
     * @param frame The frame to add
     * @return true if the frame was added successfully, false if the buffer is full and the frame was dropped
     */
    fun offer(frame: Frame): Boolean {
        if (buffer.size >= capacity) {
            // Buffer is full, drop the oldest frame
            buffer.poll()?.close()
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