// swift-tools-version:5.9
import PackageDescription

// TippliCore: platform-agnostic logic shared by the iOS app, the keyboard
// extension and the macOS app. The shared JSON/TXT files from ../../shared are
// NOT SwiftPM resources (they live outside the package); the apps bundle them
// via XcodeGen and the tests read them from the repository checkout.
let package = Package(
    name: "TippliCore",
    platforms: [
        .iOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(name: "TippliCore", targets: ["TippliCore"])
    ],
    targets: [
        .target(
            name: "TippliCore",
            path: "Sources/TippliCore"
        ),
        .testTarget(
            name: "TippliCoreTests",
            dependencies: ["TippliCore"],
            path: "Tests/TippliCoreTests"
        )
    ]
)
