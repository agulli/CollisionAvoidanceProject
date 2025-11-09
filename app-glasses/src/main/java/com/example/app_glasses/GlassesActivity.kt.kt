// Make sure this package name is correct for your app-glasses module
package com.example.app_glasses

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
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
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions.STREAM_MODE
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor


// --- Import your shared logic code ---
// This package path must match your shared-logic module
import com.example.shared_logic.CollisionLogic
import com.example.shared_logic.HapticType
import com.example.shared_logic.StepDetector


// --- Constants ---
private const val TAG = "CollisionDetectorGlass"
private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.VIBRATE)

class GlassesActivity : ComponentActivity() {

    // --- Platform-Specific Services ---
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private lateinit var vibrator: Vibrator
    private lateinit var objectDetector: ObjectDetector

    // --- Instance of our shared StepDetector ---
    private val stepDetector = StepDetector()

    // --- Instance of our shared CollisionLogic ---
    private var collisionLogic: CollisionLogic? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Initialize Platform Services (Identical to phone) ---
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        // --- Initialize ML Kit (Identical to phone) ---
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(STREAM_MODE)
            .build()
        objectDetector = ObjectDetection.getClient(options)

        // --- Set Compose Content ---
        setContent {
            // Glass apps have a black background
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                GlassCollisionApp(
                    sensorManager = sensorManager,
                    accelSensor = accelSensor,
                    stepDetector = stepDetector,
                    objectDetector = objectDetector,
                    onHapticTrigger = { triggerHapticFeedback(it) },
                    getCollisionLogic = { imageWidth, imageHeight ->
                        if (collisionLogic == null) {
                            collisionLogic = CollisionLogic(screenCenterX = imageWidth / 2)
                        }
                        collisionLogic!!
                    }
                )
            }
        }
    }

    // This function is IDENTICAL to the phone app's
    private fun triggerHapticFeedback(type: HapticType) {
        vibrator.cancel()
        when (type) {
            HapticType.DANGER -> {
                val effect = VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            }
            HapticType.WARNING -> {
                val timings = longArrayOf(0, 75, 100, 75)
                val amplitudes = intArrayOf(0, 150, 0, 150)
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrator.vibrate(effect)
            }
            HapticType.NONE -> { /* No-op */ }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        objectDetector.close()
    }
}

/**
 * The main Composable function for the GLASS app.
 */
@Composable
fun GlassCollisionApp(
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
    var hasPermissions by remember {
        mutableStateOf(REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    var isWalking by remember { mutableStateOf(false) }
    // We only care about the *worst* risk, not the whole list
    var worstRisk by remember { mutableStateOf(HapticType.NONE) }

    // --- Sensor Event Listener (Identical to phone) ---
    val sensorEventListener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
                    stepDetector.processSensorEvent(
                        eventTimestamp = event.timestamp / 1_000_000,
                        accelY = event.values[1]
                    )
                    isWalking = stepDetector.isUserWalking
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    // --- Lifecycle Observer (Identical to phone) ---
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
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager.unregisterListener(sensorEventListener)
        }
    }

    // --- Permission Launcher (Identical to phone) ---
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            hasPermissions = permissions.values.all { it }
        }
    )
    LaunchedEffect(key1 = true) {
        if (!hasPermissions) {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    // --- Main UI Layout ---
    Box(modifier = Modifier.fillMaxSize()) {
        if (hasPermissions) {
            // --- Camera Analysis (No Preview) ---
            // We run the camera for analysis, but we don't need to show it.
            // A small preview is good for the user to know it's on.
            CameraAnalysis(
                objectDetector = objectDetector,
                onRiskUpdate = { hapticType ->
                    worstRisk = hapticType
                    if (isWalking) {
                        onHapticTrigger(hapticType)
                    } else {
                        onHapticTrigger(HapticType.NONE)
                    }
                },
                getCollisionLogic = getCollisionLogic
            )

            // --- SIMPLE GLASS UI ---
            // This is the only part that is truly different
            // from the phone app.
            when (worstRisk) {
                HapticType.DANGER -> {
                    Text(
                        "DANGER",
                        color = Color.Red,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                HapticType.WARNING -> {
                    Text(
                        "WARNING",
                        color = Color.Yellow,
                        fontSize = 24.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                HapticType.NONE -> {
                    Text(
                        "Status: ${if (isWalking) "WALKING" else "Still"}",
                        color = Color.White,
                        fontSize = 20.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    )
                }
            }

        } else {
            // --- Permissions Denied Screen ---
            Text(
                "Need Camera Permission",
                color = Color.Red,
                fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * A Composable that handles the CameraX setup and image analysis
 * for the Glass app. We include a tiny preview so the user knows
 * the camera is active.
 */
@Composable
fun CameraAnalysis(
    objectDetector: ObjectDetector,
    onRiskUpdate: (HapticType) -> Unit,
    getCollisionLogic: (imageWidth: Int, imageHeight: Int) -> CollisionLogic
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // We still use a PreviewView, but we'll make it tiny
    val previewView = remember { PreviewView(context) }

    // This Box contains the tiny preview
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                previewView.apply {
                    layoutParams = ViewGroup.LayoutParams(1, 1) // 1x1 pixel, invisible
                }
            },
            modifier = Modifier.padding(0.dp) // Make it take no space
        )
        // This block is identical to the phone's
        LaunchedEffect(cameraProviderFuture) {
            val cameraProvider = cameraProviderFuture.await()

            // We *must* bind a preview, even if it's tiny
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            val collisionLogic = getCollisionLogic(image.width, image.height)

                            objectDetector.process(image)
                                .addOnSuccessListener { detectedObjects ->
                                    val worstRisk = detectedObjects
                                        .filter { it.labels.any { l -> l.text == "Person" } }
                                        .map {
                                            collisionLogic.assessRisk(
                                                it.boundingBox,
                                                image.width,
                                                image.height
                                            ).hapticType
                                        }
                                        .maxOfOrNull { it.ordinal }

                                    val hapticType = worstRisk?.let { HapticType.entries[it] } ?: HapticType.NONE
                                    onRiskUpdate(hapticType)
                                }
                                .addOnFailureListener { e -> Log.e(TAG, "Detection failed", e) }
                                .addOnCompleteListener { imageProxy.close() }
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
    }
}

// Helper function (identical to phone app)
suspend fun <T> ListenableFuture<T>.await(): T = suspendCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: Exception) {
            cont.resumeWith(Result.failure(e))
        }
    }, Dispatchers.Main.asExecutor())
}
