// This package name must match your project's package name
package com.example.collisionavoidanceproject

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleEventObserver
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// --- Import your shared logic code ---
// Make sure this package name matches what you created in shared-logic
import com.example.shared_logic.CollisionLogic
import com.example.shared_logic.DetectionResult
import com.example.shared_logic.HapticType
import com.example.shared_logic.StepDetector

// --- Constants ---
private const val TAG = "CollisionDetector"
private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.VIBRATE)

class MainActivity : ComponentActivity() {

    // --- Platform-Specific Services ---
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private lateinit var vibrator: Vibrator
    private lateinit var objectDetector: ObjectDetector

    // --- Instance of our shared StepDetector ---
    private val stepDetector = StepDetector()

    // --- Instance of our shared CollisionLogic ---
    // This is nullable because it needs the image width to be initialized
    private var collisionLogic: CollisionLogic? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Initialize Platform Services ---
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // --- Initialize ML Kit Object Detector ---
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableClassification()
            .build()
        objectDetector = ObjectDetection.getClient(options)

        // --- Set Compose Content ---
        setContent {
            // Note: We're replacing the default "CollisionAvoidanceProjectTheme"
            // with the simpler "MaterialTheme" to avoid an extra file.
            MaterialTheme {
                // This is our main application Composable
                CollisionApp(
                    sensorManager = sensorManager,
                    accelSensor = accelSensor,
                    stepDetector = stepDetector,
                    objectDetector = objectDetector,
                    onHapticTrigger = { triggerHapticFeedback(it) },
                    getCollisionLogic = { imageWidth, imageHeight ->
                        // Lazily initialize or get the collision logic class
                        // We do this here so we can pass the correct imageWidth
                        if (collisionLogic == null) {
                            collisionLogic = CollisionLogic(screenCenterX = imageWidth / 2)
                        }
                        collisionLogic!!
                    }
                )
            }
        }
    }

    /**
     * Triggers the phone's vibrator based on the risk level.
     */
    private fun triggerHapticFeedback(type: HapticType) {
        vibrator.cancel() // Stop any previous vibration
        when (type) {
            HapticType.DANGER -> {
                // Strong, solid buzz
                val effect = VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            }
            HapticType.WARNING -> {
                // A double-pulse "ba-bump"
                val timings = longArrayOf(0, 75, 100, 75) // off, on, off, on
                val amplitudes = intArrayOf(0, 150, 0, 150)
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1) // -1 = no repeat
                vibrator.vibrate(effect)
            }
            HapticType.NONE -> {
                // Do nothing (already cancelled)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        objectDetector.close() // Shut down the ML model
    }
}

/**
 * The main Composable function for our app.
 * It handles permissions, state, and UI layout.
 */
@Composable
fun CollisionApp(
    sensorManager: SensorManager,
    accelSensor: Sensor?,
    stepDetector: StepDetector,
    objectDetector: ObjectDetector,
    onHapticTrigger: (HapticType) -> Unit,
    getCollisionLogic: (imageWidth: Int, imageHeight: Int) -> CollisionLogic
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // --- State Management ---
    // State for holding permission status
    var hasPermissions by remember {
        mutableStateOf(REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    // State for holding walking status (from shared logic)
    var isWalking by remember { mutableStateOf(false) }
    // State for holding the detected objects
    var detectionResults by remember { mutableStateOf<List<DetectionResult>>(emptyList()) }
    // State for the camera image dimensions
    var imageWidth by remember { mutableStateOf(0) }
    var imageHeight by remember { mutableStateOf(0) }

    // --- Sensor Event Listener ---
    // We remember this listener so it's not re-created on every recomposition
    val sensorEventListener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
                    // Send sensor data to our shared logic
                    stepDetector.processSensorEvent(
                        eventTimestamp = event.timestamp / 1_000_000, // Nanos to millis
                        accelY = event.values[1] // Y-axis for "bounce"
                    )
                    // Update our Composable's state
                    isWalking = stepDetector.isUserWalking
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    // --- Lifecycle Observer ---
    // This safely registers and unregisters the sensor listener
    // when the app is paused or resumed.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                accelSensor?.let {
                    sensorManager.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_UI)
                }
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                sensorManager.unregisterListener(sensorEventListener)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // When the composable is removed, unregister the listener
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager.unregisterListener(sensorEventListener)
        }
    }

    // --- Permission Launcher ---
    // This is the modern way to ask for permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            // Update our permission state
            hasPermissions = permissions.values.all { it }
        }
    )

    // --- Request Permissions on Launch ---
    // This effect runs once when the composable is first launched
    LaunchedEffect(key1 = true) {
        if (!hasPermissions) {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    // --- Main UI Layout ---
    Box(modifier = Modifier.fillMaxSize()) {
        if (hasPermissions) {
            // --- Camera, Analysis, and Overlay ---
            CameraPreview(
                objectDetector = objectDetector,
                onResults = { results, w, h ->
                    // Update our state with the latest detections
                    detectionResults = results
                    imageWidth = w
                    imageHeight = h

                    // Trigger haptics based on the *worst* risk
                    // .ordinal gives us a number (NONE=0, WARNING=1, DANGER=2)
                    val worstRisk = results.maxOfOrNull { it.hapticType.ordinal }
                    val hapticType = worstRisk?.let { HapticType.entries[it] } ?: HapticType.NONE

                    // Only trigger haptics if the user is walking
                    if (isWalking) {
                        onHapticTrigger(hapticType)
                    } else {
                        onHapticTrigger(HapticType.NONE)
                    }
                },
                getCollisionLogic = getCollisionLogic
            )

            // --- Draw Bounding Boxes ---
            // This is drawn *over* the CameraPreview
            ObjectOverlay(
                results = detectionResults,
                sourceImageWidth = imageWidth,
                sourceImageHeight = imageHeight
            )

            // --- Status Text ---
            Text(
                text = "Status: ${if (isWalking) "WALKING" else "Still"}",
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
                    .padding(8.dp)
            )

        } else {
            // --- Permissions Denied Screen ---
            Text(
                text = "Please grant Camera and Vibrate permissions to use this app.",
                color = Color.Red,
                fontSize = 18.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * A Composable that handles the CameraX setup and image analysis.
 */
@Composable
fun CameraPreview(
    objectDetector: ObjectDetector,
    onResults: (List<DetectionResult>, Int, Int) -> Unit,
    getCollisionLogic: (imageWidth: Int, imageHeight: Int) -> CollisionLogic
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) } // Create and remember the PreviewView

    // We use a LaunchedEffect to bind the camera lifecycle
    LaunchedEffect(cameraProviderFuture, previewView) { // Add previewView as a key
        val cameraProvider = cameraProviderFuture.await(context)
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider) // Use previewView.surfaceProvider
        }

        // --- This is where the magic happens ---
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                        // Get our logic class
                        val collisionLogic = getCollisionLogic(image.width, image.height)

                        // Process the image with ML Kit
                        objectDetector.process(image)
                            .addOnSuccessListener { detectedObjects ->
                                val results = detectedObjects
                                    // We only care about people
                                    .filter { it.labels.any { label -> label.text == "Person" } }
                                    .map {
                                        // Use our shared logic to assess risk
                                        collisionLogic.assessRisk(
                                            it.boundingBox,
                                            image.width,
                                            image.height
                                        )
                                    }

                                // Send the results back up to our main Composable
                                onResults(results, image.width, image.height)
                            }
                            .addOnFailureListener { e -> Log.e(TAG, "Detection failed", e) }
                            .addOnCompleteListener { imageProxy.close() } // VERY IMPORTANT
                    } else {
                        imageProxy.close()
                    }
                }
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    // This is the bridge to the old Android View system
    // We use it to host the CameraX 'PreviewView'
    AndroidView(
        factory = {
            previewView.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // This scale type ensures the camera feed fills the screen
                scaleType = PreviewView.ScaleType.FILL_START
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * A Composable that draws bounding boxes over the camera feed.
 */
@Composable
fun ObjectOverlay(
    results: List<DetectionResult>,
    sourceImageWidth: Int,
    sourceImageHeight: Int
) {
    // This Canvas fills the screen, drawing on top of the CameraPreview
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (sourceImageWidth == 0 || sourceImageHeight == 0) return@Canvas

        // --- Calculate Scaling ---
        // This logic scales the boxes from the image's coordinate system
        // to the screen's coordinate system.
        val canvasWidth = size.width
        val canvasHeight = size.height

        val scaleX = canvasWidth / sourceImageWidth.toFloat()
        val scaleY = canvasHeight / sourceImageHeight.toFloat()

        // Use maxOf to "fill" the screen, matching the FILL_START scale type
        val scale = maxOf(scaleX, scaleY)

        // Calculate offsets to center the scaled image
        val offsetX = (canvasWidth - (sourceImageWidth * scale)) / 2f
        val offsetY = (canvasHeight - (sourceImageHeight * scale)) / 2f

        for (result in results) {
            val box = result.boundingBox
            val color = when (result.hapticType) {
                HapticType.DANGER -> Color.Red
                HapticType.WARNING -> Color.Yellow
                HapticType.NONE -> Color.White
            }

            // Draw the rectangle
            drawRect(
                color = color,
                topLeft = Offset(box.left * scale + offsetX, box.top * scale + offsetY),
                size = Size(box.width() * scale, box.height() * scale),
                style = Stroke(width = 8f)
            )
        }
    }
}

/**
 * A helper function to convert Google's ListenableFuture to a suspend function.
 */
suspend fun <T> ListenableFuture<T>.await(context: Context): T = suspendCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: Exception) {
            cont.resumeWith(Result.failure(e))
        }
    }, ContextCompat.getMainExecutor(context))
}
