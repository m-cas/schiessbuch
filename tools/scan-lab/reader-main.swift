// Runs the iPhone app's printout reader (ios-native/Sources/Scanner/ScanReader.swift) on the sample photos
// on a Mac and writes the words in the scan-lab format, to compare the Swift port with the reference.
//   swiftc -O main.swift ScanReader.swift ScanLayoutAnalyzer.swift -o readercheck
//   ./readercheck reader.json reader.bin out.json photo1.jpg …
import Foundation
import CoreGraphics
import ImageIO

let a = CommandLine.arguments
guard let reader = PrintoutReader(meta: try Data(contentsOf: URL(fileURLWithPath: a[1])),
                                  bin: try Data(contentsOf: URL(fileURLWithPath: a[2]))) else { fatalError("model") }
var pages: [[String: Any]] = []
for path in a.dropFirst(4) {
    guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
          let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { continue }
    let w = img.width, h = img.height
    var rgba = [UInt8](repeating: 0, count: w * h * 4)
    let ctx = CGContext(data: &rgba, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
    var gray = [Float](repeating: 0, count: w * h)
    for i in 0..<(w * h) { gray[i] = 0.299 * Float(rgba[4 * i]) + 0.587 * Float(rgba[4 * i + 1]) + 0.114 * Float(rgba[4 * i + 2]) }
    let t0 = Date()
    let words = reader.read(gray: gray, width: w, height: h)
    let ms = Int(Date().timeIntervalSince(t0) * 1000)
    pages.append(["file": (path as NSString).lastPathComponent, "ms": ms,
                  "words": words.map { ["t": $0.text, "x": $0.box.minX, "y": $0.box.minY, "w": $0.box.width, "h": $0.box.height] }])
    print((path as NSString).lastPathComponent, words.count, "\(ms) ms")
}
try JSONSerialization.data(withJSONObject: pages).write(to: URL(fileURLWithPath: a[3]))
