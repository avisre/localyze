// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "Localyze",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "Localyze", targets: ["Localyze"])
    ],
    dependencies: [
        .package(url: "https://github.com/ml-explore/mlx-swift.git", from: "0.18.0"),
        .package(url: "https://github.com/ml-explore/mlx-swift-examples.git", from: "1.16.0")
    ],
    targets: [
        .executableTarget(
            name: "Localyze",
            dependencies: [
                .product(name: "MLX", package: "mlx-swift"),
                .product(name: "MLXNN", package: "mlx-swift"),
                .product(name: "MLXLLM", package: "mlx-swift-examples"),
                .product(name: "MLXLMCommon", package: "mlx-swift-examples")
            ],
            path: "Sources/Localyze"
        )
    ]
)
