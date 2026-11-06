// Taken from the ShootingResultScanner package (reads photos of range result strips).
// Types renamed (Shot → ScanShot, Series → ScanSeries) to avoid clashing with the app model.
#if canImport(Vision) && canImport(CoreImage)
import CoreImage
import ImageIO
import Vision

/// Grayscale + contrast for faint thermal / dot-matrix print, and perspective correction
/// when one sheet fills most of the photo.
struct ImagePreprocessor {
    private let context = CIContext(options: [.useSoftwareRenderer: false])

    func prepare(_ cgImage: CGImage,
                 orientation: CGImagePropertyOrientation,
                 minimumPageArea: Double) -> CGImage {
        var image = CIImage(cgImage: cgImage).oriented(orientation)

        // Only crop when the detected page is large. Two strips lying side by side would otherwise
        // be cropped to one of them, and a narrow strip is read fine without cropping.
        if minimumPageArea < 1, let page = detectPage(in: image),
           Double(page.boundingBox.width * page.boundingBox.height) >= minimumPageArea {
            let e = image.extent
            func point(_ p: CGPoint) -> CIVector {
                CIVector(x: e.minX + p.x * e.width, y: e.minY + p.y * e.height)
            }
            image = image.applyingFilter("CIPerspectiveCorrection", parameters: [
                "inputTopLeft": point(page.topLeft),
                "inputTopRight": point(page.topRight),
                "inputBottomLeft": point(page.bottomLeft),
                "inputBottomRight": point(page.bottomRight)
            ])
        }

        image = image.applyingFilter("CIColorControls", parameters: [
            kCIInputSaturationKey: 0.0,      // blue / yellow / grey strips → neutral
            kCIInputContrastKey: 1.35
        ])
        return context.createCGImage(image, from: image.extent) ?? cgImage
    }

    private func detectPage(in image: CIImage) -> VNRectangleObservation? {
        let request = VNDetectDocumentSegmentationRequest()
        let handler = VNImageRequestHandler(ciImage: image, options: [:])
        try? handler.perform([request])
        guard let page = request.results?.first, page.confidence > 0.6 else { return nil }
        return page
    }
}
#endif
