import AVFoundation
import CoreImage
import ImageIO
import UIKit
import Vision

public protocol MRZScannerViewDelegate: AnyObject {
    func onParse(_ parsed: String?)
    func onError(_ error: String?)
    func onPhoto(_ data: Data?)
}

public final class MRZScannerView: UIView {
    private let captureSession = AVCaptureSession()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let photoOutput = AVCapturePhotoOutput()
    private let videoPreviewLayer = AVCaptureVideoPreviewLayer()
    private let captureQueue = DispatchQueue(label: "easy_mrz.capture", qos: .userInitiated)
    private let imageContext = CIContext(options: nil)
    private var observer: NSKeyValueObservation?
    private var isConfigured = false
    private var configuredForFrontCamera = false
    private var isScanningPaused = false
    private var isAnalyzingFrame = false
    private var lastFrameAnalyzedAt: CFTimeInterval = 0
    private var lastEmittedText: String?
    private var shouldCrop = false
    private var isFrontCam = false
    private var didAddAppObservers = false

    @objc public dynamic var isScanning = false
    public weak var delegate: MRZScannerViewDelegate?

    private var interfaceOrientation: UIInterfaceOrientation {
        window?.windowScene?.interfaceOrientation ?? .portrait
    }

    override public init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
    }

    required public init?(coder aDecoder: NSCoder) {
        super.init(coder: aDecoder)
        backgroundColor = .black
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        observer = nil
    }

    override public func layoutSubviews() {
        super.layoutSubviews()
        adjustVideoPreviewLayerFrame()
    }

    override public func prepareForInterfaceBuilder() {
        setViewStyle()
    }

    public func startScanning(_ isFrontCam: Bool) {
        self.isFrontCam = isFrontCam

        if !isConfigured || configuredForFrontCamera != isFrontCam {
            configureCaptureSession()
        }

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            if !self.captureSession.isRunning {
                self.captureSession.startRunning()
            }
            DispatchQueue.main.async { [weak self] in
                self?.adjustVideoPreviewLayerFrame()
            }
        }
    }

    public func stopScanning() {
        captureSession.stopRunning()
        isScanningPaused = false
        lastEmittedText = nil
    }

    public func takePhoto(shouldCrop: Bool) {
        self.shouldCrop = shouldCrop
        photoOutput.capturePhoto(with: AVCapturePhotoSettings(), delegate: self)
    }

    private func setViewStyle() {
        backgroundColor = .black
    }

    private func configureCaptureSession() {
        if captureSession.isRunning {
            captureSession.stopRunning()
        }

        captureSession.beginConfiguration()
        defer { captureSession.commitConfiguration() }

        captureSession.inputs.forEach { captureSession.removeInput($0) }
        captureSession.outputs.forEach { captureSession.removeOutput($0) }
        observer?.invalidate()
        observer = nil

        captureSession.sessionPreset = .hd1920x1080

        guard let camera = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: isFrontCam ? .front : .back
        ) else {
            delegate?.onError("Camera not accessible")
            return
        }

        guard let deviceInput = try? AVCaptureDeviceInput(device: camera) else {
            delegate?.onError("Capture input could not be initialized")
            return
        }

        observer = captureSession.observe(\.isRunning, options: [.new]) { [weak self] _, change in
            DispatchQueue.main.async {
                self?.isScanning = change.newValue ?? false
            }
        }

        guard captureSession.canAddInput(deviceInput),
              captureSession.canAddOutput(videoOutput),
              captureSession.canAddOutput(photoOutput) else {
            delegate?.onError("Input & Output could not be added to the session")
            return
        }

        captureSession.addInput(deviceInput)

        videoOutput.setSampleBufferDelegate(self, queue: captureQueue)
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        captureSession.addOutput(videoOutput)
        captureSession.addOutput(photoOutput)

        videoOutput.connection(with: .video)?.videoOrientation = AVCaptureVideoOrientation(orientation: interfaceOrientation)
        photoOutput.connection(with: .video)?.videoOrientation = AVCaptureVideoOrientation(orientation: interfaceOrientation)

        videoPreviewLayer.session = captureSession
        videoPreviewLayer.videoGravity = .resizeAspectFill

        if videoPreviewLayer.superlayer == nil {
            layer.insertSublayer(videoPreviewLayer, at: 0)
        }

        configuredForFrontCamera = isFrontCam
        isConfigured = true
        addAppObservers()
        adjustVideoPreviewLayerFrame()
    }

    private func addAppObservers() {
        guard !didAddAppObservers else {
            return
        }

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
        didAddAppObservers = true
    }

    @objc private func appWillEnterForeground() {
        if isScanningPaused {
            isScanningPaused = false
            startScanning(isFrontCam)
        }
    }

    @objc private func appDidEnterBackground() {
        if isScanning {
            isScanningPaused = true
            stopScanning()
        }
    }

    private func adjustVideoPreviewLayerFrame() {
        let orientation = AVCaptureVideoOrientation(orientation: interfaceOrientation)
        videoOutput.connection(with: .video)?.videoOrientation = orientation
        photoOutput.connection(with: .video)?.videoOrientation = orientation
        videoPreviewLayer.connection?.videoOrientation = orientation
        videoPreviewLayer.frame = bounds
    }

    private func recognizedText(from observations: [VNRecognizedTextObservation]) -> String? {
        let lines = observations
            .sorted { $0.boundingBox.midY > $1.boundingBox.midY }
            .compactMap { observation -> String? in
                guard let candidate = observation.topCandidates(1).first else { return nil }
                return normalizedMRZLine(candidate.string)
            }
            .filter { !$0.isEmpty }

        guard lines.count >= 2 else { return nil }
        return lines.joined(separator: "\n")
    }

    private func normalizedMRZLine(_ value: String) -> String {
        let replacements: [String: String] = [
            "«": "<",
            "‹": "<",
            "›": "<",
            "﹤": "<",
            "＜": "<",
            "|": "<",
            "¦": "<",
            ">": "<",
            " ": "",
            "\t": "",
        ]

        var normalized = value.uppercased()
        for (from, to) in replacements {
            normalized = normalized.replacingOccurrences(of: from, with: to)
        }

        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<")
        let scalars = normalized.unicodeScalars.filter { allowed.contains($0) }
        return String(String.UnicodeScalarView(scalars))
    }

    private func mrz(from cgImage: CGImage) -> String? {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .fast
        request.usesLanguageCorrection = false
        request.minimumTextHeight = 0.03
        request.recognitionLanguages = ["en-US"]
        request.regionOfInterest = CGRect(x: 0.0, y: 0.0, width: 1.0, height: 0.4)
        request.preferBackgroundProcessing = true

        do {
            let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
            try handler.perform([request])
        } catch {
            delegate?.onError("Vision OCR failed: \(error.localizedDescription)")
            return nil
        }

        guard let observations = request.results as? [VNRecognizedTextObservation] else {
            return nil
        }

        return recognizedText(from: observations)
    }

    private func cutoutRect(for cgImage: CGImage) -> CGRect {
        guard videoPreviewLayer.connection != nil else {
            return CGRect(
                x: 0,
                y: 0,
                width: CGFloat(cgImage.width),
                height: CGFloat(cgImage.height)
            )
        }

        let imageWidth = CGFloat(cgImage.width)
        let imageHeight = CGFloat(cgImage.height)
        let rect = videoPreviewLayer.metadataOutputRectConverted(fromLayerRect: calculateCutoutRect())
        let videoOrientation = videoPreviewLayer.connection?.videoOrientation ?? .portrait

        if videoOrientation == .portrait || videoOrientation == .portraitUpsideDown {
            return CGRect(
                x: (rect.minY * imageWidth),
                y: (rect.minX * imageHeight),
                width: (rect.height * imageWidth),
                height: (rect.width * imageHeight)
            )
        } else {
            return CGRect(
                x: (rect.minX * imageWidth),
                y: (rect.minY * imageHeight),
                width: (rect.width * imageWidth),
                height: (rect.height * imageHeight)
            )
        }
    }

    private func documentImage(from cgImage: CGImage) -> CGImage {
        let croppingRect = cutoutRect(for: cgImage).intersection(
            CGRect(x: 0, y: 0, width: CGFloat(cgImage.width), height: CGFloat(cgImage.height))
        )

        guard !croppingRect.isNull, croppingRect.width > 0, croppingRect.height > 0 else {
            return cgImage
        }

        return cgImage.cropping(to: croppingRect) ?? cgImage
    }

    private func calculateCutoutRect() -> CGRect {
        let documentFrameRatio = CGFloat(1.42)
        let (width, height): (CGFloat, CGFloat)

        if bounds.height > bounds.width {
            width = (bounds.width * 0.9)
            height = (width / documentFrameRatio)
        } else {
            height = (bounds.height * 0.75)
            width = (height * documentFrameRatio)
        }

        let topOffset = (bounds.height - height) / 2
        let leftOffset = (bounds.width - width) / 2

        return CGRect(x: leftOffset, y: topOffset, width: width, height: height)
    }
}

extension MRZScannerView: AVCaptureVideoDataOutputSampleBufferDelegate {
    public func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = CACurrentMediaTime()
        guard !isAnalyzingFrame, now - lastFrameAnalyzedAt > 0.20 else {
            return
        }

        isAnalyzingFrame = true
        lastFrameAnalyzedAt = now
        defer { isAnalyzingFrame = false }

        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            return
        }

        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        guard let cgImage = imageContext.createCGImage(ciImage, from: ciImage.extent) else {
            return
        }

        let documentImage = documentImage(from: cgImage)
        guard let recognizedText = mrz(from: documentImage), !recognizedText.isEmpty else {
            return
        }

        guard recognizedText != lastEmittedText else {
            return
        }

        lastEmittedText = recognizedText
        DispatchQueue.main.async { [weak self] in
            self?.delegate?.onParse(recognizedText)
        }
    }
}

extension MRZScannerView: AVCapturePhotoCaptureDelegate {
    public func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error {
            delegate?.onError("Error capturing photo: \(error.localizedDescription)")
            return
        }

        guard let cgImage = photo.cgImageRepresentation() else {
            delegate?.onError("Captured photo could not be converted")
            return
        }

        let rotated = createMatchingBackingDataWithImage(imageRef: cgImage, orienation: .left)
        let resized = rotated.flatMap { self.resize($0) }
        let image = resized ?? rotated ?? cgImage

        if shouldCrop {
            let document = documentImage(from: image)
            delegate?.onPhoto(document.png)
        } else {
            delegate?.onPhoto(image.png)
        }
    }

    private func createMatchingBackingDataWithImage(imageRef: CGImage?, orienation: UIImage.Orientation) -> CGImage? {
        guard let imageRef else {
            return nil
        }

        let originalWidth = imageRef.width
        let originalHeight = imageRef.height
        let bitsPerComponent = imageRef.bitsPerComponent
        let bytesPerRow = imageRef.bytesPerRow
        let bitmapInfo = imageRef.bitmapInfo

        guard let colorSpace = imageRef.colorSpace else {
            return nil
        }

        let (degreesToRotate, swapWidthHeight, mirrored): (Double, Bool, Bool)
        switch orienation {
        case .up:
            (degreesToRotate, swapWidthHeight, mirrored) = (0.0, false, false)
        case .upMirrored:
            (degreesToRotate, swapWidthHeight, mirrored) = (0.0, false, true)
        case .right:
            (degreesToRotate, swapWidthHeight, mirrored) = (90.0, true, false)
        case .rightMirrored:
            (degreesToRotate, swapWidthHeight, mirrored) = (90.0, true, true)
        case .down:
            (degreesToRotate, swapWidthHeight, mirrored) = (180.0, false, false)
        case .downMirrored:
            (degreesToRotate, swapWidthHeight, mirrored) = (180.0, false, true)
        case .left:
            (degreesToRotate, swapWidthHeight, mirrored) = (-90.0, true, false)
        case .leftMirrored:
            (degreesToRotate, swapWidthHeight, mirrored) = (-90.0, true, true)
        @unknown default:
            (degreesToRotate, swapWidthHeight, mirrored) = (0.0, false, false)
        }

        let radians = degreesToRotate * Double.pi / 180.0
        let width = swapWidthHeight ? originalHeight : originalWidth
        let height = swapWidthHeight ? originalWidth : originalHeight

        let contextRef = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: bitsPerComponent,
            bytesPerRow: bytesPerRow,
            space: colorSpace,
            bitmapInfo: bitmapInfo.rawValue
        )

        contextRef?.translateBy(x: CGFloat(width) / 2.0, y: CGFloat(height) / 2.0)
        if mirrored {
            contextRef?.scaleBy(x: -1.0, y: 1.0)
        }
        contextRef?.rotate(by: CGFloat(radians))
        if swapWidthHeight {
            contextRef?.translateBy(x: -CGFloat(height) / 2.0, y: -CGFloat(width) / 2.0)
        } else {
            contextRef?.translateBy(x: -CGFloat(width) / 2.0, y: -CGFloat(height) / 2.0)
        }
        contextRef?.draw(
            imageRef,
            in: CGRect(
                x: 0.0,
                y: 0.0,
                width: CGFloat(originalWidth),
                height: CGFloat(originalHeight)
            )
        )
        return contextRef?.makeImage()
    }

    private func resize(_ image: CGImage) -> CGImage? {
        var ratio: Float = 0.0
        let imageWidth = Float(image.width)
        let imageHeight = Float(image.height)
        let maxWidth: Float = 720.0
        let maxHeight: Float = 1280.0

        if imageWidth > imageHeight {
            ratio = maxWidth / imageWidth
        } else {
            ratio = maxHeight / imageHeight
        }

        if ratio > 1 {
            ratio = 1
        }

        let width = imageWidth * ratio
        let height = imageHeight * ratio

        guard let colorSpace = image.colorSpace else { return nil }
        guard let context = CGContext(
            data: nil,
            width: Int(width),
            height: Int(height),
            bitsPerComponent: image.bitsPerComponent,
            bytesPerRow: image.bytesPerRow,
            space: colorSpace,
            bitmapInfo: image.bitmapInfo.rawValue
        ) else {
            return nil
        }

        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: Int(width), height: Int(height)))
        return context.makeImage()
    }
}

extension AVCaptureVideoOrientation {
    internal init(orientation: UIInterfaceOrientation) {
        switch orientation {
        case .portrait:
            self = .portrait
        case .portraitUpsideDown:
            self = .portraitUpsideDown
        case .landscapeLeft:
            self = .landscapeLeft
        case .landscapeRight:
            self = .landscapeRight
        default:
            self = .portrait
        }
    }
}

extension CGImage {
    var png: Data? {
        guard let mutableData = CFDataCreateMutable(nil, 0),
              let destination = CGImageDestinationCreateWithData(mutableData, "public.png" as CFString, 1, nil) else {
            return nil
        }
        CGImageDestinationAddImage(destination, self, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return mutableData as Data
    }
}
