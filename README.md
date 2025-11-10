# Collision Avoidance System for Mobile and Wearable Devices

This Android project demonstrates a real-time collision avoidance system designed to run on both standard Android phones and AR glasses. The system uses a device's camera to detect people, analyzes their proximity and trajectory, and provides haptic feedback to the user to warn them of potential collisions.

The project is structured into three distinct modules: a shared logic core, a phone application, and a glasses application, showcasing a powerful multi-platform architecture.

## Project Structure

The repository is a multi-module Android project:

-   `:shared-logic`: A pure Kotlin module containing the core business logic for step detection and collision risk assessment. This module is platform-independent.
-   `:app-phone`: An Android application module that provides a rich visual interface. It displays the camera feed, overlays bounding boxes on detected people, and shows the user's current status.
-   `:app-glasses`: An Android application module tailored for a heads-up display (HUD) on AR glasses. It features a minimal, high-contrast UI and relies primarily on haptic feedback for alerts.

## Core Logic (`:shared-logic`)

The "brains" of the application reside in the `:shared-logic` module, making the system easily portable to different form factors.

### `StepDetector.kt`

-   **Purpose**: Determines if the user is currently walking or standing still.
-   **Mechanism**: Implements a simple peak detection algorithm on the Y-axis of the linear accelerometer sensor. It looks for the rhythmic "bounce" that occurs during a walking gait.
-   **State Management**: Includes debouncing logic to prevent double-counting steps and a timeout to automatically transition the user's state from "WALKING" to "Still" if no steps are detected for a certain period.

### `CollisionLogic.kt`

-   **Purpose**: Assesses the collision risk posed by a detected person.
-   **Risk Assessment**: The logic determines the risk level (`NONE`, `WARNING`, `DANGER`) based on a combination of factors:
    1.  **Centrality**: Is the detected person in the user's direct path (i.e., near the center of the camera's view)?
    2.  **Proximity**: How close is the person? This is inferred from the area of their bounding box; a larger area means the person is closer.
    3.  **Approach**: Is the person getting closer? This is determined by comparing the bounding box area of the current frame to the previous frame.
-   **Output**: Produces a `DetectionResult` containing the bounding box and the calculated `HapticType` risk level.

## Phone Application (`:app-phone`)

The phone app provides a full-featured visual interface for the collision avoidance system.

### Features:

-   **Live Camera Preview**: Displays the back-camera feed in real-time.
-   **Object Detection Overlay**: Renders colored bounding boxes over detected people. The color of the box indicates the assessed risk level:
    -   **White**: Person detected, no immediate risk.
    -   **Yellow**: Warning risk (person is approaching in the user's path).
    -   **Red**: Danger risk (person is too close for comfort).
-   **Status Display**: A simple text overlay shows whether the user is "WALKING" or "Still".
-   **Haptic Feedback**: Vibrates the phone with distinct patterns for `WARNING` and `DANGER` alerts, but only when the user is walking.

## Glasses Application (`:app-glasses`)

The glasses app is optimized for a minimal, hands-free experience, prioritizing information delivery through haptics and simple text.

### Features:

-   **Minimalist UI**: Instead of a full camera feed, the UI is a black screen with large, high-contrast text to indicate the highest-risk state (`DANGER` in red, `WARNING` in yellow). When there is no risk, it shows the user's walking status.
-   **Headless Camera**: The camera runs in the background for analysis, with only a 1x1 pixel `PreviewView` to satisfy the API requirements without cluttering the display.
-   **Haptic-First**: Like the phone app, it uses haptic feedback as the primary means of alerting the user to potential collisions when they are walking.

## Technologies Used

-   **Kotlin**: The entire project is written in 100% Kotlin.
-   **Jetpack Compose**: The UI for both the phone and glasses apps is built declaratively with Jetpack Compose, allowing for flexible and distinct user experiences from a shared data source.
-   **CameraX**: A Jetpack library used to simplify camera access and lifecycle management for the image analysis pipeline.
-   **Google ML Kit (Object Detection)**: Used for its powerful, on-device API to detect people in the camera feed in real-time.
-   **Android SensorManager**: Provides access to the linear accelerometer data used by the `StepDetector`.
-   **Multi-Module Architecture**: Separates concerns, improves build times, and maximizes code reuse between the phone and glasses form factors.
