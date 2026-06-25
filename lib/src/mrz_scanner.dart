import 'dart:async';

import 'package:easy_mrz/src/camera_overlay.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:mrz_parser/mrz_parser.dart';

/// MRZ scanner camera widget
class MRZScanner extends StatelessWidget {
  const MRZScanner({
    required this.onControllerCreated,
    this.withOverlay = false,
    Key? key,
  }) : super(key: key);

  /// Provides a controller for MRZ handling
  final void Function(MRZController controller) onControllerCreated;

  /// Displays MRZ scanner overlay
  final bool withOverlay;

  @override
  Widget build(BuildContext context) {
    final scanner = defaultTargetPlatform == TargetPlatform.iOS
        ? UiKitView(
            viewType: 'easy_mrz_scanner',
            onPlatformViewCreated: (int id) => onPlatformViewCreated(id),
            creationParamsCodec: const StandardMessageCodec(),
          )
        : defaultTargetPlatform == TargetPlatform.android
            ? AndroidView(
                viewType: 'easy_mrz_scanner',
                onPlatformViewCreated: (int id) => onPlatformViewCreated(id),
                creationParamsCodec: const StandardMessageCodec(),
              )
            : Text('$defaultTargetPlatform is not supported by this plugin');
    return withOverlay ? CameraOverlay(child: scanner) : scanner;
  }

  void onPlatformViewCreated(int id) {
    final controller = MRZController._init(id);
    onControllerCreated(controller);
  }
}

class MRZController {
  MRZController._init(int id) {
    _channel = MethodChannel('easy_mrz_scanner_$id');
    _channel.setMethodCallHandler(_platformCallHandler);
  }

  late final MethodChannel _channel;

  void Function(MRZResult mrz)? onParsed;

  void Function(String text)? onError;

  void flashlightOn() {
    _channel.invokeMethod<void>('flashlightOn');
  }

  void flashlightOff() {
    _channel.invokeMethod<void>('flashlightOff');
  }

  Future<List<int>?> takePhoto({
    bool crop = true,
  }) async {
    final result = await _channel.invokeMethod<List<int>>('takePhoto', {
      'crop': crop,
    });
    return result;
  }

  Future<void> _platformCallHandler(MethodCall call) {
    switch (call.method) {
      case 'onError':
        onError?.call(call.arguments);
        break;
      case 'onParsed':
        if (onParsed != null) {
          final recognizedText = call.arguments as String?;
          final result = _parseRecognizedText(recognizedText);
          if (result != null) {
            onParsed!(result);
          }
        }
        break;
    }
    return Future.value();
  }

  MRZResult? _parseRecognizedText(String? recognizedText) {
    if (recognizedText == null || recognizedText.trim().isEmpty) {
      return null;
    }

    final lines = _normalizedLines(recognizedText);
    if (lines.length < 2) {
      return null;
    }

    final candidates = <List<String>>[];
    for (final windowSize in [3, 2]) {
      if (lines.length < windowSize) {
        continue;
      }

      for (var start = 0; start <= lines.length - windowSize; start++) {
        candidates.add(lines.sublist(start, start + windowSize));
      }
    }

    candidates.sort((a, b) => _candidateScore(b).compareTo(_candidateScore(a)));

    final seen = <String>{};
    for (final candidate in candidates) {
      final key = candidate.join('\n');
      if (!seen.add(key)) {
        continue;
      }

      final result = MRZParser.tryParse(candidate);
      if (result != null) {
        return result;
      }
    }

    return null;
  }

  List<String> _normalizedLines(String recognizedText) {
    return recognizedText
        .replaceAll('\r', '\n')
        .toUpperCase()
        .split('\n')
        .map(_normalizeLine)
        .where((line) => line.isNotEmpty)
        .toList(growable: false);
  }

  String _normalizeLine(String value) {
    var line = value.trim();
    if (line.isEmpty) {
      return '';
    }

    const replacements = <String, String>{
      '«': '<',
      '‹': '<',
      '›': '<',
      '﹤': '<',
      '＜': '<',
      '|': '<',
      '¦': '<',
      '>': '<',
      ' ': '',
      '\t': '',
    };

    replacements.forEach((from, to) {
      line = line.replaceAll(from, to);
    });

    final buffer = StringBuffer();
    for (final rune in line.runes) {
      final code = rune;
      final isDigit = code >= 48 && code <= 57;
      final isUpperAlpha = code >= 65 && code <= 90;
      final isAngle = code == 60;
      if (isDigit || isUpperAlpha || isAngle) {
        buffer.writeCharCode(code);
      }
    }

    final normalized = buffer.toString();
    return normalized.length >= 5 ? normalized : '';
  }

  int _candidateScore(List<String> candidate) {
    return candidate.fold<int>(0, (score, line) => score + line.length);
  }

  void startPreview({bool isFrontCam = false}) => _channel.invokeMethod<void>(
        'start',
        {
          'isFrontCam': isFrontCam,
        },
      );

  void stopPreview() => _channel.invokeMethod<void>('stop');
}
