package com.example.shared_logic // This package name matches your project

import android.graphics.Rect
import android.graphics.RectF

/**
 * Defines the types of haptic feedback.
 * 'ordinal' property is used for ranking risk (NONE=0, WARNING=1, DANGER=2)
 */
enum class HapticType {
    NONE,
    WARNING,
    DANGER
}

/**
 * A data class to hold the results of our risk assessment.
 * This is what we'll pass to the UI to draw.
 */
data class DetectionResult(
    val boundingBox: RectF,
    val hapticType: HapticType
)

/**
 * This is the shared "brain" of the app.
 * It's responsible for deciding if a person is a collision risk.
 */
class CollisionLogic(
    private val dangerAreaThreshold: Int = 60000,
    private val centerTolerancePercent: Double = 0.35,
    private val screenCenterX: Int // This should be in IMAGE coordinates (e.g., image.width / 2)
) {
    // Stores the size of the person from the last frame
    private var previousPersonArea: Int = 0

    /**
     * Assesses the collision risk for a single detected person.
     * @param box The bounding box (in image pixels) of the detected person.
     * @param imageWidth The total width of the image being analyzed.
     * @param imageHeight The total height of the image being analyzed.
     * @return A DetectionResult object with the box and risk level.
     */
    fun assessRisk(
        box: Rect,
        imageWidth: Int,
        imageHeight: Int
    ): DetectionResult {

        val boxCenter = box.centerX()
        val boxArea = box.width() * box.height()
        var hapticState = HapticType.NONE

        // 1. Check if the person is in the center of the path
        val imageCenterX = imageWidth / 2
        val centerTolerancePixels = imageCenterX * centerTolerancePercent
        val isCentered = Math.abs(boxCenter - imageCenterX) < centerTolerancePixels

        if (isCentered) {
            // 2. Person is in our path. Check if they are too close.
            val isTooClose = boxArea > dangerAreaThreshold

            // 3. Check if they are getting closer (box size is increasing)
            val isGettingCloser = boxArea > previousPersonArea && previousPersonArea > 0

            if (isTooClose) {
                // Highest risk: They are in the danger zone.
                hapticState = HapticType.DANGER
            } else if (isGettingCloser) {
                // Medium risk: They are approaching.
                hapticState = HapticType.WARNING
            }
        }

        // 4. Update the "previous" area for the next frame's comparison
        if (hapticState != HapticType.NONE && boxArea > previousPersonArea) {
            // Store the new "closest" area
            previousPersonArea = boxArea
        } else if (hapticState == HapticType.NONE) {
            // If there's no risk (not centered), reset the area.
            previousPersonArea = 0
        }

        return DetectionResult(RectF(box), hapticState)
    }
}
