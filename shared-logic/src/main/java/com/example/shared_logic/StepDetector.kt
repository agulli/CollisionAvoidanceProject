package com.example.shared_logic // This package name matches your project

/**
 * This is the shared "brain" for motion.
 * It's responsible for deciding if the user is "walking"
 * by detecting the rhythmic "bounce" in their step.
 */
class StepDetector(
    // How strong the "bounce" (Y-axis acceleration) must be to count as a step.
    // You may need to tune this value.
    private val stepThreshold: Float = 1.8f,

    // How long to wait between steps (in milliseconds) to avoid double-counting.
    private val stepDelayMs: Long = 500,

    // If no step is detected in this time (in milliseconds),
    // assume the user has stopped walking.
    private val stepTimeoutMs: Long = 2000
) {
    private var lastStepTime: Long = 0
    private var isPeak: Boolean = false
    private var lastAccelY: Float = 0f

    // This is the public state that the rest of the app will read.
    var isUserWalking: Boolean = false
        private set // Only this class can change its own state.

    /**
     * Call this from your platform's sensor listener on every event.
     * @param eventTimestamp The timestamp of the sensor event (in millis).
     * @param accelY The raw Y-axis acceleration (vertical bounce).
     */
    fun processSensorEvent(eventTimestamp: Long, accelY: Float) {

        // --- 1. Peak Detection ---
        // We're looking for the "up" motion of a bounce (positive acceleration)
        // that crosses our threshold.
        if (accelY > lastAccelY && accelY > stepThreshold && !isPeak) {
            isPeak = true // We've hit the top of the "bounce"

            // --- 2. Step Debouncing ---
            // Only count this as a new step if enough time has passed
            // since the last one.
            if (eventTimestamp - lastStepTime > stepDelayMs) {
                lastStepTime = eventTimestamp
                isUserWalking = true
            }
        } else if (accelY < lastAccelY) {
            // The "bounce" is now going down, so we reset the peak
            // to be ready for the next "up" motion.
            isPeak = false
        }

        // --- 3. Stop Timeout ---
        // If it's been too long since the last detected step,
        // assume the user has stopped.
        if (isUserWalking && (eventTimestamp - lastStepTime > stepTimeoutMs)) {
            isUserWalking = false
        }

        // Store the current acceleration for the next comparison
        lastAccelY = accelY
    }
}
