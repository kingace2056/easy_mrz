// swift-tools-version: 5.9
// The swift-tools-version declares the minimum version of Swift required to build this package.
//
// Generated compatibility shim for the easy_mrz plugin package.

import PackageDescription

let package = Package(
    name: "easy_mrz",
    platforms: [
        .iOS("13.0")
    ],
    products: [
        .library(name: "easy-mrz", targets: ["easy_mrz"])
    ],
    dependencies: [
        .package(name: "FlutterFramework", path: "../FlutterFramework")
    ],
    targets: [
        .target(
            name: "easy_mrz",
            dependencies: [
                .product(name: "FlutterFramework", package: "FlutterFramework")
            ],
            path: "Sources/easy_mrz"
        )
    ]
)
