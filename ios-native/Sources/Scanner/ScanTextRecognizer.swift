// Taken from the ShootingResultScanner package (reads photos of range result strips).
// Types renamed (Shot → ScanShot, Series → ScanSeries) to avoid clashing with the app model.
#if canImport(Vision)
import CoreGraphics
import Foundation
import Vision

/// Runs Vision OCR and returns both whole lines and single words with their positions.
/// Word positions let `LayoutAnalyzer` separate copies printed side by side.
struct TextRecognizer {
    var minimumConfidence: Float
    var languages: [String]

    struct Output {
        var words: [TextFragment] = []
        var lines: [TextFragment] = []
    }

    func recognize(_ image: CGImage, orientation: CGImagePropertyOrientation = .up) throws -> Output {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false     // would turn digits into words
        request.recognitionLanguages = languages
        request.minimumTextHeight = 0.004           // a strip lying in a large photo has tiny digits

        let handler = VNImageRequestHandler(cgImage: image, orientation: orientation, options: [:])
        try handler.perform([request])

        var out = Output()
        for observation in request.results ?? [] {
            guard let candidate = observation.topCandidates(1).first,
                  candidate.confidence >= minimumConfidence else { continue }
            out.lines.append(TextFragment(text: candidate.string, box: observation.boundingBox,
                                          confidence: candidate.confidence))
            out.words += Self.words(of: candidate, lineBox: observation.boundingBox)
        }
        return out
    }

    private static func words(of candidate: VNRecognizedText, lineBox: CGRect) -> [TextFragment] {
        let text = candidate.string
        let length = (text as NSString).length
        guard length > 0, let regex = try? NSRegularExpression(pattern: #"\S+"#) else { return [] }
        let matches = regex.matches(in: text, range: NSRange(location: 0, length: length))

        return matches.compactMap { match in
            guard let range = Range(match.range, in: text) else { return nil }
            var box = (try? candidate.boundingBox(for: range))?.boundingBox
            // Some revisions return the whole line for every word; fall back to the character position.
            if let b = box, matches.count > 1, b.width >= lineBox.width * 0.95 { box = nil }
            let x = lineBox.minX + lineBox.width * CGFloat(match.range.location) / CGFloat(length)
            let w = lineBox.width * CGFloat(match.range.length) / CGFloat(length)
            return TextFragment(text: String(text[range]),
                                box: box ?? CGRect(x: x, y: lineBox.minY, width: w, height: lineBox.height),
                                confidence: candidate.confidence)
        }
    }
}
#endif
