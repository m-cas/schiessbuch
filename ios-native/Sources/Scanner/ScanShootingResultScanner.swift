// Taken from the ShootingResultScanner package (reads photos of range result strips).
// Types renamed (Shot → ScanShot, Series → ScanSeries) to avoid clashing with the app model.
#if canImport(UIKit) && canImport(Vision)
import UIKit
import Vision

public enum ShootingResultScannerError: Error, LocalizedError {
    case invalidImage

    public var errorDescription: String? {
        switch self {
        case .invalidImage: return "The image could not be read."
        }
    }
}

/// Entry point: photo(s) of printed result strips in, one `ShootingResult` per printout out.
///
/// ```swift
/// let results = try await ShootingResultScanner().scan(photo)
/// for r in results {
///     print(r.targetLabel ?? "-", r.total, r.series.map(\.total), r.isVerified)
/// }
/// ```
/// A photo with two Pg strips (each printed twice) gives two results; the two copies of a
/// strip are merged into one result with `copiesRead == 2`.
public struct ShootingResultScanner: Sendable {
    public var configuration: ScannerConfiguration

    public init(configuration: ScannerConfiguration = ScannerConfiguration()) {
        self.configuration = configuration
    }

    public func scan(_ image: UIImage) async throws -> [ShootingResult] {
        try await scan(pages: [image])
    }

    /// Several photos / document-camera pages as one batch (copies across pages are merged too).
    public func scan(pages: [UIImage]) async throws -> [ShootingResult] {
        let inputs = try pages.map { page -> (CGImage, CGImagePropertyOrientation) in
            if let cg = page.cgImage { return (cg, CGImagePropertyOrientation(page.imageOrientation)) }
            guard let cg = Self.render(page) else { throw ShootingResultScannerError.invalidImage }
            return (cg, .up)                                   // rendering bakes the orientation in
        }
        let config = configuration
        return try await Task.detached(priority: .userInitiated) {
            var all: [ShootingResult] = []
            for (cg, orientation) in inputs {
                all += try Self.process(cg, orientation: orientation, config: config)
            }
            return ResultAssembler.mergeCopies(all)
        }.value
    }

    public func scan(cgImage: CGImage, orientation: CGImagePropertyOrientation = .up) async throws -> [ShootingResult] {
        let config = configuration
        return try await Task.detached(priority: .userInitiated) {
            ResultAssembler.mergeCopies(try Self.process(cgImage, orientation: orientation, config: config))
        }.value
    }

    /// Re-parses (possibly hand-corrected) text without running OCR again.
    public func parse(lines: [String]) -> [ShootingResult] {
        ResultAssembler.mergeCopies(ResultParser(configuration: configuration).parseAll(lines: lines))
    }

    // MARK: - Pipeline

    private static func process(_ cgImage: CGImage, orientation: CGImagePropertyOrientation,
                                config: ScannerConfiguration) throws -> [ShootingResult] {
        let prepared = ImagePreprocessor().prepare(cgImage, orientation: orientation,
                                                   minimumPageArea: config.minimumPageAreaForCrop)
        let recognizer = TextRecognizer(minimumConfidence: config.minimumOCRConfidence,
                                        languages: config.recognitionLanguages)
        let parser = ResultParser(configuration: config)

        // the trained printout reader first (made for dot-matrix strips), Apple Vision when it finds nothing
        var results: [ShootingResult] = []
        if let reader = PrintoutReader.shared {
            let (gray, w, h) = PrintoutReader.gray(ImagePreprocessor().upright(cgImage, orientation: orientation))
            results = LayoutAnalyzer.columns(from: reader.read(gray: gray, width: w, height: h)).flatMap { parser.parseAll(lines: $0) }
        }
        if results.isEmpty {
            let upright = try recognizer.recognize(prepared)
            results = LayoutAnalyzer.columns(from: upright.words).flatMap { parser.parseAll(lines: $0) }
        }

        // SIUS-style strips print name, club and weapon sideways. Pg strips have no header.
        let needsHeader = results.contains { result in
            guard let program = result.program else { return true }
            return !program.allSatisfy(\.isNumber)
        }
        if config.readSidewaysHeader, needsHeader {
            let headers = try [CGImagePropertyOrientation.right, .left].map { o in
                HeaderParser.parse(lines: LayoutAnalyzer.readingOrder(try recognizer.recognize(prepared, orientation: o).lines))
            }
            if let best = headers.max(by: { $0.score < $1.score }), best.score > 0 {
                ResultAssembler.apply(best, to: &results)
            }
        }
        return results
    }

    private static func render(_ image: UIImage) -> CGImage? {
        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        return UIGraphicsImageRenderer(size: image.size, format: format)
            .image { _ in image.draw(at: .zero) }
            .cgImage
    }
}

extension CGImagePropertyOrientation {
    init(_ orientation: UIImage.Orientation) {
        switch orientation {
        case .up: self = .up
        case .down: self = .down
        case .left: self = .left
        case .right: self = .right
        case .upMirrored: self = .upMirrored
        case .downMirrored: self = .downMirrored
        case .leftMirrored: self = .leftMirrored
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}
#endif
