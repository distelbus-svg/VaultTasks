// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "VaultTasksCore",
    platforms: [.macOS("27.0")],
    products: [.library(name: "VaultTasksCore", targets: ["VaultTasksCore"])],
    targets: [
        .target(name: "VaultTasksCore"),
        .testTarget(
            name: "VaultTasksCoreTests",
            dependencies: ["VaultTasksCore"],
            resources: [.copy("Resources/parser-fixtures.json")]
        ),
    ]
)
