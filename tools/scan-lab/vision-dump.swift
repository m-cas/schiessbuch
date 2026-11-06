// Runs Apple Vision text recognition on the sample photos the same way the iPhone app does
// (ios-native/Sources/Scanner/ScanTextRecognizer.swift) and prints the words with their boxes as JSON.
// Used by .github/workflows/scan-lab.yml on a macOS runner:  swift vision-dump.swift out.json img1.jpg img2.jpg …
import Foundation
import Vision
import CoreGraphics
import ImageIO

struct W: Codable { let t: String; let x, y, w, h: Double; let c: Float }
struct Page: Codable { let file: String; let words: [W]; let lines: [W] }

func recognize(_ img: CGImage) throws -> ([W], [W]) {
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.usesLanguageCorrection = false
    request.recognitionLanguages = ["de-DE", "fr-FR", "en-US"]
    request.minimumTextHeight = 0.004
    try VNImageRequestHandler(cgImage: img, orientation: .up, options: [:]).perform([request])
    var words: [W] = [], lines: [W] = []
    for o in request.results ?? [] {
        guard let cand = o.topCandidates(1).first else { continue }
        let lb = o.boundingBox
        lines.append(W(t: cand.string, x: lb.minX, y: lb.minY, w: lb.width, h: lb.height, c: cand.confidence))
        let text = cand.string, len = (text as NSString).length
        let re = try NSRegularExpression(pattern: #"\S+"#)
        let ms = re.matches(in: text, range: NSRange(location: 0, length: len))
        for m in ms {
            guard let r = Range(m.range, in: text) else { continue }
            var box = (try? cand.boundingBox(for: r))?.boundingBox
            if let b = box, ms.count > 1, b.width >= lb.width * 0.95 { box = nil }
            let x = lb.minX + lb.width * CGFloat(m.range.location) / CGFloat(len)
            let w = lb.width * CGFloat(m.range.length) / CGFloat(len)
            let bb = box ?? CGRect(x: x, y: lb.minY, width: w, height: lb.height)
            words.append(W(t: String(text[r]), x: bb.minX, y: bb.minY, w: bb.width, h: bb.height, c: cand.confidence))
        }
    }
    return (words, lines)
}

let args = CommandLine.arguments.dropFirst()
let out = args.first!
var pages: [Page] = []
for path in args.dropFirst() {
    guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
          let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { continue }
    let (w, l) = try recognize(img)
    pages.append(Page(file: (path as NSString).lastPathComponent, words: w, lines: l))
}
let enc = JSONEncoder(); enc.outputFormatting = [.prettyPrinted, .sortedKeys]
try enc.encode(pages).write(to: URL(fileURLWithPath: out))
print("wrote \(pages.count) pages")
