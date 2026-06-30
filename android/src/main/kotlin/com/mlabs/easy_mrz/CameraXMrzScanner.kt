package com.mlabs.easy_mrz

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.ViewGroup
import androidx.camera.core.AspectRatio
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
import com.googlecode.tesseract.android.TessBaseAPI
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CameraXMrzScanner(
    private val context: Context,
    private val activityProvider: () -> Activity?,
    private val channel: MethodChannel,
) {
    companion object {
        private const val TAG = "EasyMrzScanner"
        private const val OCR_LANGUAGE = "ocrb"
        private const val OCR_INTERVAL_MS = 425L
        private const val LOG_INTERVAL_MS = 1500L
        private const val OCR_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<"
        private const val PASSPORT_MRZ_HEIGHT_RATIO = 0.32
        private const val PASSPORT_LINE_OVERLAP_RATIO = 0.08
        private const val MIN_TD3_SCORE = 34
    }

    private data class Td3Candidate(
        val line1: String,
        val line2: String,
        val score: Int,
        val validChecks: Int,
        val source: String,
    )

    private data class DetectionResult(
        val lines: List<String>,
        val score: Int,
        val source: String,
        val requiresStability: Boolean,
    )

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    private val isAnalyzing = AtomicBoolean(false)
    private val tessBaseApi = TessBaseAPI()
    private val tessInitLock = Any()
    private val isTesseractReady = AtomicBoolean(false)
    private val tessDataRoot = File(context.filesDir, "tesseract")
    private val tessDataDir = File(tessDataRoot, "tessdata")

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
    private var pendingDetectionText: String? = null
    private var pendingDetectionCount = 0
    private var isFrontCamera = false
    private var lastAnalyzedAtMs = 0L
    private var lastDiagnosticLogAtMs = 0L
    private var lifecycleOwner: LifecycleOwner? = null
    private var isPreviewRunning = false

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
        this.lifecycleOwner = lifecycleOwner

        withCameraProvider {
            bindScanningUseCases(lifecycleOwner)
        }
        analysisExecutor.execute {
            try {
                ensureTesseractReady()
            } catch (error: Exception) {
                emitError("Failed to initialize Tesseract: ${error.localizedMessage}")
            }
        }
    }

    fun stopScanning() {
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        lastEmittedText = null
        pendingDetectionText = null
        pendingDetectionCount = 0
        isAnalyzing.set(false)
        isPreviewRunning = false
    }

    fun flashlightOn() {
        camera?.cameraControl?.enableTorch(true)
    }

    fun flashlightOff() {
        camera?.cameraControl?.enableTorch(false)
    }

    fun takePhoto(result: MethodChannel.Result, crop: Boolean) {
        val owner = lifecycleOwner
        if (owner == null) {
            result.error("camera_not_started", "Camera preview is not running", null)
            return
        }

        withCameraProvider {
            bindPhotoCaptureUseCases(owner)

            val capture = imageCapture
            if (capture == null) {
                result.error("camera_not_started", "Camera preview is not running", null)
                return@withCameraProvider
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
                            mainExecutor.execute {
                                if (isPreviewRunning) {
                                    bindScanningUseCases(owner)
                                }
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        mainExecutor.execute {
                            result.error("capture_failed", exception.localizedMessage, null)
                            if (isPreviewRunning) {
                                bindScanningUseCases(owner)
                            }
                        }
                    }
                },
            )
        }
    }

    fun dispose() {
        stopScanning()
        synchronized(tessInitLock) {
            if (isTesseractReady.get()) {
                tessBaseApi.stop()
                isTesseractReady.set(false)
            }
        }
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

    private fun bindScanningUseCases(lifecycleOwner: LifecycleOwner) {
        val provider = cameraProvider ?: return

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = buildPreviewUseCase(rotation)
        val analysis = buildAnalysisUseCase(rotation)

        val selector = if (isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            provider.unbindAll()
            imageCapture = null
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            isPreviewRunning = true
        } catch (error: Exception) {
            emitError("Failed to start camera: ${error.localizedMessage}")
        }
    }

    private fun bindPhotoCaptureUseCases(lifecycleOwner: LifecycleOwner) {
        val provider = cameraProvider ?: return
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = buildPreviewUseCase(rotation)
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .build()

        val selector = if (isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            provider.unbindAll()
            imageCapture = capture
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        } catch (error: Exception) {
            emitError("Failed to prepare photo capture: ${error.localizedMessage}")
        }
    }

    private fun buildPreviewUseCase(rotation: Int): Preview {
        return Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
    }

    private fun buildAnalysisUseCase(rotation: Int): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()
            .also { useCase ->
                useCase.setAnalyzer(analysisExecutor) { image ->
                    analyzeImage(image)
                }
            }
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!isAnalyzing.compareAndSet(false, true) || now - lastAnalyzedAtMs < OCR_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastAnalyzedAtMs = now

        try {
            ensureTesseractReady()

            val previewDetection = snapshotPreviewBitmap()?.let { bitmap ->
                recognizeMrz(bitmap, "preview")
            }
            if (previewDetection != null && !previewDetection.requiresStability) {
                emitDetection(previewDetection)
                return
            }

            val analysisBitmap = imageProxyToBitmap(imageProxy)
            if (analysisBitmap == null) {
                maybeLogTesseract("", "analysis-null")
                return
            }

            val analysisDetection = recognizeMrz(analysisBitmap, "analysis")
            val bestDetection = listOfNotNull(previewDetection, analysisDetection)
                .maxByOrNull { it.score }
            if (bestDetection != null) {
                emitDetection(bestDetection)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Tesseract OCR failed", error)
            emitError("Tesseract OCR failed: ${error.localizedMessage}")
        } finally {
            imageProxy.close()
            isAnalyzing.set(false)
        }
    }

    private fun recognizeMrz(sourceBitmap: Bitmap, source: String): DetectionResult? {
        recognizePassportTd3(sourceBitmap, source)?.let { candidate ->
            Log.d(
                TAG,
                "Detected passport MRZ from ${candidate.source}: ${candidate.line1} | ${candidate.line2} score=${candidate.score} checks=${candidate.validChecks}",
            )
            return DetectionResult(
                lines = listOf(candidate.line1, candidate.line2),
                score = candidate.score + candidate.validChecks * 8,
                source = candidate.source,
                requiresStability = candidate.validChecks < 3 && candidate.score < 40,
            )
        }

        val crops = listOf(
            "$source-mrz" to calculateCutoutRect(sourceBitmap, cropToMrz = true),
            "$source-document" to calculateCutoutRect(sourceBitmap, cropToMrz = false),
        )

        var diagnosticText = ""
        for ((label, crop) in crops) {
            val variants = listOf(
                label to crop,
                "$label-bw-140" to preprocessForMrz(crop, threshold = 140),
                "$label-bw-165" to preprocessForMrz(crop, threshold = 165),
            )

            for ((variantLabel, bitmap) in variants) {
                val rawText = scanMrz(bitmap)
                if (normalizeLine(rawText).length > normalizeLine(diagnosticText).length) {
                    diagnosticText = rawText
                }

                val lines = extractMrzLines(rawText)
                if (lines.isNotEmpty()) {
                    Log.d(TAG, "Detected MRZ from $variantLabel: ${lines.joinToString(" | ")}")
                    return DetectionResult(
                        lines = lines,
                        score = scoreGenericLines(lines),
                        source = variantLabel,
                        requiresStability = true,
                    )
                }
            }
        }

        maybeLogTesseract(diagnosticText, source)
        return null
    }

    private fun recognizePassportTd3(sourceBitmap: Bitmap, source: String): Td3Candidate? {
        val document = calculateCutoutRect(sourceBitmap, cropToMrz = false)
        val mrzBand = cropPassportMrzBand(document)
        val (line1Bitmap, line2Bitmap) = splitPassportMrzLines(mrzBand)
        val line1Candidates = collectLineCandidates(line1Bitmap, isFirstLine = true)
        val line2Candidates = collectLineCandidates(line2Bitmap, isFirstLine = false)

        if (line1Candidates.isEmpty() || line2Candidates.isEmpty()) {
            return null
        }

        var bestCandidate: Td3Candidate? = null
        for (line1Candidate in line1Candidates.take(8)) {
            for (line2Candidate in line2Candidates.take(8)) {
                val normalizedLine1 = normalizeTd3Line1(line1Candidate) ?: continue
                val normalizedLine2 = normalizeTd3Line2(line2Candidate) ?: continue
                val validChecks = countTd3ValidChecks(normalizedLine2)
                val score = scoreTd3Candidate(normalizedLine1, normalizedLine2, validChecks)
                val candidate = Td3Candidate(
                    line1 = normalizedLine1,
                    line2 = normalizedLine2,
                    score = score,
                    validChecks = validChecks,
                    source = source,
                )

                if (bestCandidate == null || candidate.score > bestCandidate.score) {
                    bestCandidate = candidate
                }

                if (candidate.score >= MIN_TD3_SCORE && candidate.validChecks >= 2) {
                    return candidate
                }
            }
        }

        bestCandidate?.let { candidate ->
            if (candidate.score >= 26) {
                Log.d(
                    TAG,
                    "Best passport candidate from $source rejected: ${candidate.line1} | ${candidate.line2} score=${candidate.score} checks=${candidate.validChecks}",
                )
            }
        }
        return null
    }

    private fun emitDetection(detection: DetectionResult) {
        val parsed = detection.lines.joinToString("\n")
        if (parsed == lastEmittedText) {
            return
        }

        if (detection.requiresStability) {
            if (pendingDetectionText == parsed) {
                pendingDetectionCount += 1
            } else {
                pendingDetectionText = parsed
                pendingDetectionCount = 1
            }

            if (pendingDetectionCount < 2) {
                Log.d(TAG, "Holding ${detection.source} candidate until it repeats: $parsed")
                return
            }
        }

        pendingDetectionText = null
        pendingDetectionCount = 0
        lastEmittedText = parsed
        mainExecutor.execute {
            channel.invokeMethod("onParsed", parsed)
        }
    }

    private fun maybeLogTesseract(rawText: String, source: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagnosticLogAtMs < LOG_INTERVAL_MS) {
            return
        }
        lastDiagnosticLogAtMs = now

        Log.d(
            TAG,
            "No MRZ detected from $source. normalized='${normalizeLine(rawText).take(160)}' raw='${rawText.replace('\n', '|').take(240)}'",
        )
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

    private fun extractMrzLines(rawText: String): List<String> {
        val normalizedLines = rawText
            .replace("\r", "\n")
            .split('\n')
            .map(::normalizeLine)
            .filter { it.length >= 5 }

        val structuredLines = normalizedLines
            .filter { line ->
                line.length in setOf(30, 36, 44) && line.count { it == '<' } >= 2
            }
            .takeLast(3)

        if (structuredLines.size >= 2) {
            return structuredLines
        }

        val merged = normalizeLine(rawText)
        for (lineLength in listOf(44, 36, 30)) {
            val expectedLength = lineLength * 2
            if (merged.length >= expectedLength - 2 && merged.count { it == '<' } >= 4) {
                val reconstructed = listOf(
                    merged.take(lineLength).padEnd(lineLength, '<'),
                    merged.drop(lineLength).take(lineLength).padEnd(lineLength, '<'),
                )
                if (reconstructed.all { it.count { char -> char == '<' } >= 2 }) {
                    return reconstructed
                }
            }
        }

        if (structuredLines.size == 1 && structuredLines.first().length in setOf(30, 36, 44)) {
            return structuredLines
        }

        return emptyList()
    }

    private fun scoreGenericLines(lines: List<String>): Int {
        var score = 0
        for (line in lines) {
            if (line.length in setOf(30, 36, 44)) {
                score += 8
            }
            score += minOf(line.count { it == '<' }, 6)
            if (line.startsWith("P<") || line.startsWith("I<") || line.startsWith("V<")) {
                score += 6
            }
        }
        return score
    }

    private fun collectLineCandidates(bitmap: Bitmap, isFirstLine: Boolean): List<String> {
        val candidates = linkedSetOf<String>()
        val thresholds = listOf<Int?>(null, 95, 110, 125, 140, 160, 180)
        val scaledVariants = linkedSetOf<Bitmap>()
        scaledVariants += bitmap
        scaledVariants += upscaleForLineOcr(bitmap)

        for (variant in scaledVariants) {
            for (threshold in thresholds) {
                val prepared = when (threshold) {
                    null -> variant
                    else -> preprocessForMrz(variant, threshold)
                }
                val raw = scanMrz(prepared, TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
                val normalized = normalizeLine(raw)
                if (normalized.length >= 20) {
                    candidates += normalized
                }
            }
        }

        return candidates
            .map { if (isFirstLine) movePassportPrefixToStart(it) else it }
            .sortedByDescending { it.length }
    }

    private fun cropPassportMrzBand(document: Bitmap): Bitmap {
        val bandHeight = (document.height * PASSPORT_MRZ_HEIGHT_RATIO)
            .toInt()
            .coerceAtLeast(1)
            .coerceAtMost(document.height)
        val top = (document.height - bandHeight).coerceAtLeast(0)
        return Bitmap.createBitmap(document, 0, top, document.width, document.height - top)
    }

    private fun splitPassportMrzLines(mrzBand: Bitmap): Pair<Bitmap, Bitmap> {
        val overlap = (mrzBand.height * PASSPORT_LINE_OVERLAP_RATIO).toInt().coerceAtLeast(1)
        val lineHeight = (mrzBand.height / 2).coerceAtLeast(1)
        val firstHeight = (lineHeight + overlap).coerceAtMost(mrzBand.height)
        val secondTop = (lineHeight - overlap).coerceAtLeast(0)
        val secondHeight = (mrzBand.height - secondTop).coerceAtLeast(1)

        val firstLine = Bitmap.createBitmap(
            mrzBand,
            0,
            0,
            mrzBand.width,
            firstHeight,
        )
        val secondLine = Bitmap.createBitmap(
            mrzBand,
            0,
            secondTop,
            mrzBand.width,
            secondHeight,
        )
        return firstLine to secondLine
    }

    private fun upscaleForLineOcr(bitmap: Bitmap): Bitmap {
        val targetWidth = when {
            bitmap.width < 1400 -> bitmap.width * 3
            bitmap.width < 2200 -> bitmap.width * 2
            else -> bitmap.width
        }
        if (targetWidth == bitmap.width) {
            return bitmap
        }
        val targetHeight = (bitmap.height * (targetWidth.toFloat() / bitmap.width))
            .toInt()
            .coerceAtLeast(bitmap.height)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun movePassportPrefixToStart(value: String): String {
        val index = value.indexOf("P<")
        if (index <= 0 || index > 6) {
            return value
        }
        return value.substring(index) + value.substring(0, index)
    }

    private fun normalizeTd3Line1(raw: String): String? {
        val reordered = movePassportPrefixToStart(raw)
        if (reordered.length < 30) {
            return null
        }

        val line = fitMrzLength(reordered, 44).toCharArray()
        line[0] = 'P'
        line[1] = '<'
        for (index in 2..4) {
            line[index] = repairAlphaChar(line[index])
        }
        for (index in 5 until line.size) {
            line[index] = repairLine1BodyChar(line[index])
        }

        val normalized = String(line)
        if (!normalized.startsWith("P<")) {
            return null
        }
        return normalized
    }

    private fun normalizeTd3Line2(raw: String): String? {
        if (raw.length < 30) {
            return null
        }

        val line = fitMrzLength(raw, 44).toCharArray()
        for (index in 0..8) {
            line[index] = repairAlphaNumericChar(line[index])
        }
        line[9] = repairDigitChar(line[9])
        for (index in 10..12) {
            line[index] = repairAlphaChar(line[index])
        }
        for (index in 13..18) {
            line[index] = repairDigitChar(line[index])
        }
        line[19] = repairDigitChar(line[19])
        line[20] = repairSexChar(line[20])
        for (index in 21..26) {
            line[index] = repairDigitChar(line[index])
        }
        line[27] = repairDigitChar(line[27])
        for (index in 28..41) {
            line[index] = repairAlphaNumericChar(line[index])
        }
        line[42] = repairDigitChar(line[42])
        line[43] = repairDigitChar(line[43])

        return String(line)
    }

    private fun fitMrzLength(value: String, targetLength: Int): String {
        return when {
            value.length == targetLength -> value
            value.length > targetLength -> value.take(targetLength)
            else -> value.padEnd(targetLength, '<')
        }
    }

    private fun repairLine1BodyChar(value: Char): Char {
        return when (value) {
            '<' -> '<'
            in 'A'..'Z' -> value
            in '0'..'9' -> repairAlphaChar(value)
            else -> '<'
        }
    }

    private fun repairAlphaNumericChar(value: Char): Char {
        return when (value) {
            '<' -> '<'
            in '0'..'9' -> value
            in 'A'..'Z' -> value
            else -> repairAlphaChar(value)
        }
    }

    private fun repairAlphaChar(value: Char): Char {
        return when (value) {
            '0' -> 'O'
            '1' -> 'I'
            '2' -> 'Z'
            '5' -> 'S'
            '6' -> 'G'
            '8' -> 'B'
            '<' -> '<'
            in 'A'..'Z' -> value
            else -> '<'
        }
    }

    private fun repairDigitChar(value: Char): Char {
        return when (value) {
            in '0'..'9' -> value
            'O', 'Q', 'D', 'U' -> '0'
            'I', 'L' -> '1'
            'Z' -> '2'
            'S' -> '5'
            'G' -> '6'
            'B' -> '8'
            else -> '<'
        }
    }

    private fun repairSexChar(value: Char): Char {
        return when (value) {
            'M', 'F', '<' -> value
            'H' -> 'M'
            else -> '<'
        }
    }

    private fun scoreTd3Candidate(line1: String, line2: String, validChecks: Int): Int {
        var score = 0
        if (line1.length == 44) score += 4
        if (line2.length == 44) score += 4
        if (line1.startsWith("P<")) score += 8
        if (line1.substring(2, 5).all { it in 'A'..'Z' || it == '<' }) score += 3
        if (line1.count { it == '<' } >= 4) score += 2
        if (line2.substring(10, 13).all { it in 'A'..'Z' || it == '<' }) score += 3
        if (line2.substring(13, 19).all { it.isDigit() }) score += 4
        if (line2.substring(21, 27).all { it.isDigit() }) score += 4
        if (line2[20] == 'M' || line2[20] == 'F' || line2[20] == '<') score += 2
        score += validChecks * 6
        return score
    }

    private fun countTd3ValidChecks(line2: String): Int {
        if (line2.length != 44) {
            return 0
        }

        var valid = 0
        if (mrzCheckDigit(line2.substring(0, 9)) == line2[9]) valid++
        if (mrzCheckDigit(line2.substring(13, 19)) == line2[19]) valid++
        if (mrzCheckDigit(line2.substring(21, 27)) == line2[27]) valid++
        if (mrzCheckDigit(line2.substring(28, 42)) == line2[42]) valid++

        val composite = buildString {
            append(line2.substring(0, 10))
            append(line2.substring(13, 20))
            append(line2.substring(21, 43))
        }
        if (mrzCheckDigit(composite) == line2[43]) valid++
        return valid
    }

    private fun mrzCheckDigit(value: String): Char {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        for ((index, character) in value.withIndex()) {
            sum += mrzCharValue(character) * weights[index % weights.size]
        }
        return ('0'.code + (sum % 10)).toChar()
    }

    private fun mrzCharValue(value: Char): Int {
        return when (value) {
            in '0'..'9' -> value.code - '0'.code
            in 'A'..'Z' -> value.code - 'A'.code + 10
            '<' -> 0
            else -> 0
        }
    }

    private fun preprocessForMrz(bitmap: Bitmap, threshold: Int): Bitmap {
        val processed = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(processed.width * processed.height)
        processed.getPixels(pixels, 0, processed.width, 0, 0, processed.width, processed.height)

        for (index in pixels.indices) {
            val pixel = pixels[index]
            val gray = (Color.red(pixel) * 30 + Color.green(pixel) * 59 + Color.blue(pixel) * 11) / 100
            pixels[index] = if (gray >= threshold) {
                Color.WHITE
            } else {
                Color.BLACK
            }
        }

        processed.setPixels(pixels, 0, processed.width, 0, 0, processed.width, processed.height)
        return processed
    }

    private fun scanMrz(bitmap: Bitmap, pageSegMode: Int = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK): String {
        synchronized(tessInitLock) {
            ensureTesseractReady()
            tessBaseApi.pageSegMode = pageSegMode
            tessBaseApi.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, OCR_CHARSET)
            tessBaseApi.setImage(bitmap)
            val text = tessBaseApi.utF8Text.orEmpty()
            tessBaseApi.clear()
            return text
        }
    }

    private fun snapshotPreviewBitmap(): Bitmap? {
        val latch = CountDownLatch(1)
        var snapshot: Bitmap? = null
        mainExecutor.execute {
            snapshot = previewView.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
            latch.countDown()
        }
        if (!latch.await(120, TimeUnit.MILLISECONDS)) {
            return null
        }
        return snapshot
    }

    private fun ensureTesseractReady() {
        if (isTesseractReady.get()) {
            return
        }

        synchronized(tessInitLock) {
            if (isTesseractReady.get()) {
                return
            }

            prepareTrainedData()
            val initialized = tessBaseApi.init(tessDataRoot.absolutePath, OCR_LANGUAGE)
            if (!initialized) {
                throw IllegalStateException("Unable to initialize Tesseract for language '$OCR_LANGUAGE'")
            }
            tessBaseApi.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            tessBaseApi.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, OCR_CHARSET)
            tessBaseApi.setVariable("preserve_interword_spaces", "0")
            isTesseractReady.set(true)
        }
    }

    @Throws(IOException::class)
    private fun prepareTrainedData() {
        if (!tessDataDir.exists() && !tessDataDir.mkdirs()) {
            throw IOException("Unable to create tessdata directory at ${tessDataDir.absolutePath}")
        }

        val trainedDataFile = File(tessDataDir, "$OCR_LANGUAGE.traineddata")
        if (trainedDataFile.exists() && trainedDataFile.length() > 0L) {
            return
        }

        context.assets.open("$OCR_LANGUAGE.traineddata").use { input ->
            trainedDataFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
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
        val rawWidth: Double
        val rawHeight: Double

        if (bitmap.height > bitmap.width) {
            rawWidth = bitmap.width * 0.9
            rawHeight = rawWidth / documentFrameRatio
        } else {
            rawHeight = bitmap.height * 0.75
            rawWidth = rawHeight * documentFrameRatio
        }

        // Some Android devices provide square analysis frames, where the
        // nominal document aspect ratio would otherwise push the crop outside
        // the bitmap bounds and crash Bitmap.createBitmap(...).
        val scale = minOf(
            1.0,
            bitmap.width / rawWidth,
            bitmap.height / rawHeight,
        )
        val width = (rawWidth * scale).coerceAtLeast(1.0)
        val height = (rawHeight * scale).coerceAtLeast(1.0)

        val mrzZoneOffset = if (cropToMrz) height * 0.6 else 0.0
        val leftOffset = ((bitmap.width - width) / 2).coerceAtLeast(0.0)
        val topOffset = ((bitmap.height - height) / 2 + mrzZoneOffset).coerceAtLeast(0.0)
        val croppedHeight = (height - mrzZoneOffset).coerceAtLeast(1.0)

        val safeLeft = leftOffset.toInt().coerceIn(0, bitmap.width - 1)
        val safeTop = topOffset.toInt().coerceIn(0, bitmap.height - 1)
        val safeWidth = width.toInt().coerceAtLeast(1).coerceAtMost(bitmap.width - safeLeft)
        val safeHeight = croppedHeight.toInt().coerceAtLeast(1).coerceAtMost(bitmap.height - safeTop)

        return Bitmap.createBitmap(
            bitmap,
            safeLeft,
            safeTop,
            safeWidth,
            safeHeight,
        )
    }

    private fun emitError(message: String) {
        mainExecutor.execute {
            channel.invokeMethod("onError", message)
        }
    }
}
