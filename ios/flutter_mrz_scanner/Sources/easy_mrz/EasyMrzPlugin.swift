import AVFoundation
import Flutter
import UIKit

public final class FlutterMrzScannerPlugin: NSObject, FlutterPlugin {
    public static func register(with registrar: FlutterPluginRegistrar) {
        let factory = FlutterMRZScannerFactory(messenger: registrar.messenger())
        registrar.register(factory, withId: "easy_mrz_scanner")
    }
}

final class FlutterMRZScannerFactory: NSObject, FlutterPlatformViewFactory {
    private let messenger: FlutterBinaryMessenger

    init(messenger: FlutterBinaryMessenger) {
        self.messenger = messenger
        super.init()
    }

    func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol {
        FlutterStandardMessageCodec.sharedInstance()
    }

    func create(
        withFrame frame: CGRect,
        viewIdentifier viewId: Int64,
        arguments args: Any?
    ) -> FlutterPlatformView {
        let channel = FlutterMethodChannel(
            name: "easy_mrz_scanner_\(viewId)",
            binaryMessenger: messenger
        )
        return FlutterMRZScanner(frame, viewId: viewId, channel: channel, args: args)
    }
}

final class FlutterMRZScanner: NSObject, FlutterPlatformView, MRZScannerViewDelegate {
    private let mrzView: MRZScannerView
    private let channel: FlutterMethodChannel
    private var photoResult: FlutterResult?

    init(_ frame: CGRect, viewId: Int64, channel: FlutterMethodChannel, args: Any?) {
        self.channel = channel
        self.mrzView = MRZScannerView(frame: frame)
        super.init()

        mrzView.delegate = self

        channel.setMethodCallHandler { [weak self] call, result in
            guard let self else {
                result(nil)
                return
            }

            switch call.method {
            case "start":
                if let myArgs = call.arguments as? [String: Any],
                   let isFrontCam = myArgs["isFrontCam"] as? Bool {
                    self.mrzView.startScanning(isFrontCam)
                }
                result(nil)
            case "stop":
                self.mrzView.stopScanning()
                result(nil)
            case "flashlightOn":
                self.toggleFlash(on: true)
                result(nil)
            case "flashlightOff":
                self.toggleFlash(on: false)
                result(nil)
            case "takePhoto":
                self.photoResult = result
                if let myArgs = call.arguments as? [String: Any],
                   let shouldCrop = myArgs["crop"] as? Bool {
                    self.mrzView.takePhoto(shouldCrop: shouldCrop)
                } else {
                    self.photoResult = nil
                    result(nil)
                }
            default:
                result(FlutterMethodNotImplemented)
            }
        }
    }

    func view() -> UIView {
        mrzView
    }

    func onParse(_ parsed: String?) {
        DispatchQueue.main.async { [weak self] in
            self?.channel.invokeMethod("onParsed", arguments: parsed)
        }
    }

    func onError(_ error: String?) {
        DispatchQueue.main.async { [weak self] in
            self?.channel.invokeMethod("onError", arguments: error)
        }
    }

    func onPhoto(_ data: Data?) {
        guard let photoResult else { return }
        self.photoResult = nil
        DispatchQueue.main.async {
            photoResult(data)
        }
    }

    private func toggleFlash(on: Bool) {
        guard let device = AVCaptureDevice.default(for: .video), device.hasTorch else {
            return
        }

        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }

            if on, device.torchMode == .off {
                try device.setTorchModeOn(level: 1.0)
            } else if !on, device.torchMode == .on {
                device.torchMode = .off
            }
        } catch {
            print(error)
        }
    }
}
