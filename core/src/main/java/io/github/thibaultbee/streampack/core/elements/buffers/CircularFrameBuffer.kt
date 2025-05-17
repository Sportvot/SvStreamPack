package io.github.thibaultbee.streampack.core.elements.buffers

import io.github.thibaultbee.streampack.core.elements.data.Frame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A circular buffer implementation for frames to handle back pressure between encoder and muxer.
 * 
 * @param capacity The maximum number of frames the buffer can hold
 */
class CircularFrameBuffer(private val capacity: Int) {
    private val buffer = ArrayBlockingQueue<Frame>(capacity)
    private val isFull = AtomicBoolean(false)
    
    private val _bufferUsageFlow = MutableStateFlow(0f)
    val bufferUsageFlow: StateFlow<Float> = _bufferUsageFlow.asStateFlow()

    /**
     * Adds a frame to the buffer. If the buffer is full, the oldest frame will be dropped.
     * 
     * @param frame The frame to add
     * @return true if the frame was added successfully, false if the buffer is full and the frame was dropped
     */
    fun offer(frame: Frame): Boolean {
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
        buffer.clear()
        updateBufferUsage()
    }

    private fun updateBufferUsage() {
        _bufferUsageFlow.value = buffer.size.toFloat() / capacity
    }
} 