// swift-tools-version: 6.2
import PackageDescription

let package = Package(
  name: "FilePreviewKit",
  platforms: [.iOS(.v17), .macOS(.v13)],
  products: [.library(name: "FilePreviewKit", targets: ["FilePreviewKit"])],
  dependencies: [.package(url: "https://github.com/swiftlang/swift-markdown.git", exact: "0.8.0")],
  targets: [
    .target(name: "FilePreviewKit", dependencies: [.product(name: "Markdown", package: "swift-markdown")]),
    .testTarget(name: "FilePreviewKitTests", dependencies: ["FilePreviewKit"]),
  ],
  swiftLanguageModes: [.v5]
)
