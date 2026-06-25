#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint easy_mrz.podspec' to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'easy_mrz'
  s.version          = '1.0.0'
  s.summary          = 'Fast MRZ scanning from identity documents.'
  s.description      = <<-DESC
Fast MRZ (Machine Readable Zone) scanning from identity documents for iOS and Android.
                       DESC
  s.homepage         = 'https://github.com/kingace2056/easy_mrz'
  s.license          = { :file => '../LICENSE' }
  s.author           = 'Sarthak Parajuli'
  s.source           = { :path => '.' }
  s.source_files = 'flutter_mrz_scanner/Sources/easy_mrz/**/*'
  s.dependency 'Flutter'
  s.platform = :ios, '13.0'
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
  s.swift_version = '5.9'
end
