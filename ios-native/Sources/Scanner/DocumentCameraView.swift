// Taken from the ShootingResultScanner package (reads photos of range result strips).
// Types renamed (Shot → ScanShot, Series → ScanSeries) to avoid clashing with the app model.
#if canImport(VisionKit) && os(iOS)
import SwiftUI
import VisionKit

/// SwiftUI wrapper around VisionKit's document camera (auto edge detection, cropping, multi-page).
/// Requires `NSCameraUsageDescription` in the app's Info.plist.
public struct DocumentCameraView: UIViewControllerRepresentable {
    public static var isSupported: Bool { VNDocumentCameraViewController.isSupported }

    private let onFinish: ([UIImage]) -> Void
    private let onCancel: () -> Void

    public init(onFinish: @escaping ([UIImage]) -> Void, onCancel: @escaping () -> Void = {}) {
        self.onFinish = onFinish
        self.onCancel = onCancel
    }

    public func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let controller = VNDocumentCameraViewController()
        controller.delegate = context.coordinator
        return controller
    }

    public func updateUIViewController(_ controller: VNDocumentCameraViewController, context: Context) {}

    public func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish, onCancel: onCancel) }

    public final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        let onFinish: ([UIImage]) -> Void
        let onCancel: () -> Void

        init(onFinish: @escaping ([UIImage]) -> Void, onCancel: @escaping () -> Void) {
            self.onFinish = onFinish
            self.onCancel = onCancel
        }

        public func documentCameraViewController(_ controller: VNDocumentCameraViewController,
                                                 didFinishWith scan: VNDocumentCameraScan) {
            onFinish((0..<scan.pageCount).map { scan.imageOfPage(at: $0) })
        }

        public func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
            onCancel()
        }

        public func documentCameraViewController(_ controller: VNDocumentCameraViewController,
                                                 didFailWithError error: Error) {
            onCancel()
        }
    }
}
#endif
