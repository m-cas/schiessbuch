package io.github.mcas.schiessbuch.scan

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Printout reader: two small trained networks find and read the words on a photo of a range result strip
 * (tools/reader in the repository). Same steps as tools/reader/infer.py and reader.js (web).
 * The model is assets/reader.json + assets/reader.bin.
 */
class Reader(metaJson: String, bin: ByteArray) {
    private class Layer(val pool: Boolean, val cin: Int, val cout: Int, val kh: Int, val kw: Int, val dh: Int, val dw: Int,
                        val ph: Int, val pw: Int, val relu: Boolean, val offset: Int)

    private val charset: String
    private val det: List<Layer>
    private val rec: List<Layer>
    private val detLong: Int
    private val cell: Int
    private val boxUnit: Float
    private val recH: Int
    private val recMaxW: Int
    private val w: FloatArray

    init {
        val m = JSONObject(metaJson)
        charset = m.getString("charset")
        det = layers(m.getJSONArray("det")); rec = layers(m.getJSONArray("rec"))
        detLong = m.getInt("detLong"); cell = m.getInt("cell"); boxUnit = m.getDouble("boxUnit").toFloat()
        recH = m.getInt("recHeight"); recMaxW = m.getInt("recMaxWidth")
        val sb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        w = FloatArray(sb.remaining()) { half(sb.get(it).toInt() and 0xffff) }
    }

    private fun layers(a: JSONArray) = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        if (o.getString("op") == "pool") Layer(true, 0, 0, o.getInt("kh"), o.getInt("kw"), 1, 1, 0, 0, false, 0)
        else Layer(false, o.getInt("cin"), o.getInt("cout"), o.getInt("kh"), o.getInt("kw"), o.getInt("dh"), o.getInt("dw"),
            o.getInt("ph"), o.getInt("pw"), o.getBoolean("relu"), o.getInt("offset"))
    }

    private fun half(h: Int): Float {
        val s = if (h and 0x8000 != 0) -1f else 1f; val e = (h shr 10) and 31; val m = h and 1023
        return when (e) {
            0 -> s * m * 5.9604645e-8f
            31 -> if (m != 0) Float.NaN else s * Float.POSITIVE_INFINITY
            else -> s * (1 + m / 1024f) * Math.pow(2.0, (e - 15).toDouble()).toFloat()
        }
    }

    private class T(val x: FloatArray, val c: Int, val h: Int, val w: Int)

    private fun run(layers: List<Layer>, input: T): T {
        var x = input.x; var c = input.c; var h = input.h; var w0 = input.w
        var li = 0
        while (li < layers.size) {
            val L = layers[li]
            if (L.pool) { val r = pool(T(x, c, h, w0), L.kh, L.kw); x = r.x; h = r.h; w0 = r.w; li++; continue }
            val fuse = layers.getOrNull(li + 1)?.takeIf { it.pool }
            val oh = h + 2 * L.ph - L.dh * (L.kh - 1); val ow = w0 + 2 * L.pw - L.dw * (L.kw - 1)
            val ph2 = if (fuse != null) oh / fuse.kh else oh; val pw2 = if (fuse != null) ow / fuse.kw else ow
            val out = FloatArray(L.cout * ph2 * pw2); val plane = FloatArray(oh * ow)
            val n = L.cout * L.cin * L.kh * L.kw
            for (co in 0 until L.cout) {
                plane.fill(w[L.offset + n + co])
                for (ci in 0 until c) {
                    val src = ci * h * w0
                    for (i in 0 until L.kh) {
                        val dy = i * L.dh - L.ph; val y0 = max(0, -dy); val y1 = min(oh, h - dy)
                        for (j in 0 until L.kw) {
                            val wt = w[L.offset + ((co * c + ci) * L.kh + i) * L.kw + j]
                            if (wt == 0f) continue
                            val dx = j * L.dw - L.pw; val x0 = max(0, -dx); val x1 = min(ow, w0 - dx)
                            for (y in y0 until y1) {
                                val o = y * ow; val s = src + (y + dy) * w0 + dx
                                for (xx in x0 until x1) plane[o + xx] += wt * x[s + xx]
                            }
                        }
                    }
                }
                if (L.relu) for (k in plane.indices) if (plane[k] < 0f) plane[k] = 0f
                val dst = co * ph2 * pw2
                if (fuse == null) System.arraycopy(plane, 0, out, dst, plane.size)
                else for (y in 0 until ph2) for (xx in 0 until pw2) {
                    var mx = Float.NEGATIVE_INFINITY
                    for (a in 0 until fuse.kh) for (b in 0 until fuse.kw) { val v = plane[(y * fuse.kh + a) * ow + xx * fuse.kw + b]; if (v > mx) mx = v }
                    out[dst + y * pw2 + xx] = mx
                }
            }
            x = out; c = L.cout; h = ph2; w0 = pw2
            li += if (fuse != null) 2 else 1
        }
        return T(x, c, h, w0)
    }

    private fun pool(t: T, kh: Int, kw: Int): T {
        val oh = t.h / kh; val ow = t.w / kw; val out = FloatArray(t.c * oh * ow)
        for (k in 0 until t.c) for (y in 0 until oh) for (xx in 0 until ow) {
            var mx = Float.NEGATIVE_INFINITY
            for (a in 0 until kh) for (b in 0 until kw) { val v = t.x[k * t.h * t.w + (y * kh + a) * t.w + xx * kw + b]; if (v > mx) mx = v }
            out[(k * oh + y) * ow + xx] = mx
        }
        return T(out, t.c, oh, ow)
    }

    /** Area resampling when shrinking (like OpenCV INTER_AREA), bilinear when enlarging. */
    private fun resize(src: FloatArray, w0: Int, h: Int, nw: Int, nh: Int): FloatArray {
        val tmp = FloatArray(nw * h); val out = FloatArray(nw * nh)
        axis(src, tmp, h, w0, nw, w0, 1, nw, 1)
        axis(tmp, out, nw, h, nh, 1, nw, 1, nw)
        return out
    }

    private fun axis(a: FloatArray, b: FloatArray, lines: Int, n: Int, m: Int, lineA: Int, stepA: Int, lineB: Int, stepB: Int) {
        val sc = n.toDouble() / m
        for (l in 0 until lines) for (i in 0 until m) {
            var v = 0.0
            if (sc > 1) {
                val f0 = i * sc; val f1 = f0 + sc; var k = floor(f0).toInt()
                while (k < f1 && k < n) { v += a[l * lineA + k * stepA] * (min(f1, k + 1.0) - max(f0, k.toDouble())); k++ }
                v /= sc
            } else {
                var f = (i + 0.5) * sc - 0.5; if (f < 0) f = 0.0
                val k = min(n - 1, floor(f).toInt()); val k2 = min(n - 1, k + 1); val t = f - k
                v = a[l * lineA + k * stepA] * (1 - t) + a[l * lineA + k2 * stepA] * t
            }
            b[l * lineB + i * stepB] = v.toFloat()
        }
    }

    private fun blur(g: FloatArray, w0: Int, s: Float, x0: Int, y0: Int, x1: Int, y1: Int): FloatArray {
        val r = max(1, (s * 4).roundToInt())
        val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * s * s)) }
        val sum = k.sum(); for (i in k.indices) k[i] /= sum
        val rw = x1 - x0; val rh = y1 - y0; val t = FloatArray(rw * rh); val o = FloatArray(rw * rh)
        for (y in 0 until rh) for (x in 0 until rw) {
            var v = 0f; for (i in -r..r) v += k[i + r] * g[(y0 + y) * w0 + (x0 + x + i).coerceIn(0, w0 - 1)]
            t[y * rw + x] = v
        }
        for (y in 0 until rh) for (x in 0 until rw) {
            var v = 0f; for (i in -r..r) v += k[i + r] * t[(y + i).coerceIn(0, rh - 1) * rw + x]
            o[y * rw + x] = v
        }
        return o
    }

    private fun percentile(a: FloatArray, q: Double): Float {
        val s = a.copyOf(); s.sort()
        val p = q / 100 * (s.size - 1); val i = floor(p).toInt()
        return if (i + 1 < s.size) (s[i] + (s[i + 1] - s[i]) * (p - i)).toFloat() else s[i]
    }

    private fun crop(g: FloatArray, w0: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float): T {
        val bh = y1 - y0
        val X0 = x0 - 0.3f * bh; val X1 = x1 + 0.3f * bh; val Y0 = y0 - 0.2f * bh; val Y1 = y1 + 0.2f * bh
        val ch = Y1 - Y0; val cw = X1 - X0
        var ow = min(recMaxW.toFloat(), max(8f, (cw * recH / ch).roundToInt().toFloat())).roundToInt()
        ow = (ow + 3) / 4 * 4
        var src = g; var sw = w0; var sh = h; var ox = 0; var oy = 0
        if (ch / recH > 2) {
            val s = 0.5f * ch / recH
            val ya = floor(max(0f, Y0 - 4 * s)).toInt(); val yb = min(h, floor(min(h.toFloat(), Y1 + 4 * s)).toInt() + 1)
            val xa = floor(max(0f, X0 - 4 * s)).toInt(); val xb = min(w0, floor(min(w0.toFloat(), X1 + 4 * s)).toInt() + 1)
            src = blur(g, w0, s, xa, ya, xb, yb); sw = xb - xa; sh = yb - ya; ox = xa; oy = ya
        }
        val c = FloatArray(recH * ow); val ax = cw / ow; val ay = ch / recH
        for (y in 0 until recH) for (x in 0 until ow) {
            val fx = (ax * x + X0 - ox).coerceIn(0f, (sw - 1).toFloat()); val fy = (ay * y + Y0 - oy).coerceIn(0f, (sh - 1).toFloat())
            val ix = fx.toInt(); val iy = fy.toInt(); val ix2 = min(sw - 1, ix + 1); val iy2 = min(sh - 1, iy + 1)
            val tx = fx - ix; val ty = fy - iy
            c[y * ow + x] = (src[iy * sw + ix] * (1 - tx) + src[iy * sw + ix2] * tx) * (1 - ty) + (src[iy2 * sw + ix] * (1 - tx) + src[iy2 * sw + ix2] * tx) * ty
        }
        val lo = percentile(c, 3.0); val hi = percentile(c, 90.0); val d = max(hi - lo, 12f)
        for (i in c.indices) c[i] = ((hi - c[i]) / d).coerceIn(0f, 1f)
        return T(c, 1, recH, ow)
    }

    private fun boxes(y: FloatArray, gh: Int, gw: Int): List<FloatArray> {
        val n = gh * gw; val prob = FloatArray(n) { 1f / (1f + exp(-y[it])) }; val seen = BooleanArray(n); val q = IntArray(n)
        val out = mutableListOf<FloatArray>()
        for (s in 0 until n) {
            if (prob[s] <= 0.5f || seen[s]) continue
            var head = 0; var tail = 0; q[tail++] = s; seen[s] = true
            var sw = 0.0; var a = 0.0; var b = 0.0; var c = 0.0; var d = 0.0
            while (head < tail) {
                val k = q[head++]; val r = k / gw; val cc = k % gw; val p = prob[k]
                val px = (cc + 0.5) * cell; val py = (r + 0.5) * cell
                sw += p; a += p * (px - y[n + k] * boxUnit); b += p * (py - y[2 * n + k] * boxUnit)
                c += p * (px + y[3 * n + k] * boxUnit); d += p * (py + y[4 * n + k] * boxUnit)
                for (m in intArrayOf(if (r > 0) k - gw else -1, if (r < gh - 1) k + gw else -1, if (cc > 0) k - 1 else -1, if (cc < gw - 1) k + 1 else -1))
                    if (m >= 0 && !seen[m] && prob[m] > 0.5f) { seen[m] = true; q[tail++] = m }
            }
            val bx = floatArrayOf((a / sw).toFloat(), (b / sw).toFloat(), (c / sw).toFloat(), (d / sw).toFloat())
            if (bx[2] - bx[0] >= 2 && bx[3] - bx[1] >= 3) out.add(bx)
        }
        return out
    }

    private fun decode(z: FloatArray, cls: Int, steps: Int): String {
        val sb = StringBuilder(); var prev = 0
        for (t in 0 until steps) {
            var best = 0; var mx = Float.NEGATIVE_INFINITY
            for (k in 0 until cls) if (z[k * steps + t] > mx) { mx = z[k * steps + t]; best = k }
            if (best != prev && best != 0) sb.append(charset[best - 1])
            prev = best
        }
        return sb.toString()
    }

    /** gray: w*h luminance 0..255. Words with boxes normalized, origin at the bottom left. */
    fun read(gray: FloatArray, w0: Int, h: Int): List<Word> {
        val k = detLong.toFloat() / max(w0, h)
        val nh = max(cell, (h * k / cell).roundToInt() * cell); val nw = max(cell, (w0 * k / cell).roundToInt() * cell)
        val x = resize(gray, w0, h, nw, nh)
        for (i in x.indices) x[i] = x[i] / 255f - 0.5f
        val y = run(det, T(x, 1, nh, nw))
        val kx = nw.toFloat() / w0; val ky = nh.toFloat() / h
        return boxes(y.x, y.h, y.w).mapNotNull { b ->
            val X0 = b[0] / kx; val Y0 = b[1] / ky; val X1 = b[2] / kx; val Y1 = b[3] / ky
            val z = run(rec, crop(gray, w0, h, X0, Y0, X1, Y1))
            val t = decode(z.x, z.c, z.w)
            if (t.isEmpty()) null else Word(t, (X0 / w0).toDouble(), (1 - Y1 / h).toDouble(), ((X1 - X0) / w0).toDouble(), ((Y1 - Y0) / h).toDouble())
        }
    }
}
