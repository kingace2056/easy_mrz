package com.mlabs.easy_mrz

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.view.Surface
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraXMrzScanner(
    private val context: Context,
    private val activityProvider: () -> Activity?,
    private val channel: MethodChannel,
) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val isAnalyzing = AtomicBoolean(false)

    val previewView: PreviewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lastEmittedText: String? = null
    private var isFrontCamera = false
    private var lastAnalyzedAtMs = 0L

    init {
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
            },
            mainExecutor,
        )
    }

    fun startScanning(useFrontCamera: Boolean) {
        isFrontCamera = useFrontCamera

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            emitError("Camera permission not granted")
            return
        }

        val lifecycleOwner = activityProvider() as? LifecycleOwner
        if (lifecycleOwner == null) {
            emitError("Scanner requires an attached Flutter activity")
            return
        }

        withCameraProvider {
            bindUseCases(lifecycleOwner)
        }
    }

    fun stopScanning() {
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        lastEmittedText = null
        isAnalyzing.set(false)
    }

    fun flashlightOn() {
        camera?.cameraControl?.enableTorch(true)
    }

    fun flashlightOff() {
        camera?.cameraControl?.enableTorch(false)
    }

    fun takePhoto(result: MethodChannel.Result, crop: Boolean) {
        val capture = imageCapture
        if (capture == null) {
            result.error("camera_not_started", "Camera preview is not running", null)
            return
        }

        capture.takePicture(
            analysisExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = imageProxyToBitmap(image)
                        if (bitmap == null) {
                            mainExecutor.execute {
                                result.error("capture_failed", "Captured image could not be decoded", null)
                            }
                            return
                        }

                        val finalBitmap = if (crop) {
                            calculateCutoutRect(bitmap, cropToMrz = false)
                        } else {
                            bitmap
                        }
                        val output = ByteArrayOutputStream()
                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
                        val bytes = output.toByteArray()
                        mainExecutor.execute {
                            result.success(bytes)
                        }
                    } catch (error: Exception) {
                        mainExecutor.execute {
                            result.error("capture_failed", error.localizedMessage, null)
                        }
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    mainExecutor.execute {
                        result.error("capture_failed", exception.localizedMessage, null)
                    }
                }
            },
        )
    }

    fun dispose() {
        stopScanning()
        recognizer.close()
        analysisExecutor.shutdown()
    }

    private fun withCameraProvider(action: () -> Unit) {
        if (cameraProvider != null) {
            action()
            return
        }

        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                action()
            },
            mainExecutor,
        )
    }

    private fun bindUseCases(lifecycleOwner: LifecycleOwner) {
        val provider = cameraProvider ?: return

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .build()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .build()
            .also { useCase ->
                useCase.setAnalyzer(analysisExecutor) { image ->
                    analyzeImage(image)
                }
            }

        val selector = if (isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, analysis)
        } catch (error: Exception) {
            emitError("Failed to start camera: ${error.localizedMessage}")
        }
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!isAnalyzing.compareAndSet(false, true) || now - lastAnalyzedAtMs < 150L) {
            imageProxy.close()
            return
        }
        lastAnalyzedAtMs = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            isAnalyzing.set(false)
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotation)
        val imageWidth = if (rotation % 180 == 0) imageProxy.width else imageProxy.height
        val imageHeight = if (rotation % 180 == 0) imageProxy.height else imageProxy.width

        recognizer.process(image)
            .addOnSuccessListener { text ->
                val lines = extractMrzLines(text, imageWidth, imageHeight)
                if (lines.isEmpty()) {
                    return@addOnSuccessListener
                }

                val parsed = lines.joinToString("\n")
                if (parsed != lastEmittedText) {
                    lastEmittedText = parsed
                    channel.invokeMethod("onParsed", parsed)
                }
            }
            .addOnFailureListener { error ->
                emitError("Text recognition failed: ${error.localizedMessage}")
            }
            .addOnCompleteListener {
                imageProxy.close()
                isAnalyzing.set(false)
            }
    }

    private fun extractMrzLines(text: Text, imageWidth: Int, imageHeight: Int): List<String> {
        val lowerBound = (imageHeight * 0.45f).toInt()
        val preferred = mutableListOf<Pair<Int, String>>()
        val fallback = mutableListOf<Pair<Int, String>>()

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val normalized = normalizeLine(line.text)
                if (normalized.length < 5) {
                    continue
                }

                val top = line.boundingBox?.top ?: Int.MAX_VALUE
                val centerY = line.boundingBox?.centerY() ?: 0
                val target = if (centerY >= lowerBound && line.boundingBox != null) preferred else fallback
                target += top to normalized
            }
        }

        val candidates = if (preferred.isNotEmpty()) preferred else fallback
        return candidates
            .sortedBy { it.first }
            .map { it.second }
            .distinct()
    }

    private fun normalizeLine(value: String): String {
        val replacements = mapOf(
            '«' to '<',
            '‹' to '<',
            '›' to '<',
            '﹤' to '<',
            '＜' to '<',
            '|' to '<',
            '¦' to '<',
            '>' to '<',
            ' ' to null,
            '\t' to null,
        )

        val builder = StringBuilder()
        for (character in value.uppercase()) {
            val replacement = replacements[character]
            val normalized = replacement ?: if (character in replacements) continue else character
            val isDigit = normalized in '0'..'9'
            val isAlpha = normalized in 'A'..'Z'
            if (isDigit || isAlpha || normalized == '<') {
                builder.append(normalized)
            }
        }
        return builder.toString()
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val nv21 = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val stream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, stream)
        val imageBytes = stream.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
        return rotateBitmap(bitmap, image.imageInfo.rotationDegrees.toFloat())
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val imageSize = image.width * image.height
        val out = ByteArray(imageSize + 2 * (imageSize / 4))

        unpackPlane(image.planes[0], image.width, image.height, out, 0, 1)
        unpackPlane(image.planes[2], image.width / 2, image.height / 2, out, imageSize, 2)
        unpackPlane(image.planes[1], image.width / 2, image.height / 2, out, imageSize + 1, 2)

        return out
    }

    private fun unpackPlane(
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        out: ByteArray,
        offset: Int,
        pixelStride: Int,
    ) {
        val buffer = plane.buffer
        buffer.rewind()

        val rowStride = plane.rowStride
        val planePixelStride = plane.pixelStride
        val rowData = ByteArray(rowStride)
        var outputOffset = offset

        for (row in 0 until height) {
            val length = if (planePixelStride == 1 && pixelStride == 1) {
                width
            } else {
                (width - 1) * planePixelStride + 1
            }

            buffer.get(rowData, 0, length)
            var inputOffset = 0
            for (column in 0 until width) {
                out[outputOffset] = rowData[inputOffset]
                outputOffset += pixelStride
                inputOffset += planePixelStride
            }

            if (row < height - 1) {
                buffer.position(buffer.position() + rowStride - length)
            }
        }
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        if (angle == 0f) {
            return source
        }

        val matrix = Matrix().apply { postRotate(angle) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun calculateCutoutRect(bitmap: Bitmap, cropToMrz: Boolean): Bitmap {
        val documentFrameRatio = 1.42
        val width: Double
        val height: Double

        if (bitmap.height > bitmap.width) {
            width = bitmap.width * 0.9
            height = width / documentFrameRatio
        } else {
            height = bitmap.height * 0.75
            width = height * documentFrameRatio
        }

        val mrzZoneOffset = if (cropToMrz) height * 0.6 else 0.0
        val topOffset = (bitmap.height - height) / 2 + mrzZoneOffset
        val leftOffset = (bitmap.width - width) / 2

        return Bitmap.createBitmap(
            bitmap,
            leftOffset.toInt(),
            topOffset.toInt(),
            width.toInt(),
            (height - mrzZoneOffset).toInt(),
        )
    }

    private fun emitError(message: String) {
        mainExecutor.execute {
            channel.invokeMethod("onError", message)
        }
    }
}
