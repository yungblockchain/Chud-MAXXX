// swift-tools-version:5.9
// CHUD STREAMS for Mac. Builds with Xcode 15.2 or its Command Line Tools on macOS Ventura (13).
import PackageDescription

let package = Package(
    name: "ChudStreams",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "ChudStreams",
            path: "Sources/ChudStreams"
        )
    ]
)
