package io.github.thibaultbee.streampack.core.elements.buffers

import io.github.thibaultbee.streampack.core.elements.data.Frame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import io.github.thibaultbee.streampack.core.logger.Logger
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.Condition

/**
 * A circular buffer implementation for frames to handle back pressure between encoder and muxer.
 * Maintains frame order and proper timing using a true circular buffer pattern.
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
    
    // Circular buffer state
    private val elements = AtomicInteger(0)
    private var entrance = 0
    private var exit = 0
    private val frames = Array<Frame?>(capacity) { null }
    
    // Synchronization
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()
    
    private var lastPollTime: Long = 0
    private var emptyPollCount: Int = 0
    private var lastEmptyPollLogTime: Long = 0
    private var lastOfferTime: Long = 0
    private var maxBufferSize: Int = 0
    
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
     * Adds a frame to the buffer. If the buffer is full, waits until space is available.
     * Maintains proper timing by adjusting frame timestamps.
     * 
     * @param frame The frame to add
     * @return true if the frame was added successfully, false if the operation was interrupted
     */
    fun offer(frame: Frame): Boolean {
        val currentTime = System.nanoTime() / 1000 // Convert to microseconds
        val timeSinceLastOffer = if (lastOfferTime > 0) currentTime - lastOfferTime else 0
        lastOfferTime = currentTime

        lock.lock()
        try {
            // Wait if buffer is full
            while (elements.get() >= capacity) {
                try {
                    notFull.await()
                } catch (e: InterruptedException) {
                    Logger.w(TAG, "Offer interrupted while waiting for space")
                    return false
                }
            }

            // Adjust frame timing if needed
            if (lastFrameTime > 0 && frameInterval > 0) {
                val expectedTime = lastFrameTime + frameInterval
                if (frame.ptsInUs < expectedTime) {
                    // Frame is too early, adjust its timestamp
                    frame.ptsInUs = expectedTime
                    Logger.d(TAG, "[${Thread.currentThread().name}] Adjusted frame timing: $expectedTime")
                }
            }
            
            lastFrameTime = frame.ptsInUs
            
            // Add frame to buffer
            frames[entrance] = frame
            entrance = (entrance + 1) % capacity
            elements.incrementAndGet()
            
            // Update buffer usage
            updateBufferUsage()
            
            // Track maximum buffer size
            val currentSize = elements.get()
            if (currentSize > maxBufferSize) {
                maxBufferSize = currentSize
                Logger.d(TAG, "[${Thread.currentThread().name}] New max buffer size: $maxBufferSize/$capacity (${(maxBufferSize.toFloat()/capacity)*100}%)")
            }
            
            // Signal that buffer is not empty
            notEmpty.signal()
            
            Logger.d(TAG, "[${Thread.currentThread().name}] Frame offered: size=${elements.get()}/$capacity, usage=${_bufferUsageFlow.value}, pts=${frame.ptsInUs}, timeSinceLastOffer=${timeSinceLastOffer}µs")
            return true
        } finally {
            lock.unlock()
        }
    }

    /**
     * Retrieves and removes the oldest frame from the buffer.
     * If the buffer is empty, waits until a frame is available.
     * 
     * @return The oldest frame, or null if the operation was interrupted
     */
    fun poll(): Frame? {
        val currentTime = System.nanoTime() / 1000 // Convert to microseconds
        
        lock.lock()
        try {
            // Wait if buffer is empty
            while (elements.get() == 0) {
                try {
                    emptyPollCount++
                    // Log empty polls every second to avoid log spam
                    if (currentTime - lastEmptyPollLogTime > 1_000_000) { // 1 second in microseconds
                        Logger.d(TAG, "[${Thread.currentThread().name}] Empty polls in last second: $emptyPollCount")
                        emptyPollCount = 0
                        lastEmptyPollLogTime = currentTime
                    }
                    notEmpty.await()
                } catch (e: InterruptedException) {
                    Logger.w(TAG, "Poll interrupted while waiting for frame")
                    return null
                }
            }

            // Get frame from buffer
            val frame = frames[exit]
            frames[exit] = null // Clear the slot
            exit = (exit + 1) % capacity
            elements.decrementAndGet()
            
            // Update buffer usage
            updateBufferUsage()
            
            // Signal that buffer is not full
            notFull.signal()
            
            if (frame != null) {
                val timeSinceLastPoll = if (lastPollTime > 0) currentTime - lastPollTime else 0
                val timeSinceFrameCreation = currentTime - (frame.ptsInUs / 1000)
                Logger.d(TAG, "[${Thread.currentThread().name}] Frame polled: size=${elements.get()}/$capacity, usage=${_bufferUsageFlow.value}, pts=${frame.ptsInUs}, timeSinceLastPoll=${timeSinceLastPoll}µs, latency=${timeSinceFrameCreation}µs")
                lastPollTime = currentTime
            }
            
            return frame
        } finally {
            lock.unlock()
        }
    }

    /**
     * Retrieves but does not remove the oldest frame from the buffer.
     * 
     * @return The oldest frame, or null if the buffer is empty
     */
    fun peek(): Frame? {
        lock.lock()
        try {
            return if (elements.get() > 0) frames[exit] else null
        } finally {
            lock.unlock()
        }
    }

    /**
     * Returns the current number of frames in the buffer.
     */
    fun size(): Int = elements.get()

    /**
     * Returns true if the buffer is empty.
     */
    fun isEmpty(): Boolean = elements.get() == 0

    /**
     * Returns true if the buffer is full.
     */
    fun isFull(): Boolean = elements.get() >= capacity

    /**
     * Clears all frames from the buffer.
     */
    fun clear() {
        lock.lock()
        try {
            for (i in 0 until capacity) {
                frames[i]?.close()
                frames[i] = null
            }
            entrance = 0
            exit = 0
            elements.set(0)
            lastFrameTime = 0
            maxBufferSize = 0
            updateBufferUsage()
            notFull.signalAll()
        } finally {
            lock.unlock()
        }
    }

    private fun updateBufferUsage() {
        _bufferUsageFlow.value = elements.get().toFloat() / capacity
    }
} 