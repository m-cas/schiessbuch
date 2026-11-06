// Printout reader: two small trained networks find and read the words on a photo of a range result strip
// (tools/reader in the repository). Same steps as tools/reader/infer.py and reader.js (web).
// The model is Resources/reader.json + Resources/reader.bin.
import Foundation
import CoreGraphics

final class PrintoutReader: @unchecked Sendable {
    private struct Layer {
        var pool: Bool; var cin: Int; var cout: Int; var kh: Int; var kw: Int
        var dh: Int; var dw: Int; var ph: Int; var pw: Int; var relu: Bool; var offset: Int
    }
    private struct Tensor { var x: [Float]; var c: Int; var h: Int; var w: Int }

    private let charset: [Character]
    private let det: [Layer], rec: [Layer]
    private let detLong: Int, cell: Int, boxUnit: Float, recH: Int, recMaxW: Int
    private let weights: [Float]

    static let shared: PrintoutReader? = {
        guard let j = Bundle.main.url(forResource: "reader", withExtension: "json"),
              let b = Bundle.main.url(forResource: "reader", withExtension: "bin"),
              let jd = try? Data(contentsOf: j), let bd = try? Data(contentsOf: b) else { return nil }
        return PrintoutReader(meta: jd, bin: bd)
    }()

    init?(meta: Data, bin: Data) {
        guard let m = try? JSONSerialization.jsonObject(with: meta) as? [String: Any],
              let cs = m["charset"] as? String, let d = m["det"] as? [[String: Any]], let r = m["rec"] as? [[String: Any]] else { return nil }
        func layers(_ a: [[String: Any]]) -> [Layer] {
            a.map { o in
                let i = { (k: String) in (o[k] as? Int) ?? 0 }
                return Layer(pool: (o["op"] as? String) == "pool", cin: i("cin"), cout: i("cout"), kh: i("kh"), kw: i("kw"),
                             dh: max(1, i("dh")), dw: max(1, i("dw")), ph: i("ph"), pw: i("pw"), relu: (o["relu"] as? Bool) ?? false, offset: i("offset"))
            }
        }
        charset = Array(cs); det = layers(d); rec = layers(r)
        detLong = (m["detLong"] as? Int) ?? 1024; cell = (m["cell"] as? Int) ?? 4
        boxUnit = Float((m["boxUnit"] as? Double) ?? 32); recH = (m["recHeight"] as? Int) ?? 32; recMaxW = (m["recMaxWidth"] as? Int) ?? 512
        let n = bin.count / 2
        var w = [Float](repeating: 0, count: n)
        bin.withUnsafeBytes { raw in
            let p = raw.bindMemory(to: UInt16.self)
            for i in 0..<n { w[i] = Self.half(UInt16(littleEndian: p[i])) }
        }
        weights = w
    }

    private static func half(_ h: UInt16) -> Float {
        let s: Float = (h & 0x8000) != 0 ? -1 : 1, e = Int((h >> 10) & 31), m = Float(h & 1023)
        if e == 0 { return s * m * 5.9604645e-8 }
        if e == 31 { return m != 0 ? .nan : s * .infinity }
        return s * (1 + m / 1024) * powf(2, Float(e - 15))
    }

    // MARK: network

    private func run(_ layers: [Layer], _ input: Tensor) -> Tensor {
        var t = input
        var li = 0
        while li < layers.count {
            let L = layers[li]
            if L.pool { t = pool(t, L.kh, L.kw); li += 1; continue }
            let fuse: Layer? = li + 1 < layers.count && layers[li + 1].pool ? layers[li + 1] : nil
            let h = t.h, w = t.w, c = t.c
            let oh = h + 2 * L.ph - L.dh * (L.kh - 1), ow = w + 2 * L.pw - L.dw * (L.kw - 1)
            let ph2 = fuse.map { oh / $0.kh } ?? oh, pw2 = fuse.map { ow / $0.kw } ?? ow
            var out = [Float](repeating: 0, count: L.cout * ph2 * pw2)
            var plane = [Float](repeating: 0, count: oh * ow)
            let n = L.cout * L.cin * L.kh * L.kw
            weights.withUnsafeBufferPointer { W in
            t.x.withUnsafeBufferPointer { X in
            plane.withUnsafeMutableBufferPointer { P in
            out.withUnsafeMutableBufferPointer { O in
                for co in 0..<L.cout {
                    let bias = W[L.offset + n + co]
                    for k in 0..<(oh * ow) { P[k] = bias }
                    for ci in 0..<c {
                        let src = ci * h * w
                        for i in 0..<L.kh {
                            let dy = i * L.dh - L.ph, y0 = max(0, -dy), y1 = min(oh, h - dy)
                            if y0 >= y1 { continue }
                            for j in 0..<L.kw {
                                let wt = W[L.offset + ((co * c + ci) * L.kh + i) * L.kw + j]
                                if wt == 0 { continue }
                                let dx = j * L.dw - L.pw, x0 = max(0, -dx), x1 = min(ow, w - dx)
                                if x0 >= x1 { continue }
                                for y in y0..<y1 {
                                    let o = y * ow, s = src + (y + dy) * w + dx
                                    for xx in x0..<x1 { P[o + xx] += wt * X[s + xx] }
                                }
                            }
                        }
                    }
                    if L.relu { for k in 0..<(oh * ow) where P[k] < 0 { P[k] = 0 } }
                    let dst = co * ph2 * pw2
                    if let f = fuse {
                        for y in 0..<ph2 { for xx in 0..<pw2 {
                            var m = -Float.infinity
                            for a in 0..<f.kh { for b in 0..<f.kw { m = max(m, P[(y * f.kh + a) * ow + xx * f.kw + b]) } }
                            O[dst + y * pw2 + xx] = m
                        } }
                    } else {
                        for k in 0..<(oh * ow) { O[dst + k] = P[k] }
                    }
                }
            } } } }
            t = Tensor(x: out, c: L.cout, h: ph2, w: pw2)
            li += fuse == nil ? 1 : 2
        }
        return t
    }

    private func pool(_ t: Tensor, _ kh: Int, _ kw: Int) -> Tensor {
        let oh = t.h / kh, ow = t.w / kw
        var out = [Float](repeating: 0, count: t.c * oh * ow)
        for k in 0..<t.c { for y in 0..<oh { for xx in 0..<ow {
            var m = -Float.infinity
            for a in 0..<kh { for b in 0..<kw { m = max(m, t.x[k * t.h * t.w + (y * kh + a) * t.w + xx * kw + b]) } }
            out[(k * oh + y) * ow + xx] = m
        } } }
        return Tensor(x: out, c: t.c, h: oh, w: ow)
    }

    // MARK: image steps

    /// Area resampling when shrinking (like OpenCV INTER_AREA), bilinear when enlarging.
    private func resize(_ src: [Float], _ w: Int, _ h: Int, _ nw: Int, _ nh: Int) -> [Float] {
        var tmp = [Float](repeating: 0, count: nw * h), out = [Float](repeating: 0, count: nw * nh)
        axis(src, &tmp, h, w, nw, w, 1, nw, 1)
        axis(tmp, &out, nw, h, nh, 1, nw, 1, nw)
        return out
    }

    private func axis(_ a: [Float], _ b: inout [Float], _ lines: Int, _ n: Int, _ m: Int, _ lineA: Int, _ stepA: Int, _ lineB: Int, _ stepB: Int) {
        let sc = Double(n) / Double(m)
        for l in 0..<lines { for i in 0..<m {
            var v = 0.0
            if sc > 1 {
                let f0 = Double(i) * sc, f1 = f0 + sc
                var k = Int(f0.rounded(.down))
                while Double(k) < f1 && k < n { v += Double(a[l * lineA + k * stepA]) * (min(f1, Double(k + 1)) - max(f0, Double(k))); k += 1 }
                v /= sc
            } else {
                let f = max(0, (Double(i) + 0.5) * sc - 0.5)
                let k = min(n - 1, Int(f)), k2 = min(n - 1, k + 1), t = f - Double(k)
                v = Double(a[l * lineA + k * stepA]) * (1 - t) + Double(a[l * lineA + k2 * stepA]) * t
            }
            b[l * lineB + i * stepB] = Float(v)
        } }
    }

    private func blur(_ g: [Float], _ w: Int, _ s: Float, _ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int) -> [Float] {
        let r = max(1, Int((s * 4).rounded()))
        var k = (0...(2 * r)).map { expf(-Float(($0 - r) * ($0 - r)) / (2 * s * s)) }
        let sum = k.reduce(0, +); k = k.map { $0 / sum }
        let rw = x1 - x0, rh = y1 - y0
        var t = [Float](repeating: 0, count: rw * rh), o = t
        for y in 0..<rh { for x in 0..<rw {
            var v: Float = 0
            for i in -r...r { v += k[i + r] * g[(y0 + y) * w + min(w - 1, max(0, x0 + x + i))] }
            t[y * rw + x] = v
        } }
        for y in 0..<rh { for x in 0..<rw {
            var v: Float = 0
            for i in -r...r { v += k[i + r] * t[min(rh - 1, max(0, y + i)) * rw + x] }
            o[y * rw + x] = v
        } }
        return o
    }

    private func percentile(_ a: [Float], _ q: Double) -> Float {
        let s = a.sorted(), p = q / 100 * Double(s.count - 1), i = Int(p)
        return i + 1 < s.count ? s[i] + (s[i + 1] - s[i]) * Float(p - Double(i)) : s[i]
    }

    private func crop(_ g: [Float], _ w: Int, _ h: Int, _ x0: Float, _ y0: Float, _ x1: Float, _ y1: Float) -> Tensor {
        let bh = y1 - y0
        let X0 = x0 - 0.3 * bh, X1 = x1 + 0.3 * bh, Y0 = y0 - 0.2 * bh, Y1 = y1 + 0.2 * bh
        let ch = Y1 - Y0, cw = X1 - X0
        var ow = Int(min(Float(recMaxW), max(8, (cw * Float(recH) / ch).rounded())))
        ow = (ow + 3) / 4 * 4
        var src = g, sw = w, sh = h, ox = 0, oy = 0
        if ch / Float(recH) > 2 {
            let s = 0.5 * ch / Float(recH)
            let ya = Int(max(0, Y0 - 4 * s)), yb = min(h, Int(min(Float(h), Y1 + 4 * s)) + 1)
            let xa = Int(max(0, X0 - 4 * s)), xb = min(w, Int(min(Float(w), X1 + 4 * s)) + 1)
            src = blur(g, w, s, xa, ya, xb, yb); sw = xb - xa; sh = yb - ya; ox = xa; oy = ya
        }
        var c = [Float](repeating: 0, count: recH * ow)
        let ax = cw / Float(ow), ay = ch / Float(recH)
        for y in 0..<recH { for x in 0..<ow {
            let fx = min(max(ax * Float(x) + X0 - Float(ox), 0), Float(sw - 1)), fy = min(max(ay * Float(y) + Y0 - Float(oy), 0), Float(sh - 1))
            let ix = Int(fx), iy = Int(fy), ix2 = min(sw - 1, ix + 1), iy2 = min(sh - 1, iy + 1), tx = fx - Float(ix), ty = fy - Float(iy)
            let top = src[iy * sw + ix] * (1 - tx) + src[iy * sw + ix2] * tx
            let bottom = src[iy2 * sw + ix] * (1 - tx) + src[iy2 * sw + ix2] * tx
            c[y * ow + x] = top * (1 - ty) + bottom * ty
        } }
        let lo = percentile(c, 3), hi = percentile(c, 90), d = max(hi - lo, 12)
        for i in 0..<c.count { c[i] = min(1, max(0, (hi - c[i]) / d)) }
        return Tensor(x: c, c: 1, h: recH, w: ow)
    }

    private func boxes(_ y: [Float], _ gh: Int, _ gw: Int) -> [[Float]] {
        let n = gh * gw
        let prob = (0..<n).map { 1 / (1 + expf(-y[$0])) }
        var seen = [Bool](repeating: false, count: n), q = [Int](repeating: 0, count: n), out: [[Float]] = []
        for s in 0..<n where prob[s] > 0.5 && !seen[s] {
            var head = 0, tail = 0; q[tail] = s; tail += 1; seen[s] = true
            var sw = 0.0, a = 0.0, b = 0.0, c = 0.0, d = 0.0
            while head < tail {
                let k = q[head]; head += 1
                let r = k / gw, cc = k % gw, p = Double(prob[k]), u = Double(boxUnit)
                let px = (Double(cc) + 0.5) * Double(cell), py = (Double(r) + 0.5) * Double(cell)
                sw += p; a += p * (px - Double(y[n + k]) * u); b += p * (py - Double(y[2 * n + k]) * u)
                c += p * (px + Double(y[3 * n + k]) * u); d += p * (py + Double(y[4 * n + k]) * u)
                for m in [r > 0 ? k - gw : -1, r < gh - 1 ? k + gw : -1, cc > 0 ? k - 1 : -1, cc < gw - 1 ? k + 1 : -1]
                where m >= 0 && !seen[m] && prob[m] > 0.5 { seen[m] = true; q[tail] = m; tail += 1 }
            }
            let bx = [Float(a / sw), Float(b / sw), Float(c / sw), Float(d / sw)]
            if bx[2] - bx[0] >= 2 && bx[3] - bx[1] >= 3 { out.append(bx) }
        }
        return out
    }

    private func decode(_ z: [Float], _ cls: Int, _ steps: Int) -> String {
        var s = "", prev = 0
        for t in 0..<steps {
            var best = 0, mx = -Float.infinity
            for k in 0..<cls where z[k * steps + t] > mx { mx = z[k * steps + t]; best = k }
            if best != prev && best != 0 { s.append(charset[best - 1]) }
            prev = best
        }
        return s
    }

    /// gray: w*h luminance 0...255. Words with boxes normalized, origin at the bottom left (like Vision).
    func read(gray: [Float], width w: Int, height h: Int) -> [TextFragment] {
        let k = Float(detLong) / Float(max(w, h))
        let nh = max(cell, Int((Float(h) * k / Float(cell)).rounded()) * cell), nw = max(cell, Int((Float(w) * k / Float(cell)).rounded()) * cell)
        var x = resize(gray, w, h, nw, nh)
        for i in 0..<x.count { x[i] = x[i] / 255 - 0.5 }
        let y = run(det, Tensor(x: x, c: 1, h: nh, w: nw))
        let kx = Float(nw) / Float(w), ky = Float(nh) / Float(h)
        return boxes(y.x, y.h, y.w).compactMap { b in
            let X0 = b[0] / kx, Y0 = b[1] / ky, X1 = b[2] / kx, Y1 = b[3] / ky
            let z = run(rec, crop(gray, w, h, X0, Y0, X1, Y1))
            let t = decode(z.x, z.c, z.w)
            if t.isEmpty { return nil }
            let box = CGRect(x: CGFloat(X0) / CGFloat(w), y: 1 - CGFloat(Y1) / CGFloat(h), width: CGFloat(X1 - X0) / CGFloat(w), height: CGFloat(Y1 - Y0) / CGFloat(h))
            return TextFragment(text: t, box: box)
        }
    }

    /// Luminance of an image, longer side at most 2400 px.
    static func gray(_ image: CGImage) -> ([Float], Int, Int) {
        let k = min(1, 2400 / Double(max(image.width, image.height)))
        let w = max(1, Int(Double(image.width) * k)), h = max(1, Int(Double(image.height) * k))
        var px = [UInt8](repeating: 0, count: w * h)
        px.withUnsafeMutableBytes { buf in
            if let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                                   space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) {
                ctx.interpolationQuality = .high
                ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            }
        }
        return (px.map(Float.init), w, h)
    }
}
