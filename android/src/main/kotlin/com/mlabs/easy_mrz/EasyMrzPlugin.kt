package com.mlabs.easy_mrz

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class EasyMrzPlugin : FlutterPlugin, ActivityAware {
    private lateinit var viewFactory: MRZScannerFactory
    private var activity: Activity? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        viewFactory = MRZScannerFactory(
            messenger = flutterPluginBinding.binaryMessenger,
            activityProvider = { activity },
        )
        flutterPluginBinding.platformViewRegistry.registerViewFactory(
            "easy_mrz_scanner",
            viewFactory,
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) = Unit

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }
}

class MRZScannerFactory(
    private val messenger: BinaryMessenger,
    private val activityProvider: () -> Activity?,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context?, id: Int, args: Any?): PlatformView {
        val platformContext = context ?: activityProvider()?.applicationContext
        requireNotNull(platformContext) { "Android context is required to create MRZ scanner view." }
        return MRZScannerView(platformContext, messenger, id, activityProvider)
    }
}

class MRZScannerView internal constructor(
    context: Context,
    messenger: BinaryMessenger,
    id: Int,
    activityProvider: () -> Activity?,
) : PlatformView, MethodChannel.MethodCallHandler {
    private val methodChannel = MethodChannel(messenger, "easy_mrz_scanner_$id")
    private val scanner = CameraXMrzScanner(context, activityProvider, methodChannel)

    init {
        methodChannel.setMethodCallHandler(this)
    }

    override fun getView(): View = scanner.previewView

    override fun dispose() {
        scanner.dispose()
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
        when (call.method) {
            "start" -> {
                scanner.startScanning(call.argument<Boolean>("isFrontCam") == true)
                result.success(null)
            }

            "stop" -> {
                scanner.stopScanning()
                result.success(null)
            }

            "flashlightOn" -> {
                scanner.flashlightOn()
                result.success(null)
            }

            "flashlightOff" -> {
                scanner.flashlightOff()
                result.success(null)
            }

            "takePhoto" -> {
                scanner.takePhoto(result, call.argument<Boolean>("crop") != false)
            }

            else -> result.notImplemented()
        }
    }
}
