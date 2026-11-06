// Printout reader: finds and reads the words on a photo of a range result strip with two small trained
// networks (tools/reader). Runs on the device; reader.json + reader.bin hold the model.
// Same steps as tools/reader/infer.py + prep.py (the reference); the iPhone and Android apps follow it too.
(function () {
  "use strict";

  function half(buf) {   // float16 -> Float32Array
    const u = new Uint16Array(buf), f = new Float32Array(u.length);
    for (let i = 0; i < u.length; i++) {
      const h = u[i], s = h & 0x8000 ? -1 : 1, e = (h >> 10) & 31, m = h & 1023;
      f[i] = e === 0 ? s * m * 5.960464477539063e-8 : e === 31 ? (m ? NaN : s * Infinity) : s * (1 + m / 1024) * Math.pow(2, e - 15);
    }
    return f;
  }

  // ---------- network: 2D convolutions (zero padding, dilation), ReLU, max pooling ----------
  function run(layers, W, x, c, h, w) {
    for (let li = 0; li < layers.length; li++) {
      const L = layers[li];
      if (L.op === "pool") { const r = pool(x, c, h, w, L.kh, L.kw); x = r[0]; h = r[1]; w = r[2]; continue; }
      const next = layers[li + 1], fuse = next && next.op === "pool" ? next : null;
      const oh = h + 2 * L.ph - L.dh * (L.kh - 1), ow = w + 2 * L.pw - L.dw * (L.kw - 1);
      const ph2 = fuse ? Math.floor(oh / fuse.kh) : oh, pw2 = fuse ? Math.floor(ow / fuse.kw) : ow;
      const out = new Float32Array(L.cout * ph2 * pw2), plane = new Float32Array(oh * ow);
      const wo = L.offset, n = L.cout * L.cin * L.kh * L.kw, bo = wo + n;
      for (let co = 0; co < L.cout; co++) {
        plane.fill(W[bo + co]);
        for (let ci = 0; ci < c; ci++) {
          const src = ci * h * w;
          for (let i = 0; i < L.kh; i++) {
            const dy = i * L.dh - L.ph, y0 = Math.max(0, -dy), y1 = Math.min(oh, h - dy);
            for (let j = 0; j < L.kw; j++) {
              const wt = W[wo + ((co * c + ci) * L.kh + i) * L.kw + j];
              if (wt === 0) continue;
              const dx = j * L.dw - L.pw, x0 = Math.max(0, -dx), x1 = Math.min(ow, w - dx);
              for (let y = y0; y < y1; y++) {
                const o = y * ow, s = src + (y + dy) * w + dx;
                for (let xx = x0; xx < x1; xx++) plane[o + xx] += wt * x[s + xx];
              }
            }
          }
        }
        if (L.relu) for (let k = 0; k < plane.length; k++) if (plane[k] < 0) plane[k] = 0;
        const dst = co * ph2 * pw2;
        if (!fuse) out.set(plane, dst);
        else for (let y = 0; y < ph2; y++) for (let xx = 0; xx < pw2; xx++) {
          let m = -Infinity;
          for (let a = 0; a < fuse.kh; a++) for (let b = 0; b < fuse.kw; b++) { const v = plane[(y * fuse.kh + a) * ow + xx * fuse.kw + b]; if (v > m) m = v; }
          out[dst + y * pw2 + xx] = m;
        }
      }
      x = out; c = L.cout; h = ph2; w = pw2;
      if (fuse) li++;
    }
    return [x, c, h, w];
  }
  function pool(x, c, h, w, kh, kw) {
    const oh = Math.floor(h / kh), ow = Math.floor(w / kw), out = new Float32Array(c * oh * ow);
    for (let k = 0; k < c; k++) for (let y = 0; y < oh; y++) for (let xx = 0; xx < ow; xx++) {
      let m = -Infinity;
      for (let a = 0; a < kh; a++) for (let b = 0; b < kw; b++) { const v = x[k * h * w + (y * kh + a) * w + xx * kw + b]; if (v > m) m = v; }
      out[(k * oh + y) * ow + xx] = m;
    }
    return [out, oh, ow];
  }

  // ---------- image steps ----------
  // area resampling (like OpenCV INTER_AREA) when shrinking, bilinear when enlarging
  function resize(src, w, h, nw, nh) {
    const tmp = new Float32Array(nw * h), out = new Float32Array(nw * nh);
    axis(src, tmp, h, w, nw, w, 1, nw, 1);          // rows: line l starts at l*w, pixels next to each other
    axis(tmp, out, nw, h, nh, 1, nw, 1, nw);        // columns: line l starts at l, pixels nw apart
    return out;
    function axis(a, b, lines, n, m, lineA, stepA, lineB, stepB) {
      const sc = n / m;
      for (let l = 0; l < lines; l++) for (let i = 0; i < m; i++) {
        let v = 0;
        if (sc > 1) {
          const f0 = i * sc, f1 = f0 + sc; let k = Math.floor(f0);
          for (; k < f1 && k < n; k++) v += a[l * lineA + k * stepA] * (Math.min(f1, k + 1) - Math.max(f0, k));
          v /= sc;
        } else {
          let f = (i + 0.5) * sc - 0.5; if (f < 0) f = 0;
          const k = Math.min(n - 1, Math.floor(f)), k2 = Math.min(n - 1, k + 1), t = f - k;
          v = a[l * lineA + k * stepA] * (1 - t) + a[l * lineA + k2 * stepA] * t;
        }
        b[l * lineB + i * stepB] = v;
      }
    }
  }

  function blur(g, w, h, s, x0, y0, x1, y1) {    // gaussian blur of a region, returned with its origin
    const r = Math.max(1, Math.round(s * 4)), k = [];
    let sum = 0;
    for (let i = -r; i <= r; i++) { const v = Math.exp(-(i * i) / (2 * s * s)); k.push(v); sum += v; }
    for (let i = 0; i < k.length; i++) k[i] /= sum;
    const rw = x1 - x0, rh = y1 - y0, t = new Float32Array(rw * rh), o = new Float32Array(rw * rh);
    const cl = (v, n) => (v < 0 ? 0 : v >= n ? n - 1 : v);
    for (let y = 0; y < rh; y++) for (let x = 0; x < rw; x++) {
      let v = 0; for (let i = -r; i <= r; i++) v += k[i + r] * g[(y0 + y) * w + cl(x0 + x + i, w)];
      t[y * rw + x] = v;
    }
    for (let y = 0; y < rh; y++) for (let x = 0; x < rw; x++) {
      let v = 0; for (let i = -r; i <= r; i++) v += k[i + r] * t[cl(y + i, rh) * rw + x];
      o[y * rw + x] = v;
    }
    return o;
  }

  function percentile(a, q) {
    const s = Float32Array.from(a).sort(), p = (q / 100) * (s.length - 1), i = Math.floor(p);
    return i + 1 < s.length ? s[i] + (s[i + 1] - s[i]) * (p - i) : s[i];
  }

  function crop(g, w, h, x0, y0, x1, y1, M) {
    const bh = y1 - y0;
    const X0 = x0 - 0.3 * bh, X1 = x1 + 0.3 * bh, Y0 = y0 - 0.2 * bh, Y1 = y1 + 0.2 * bh;
    const ch = Y1 - Y0, cw = X1 - X0, H = M.recHeight;
    let ow = Math.round(Math.min(M.recMaxWidth, Math.max(8, Math.round((cw * H) / ch))));
    ow = Math.ceil(ow / 4) * 4;
    let src = g, sw = w, sh = h, ox = 0, oy = 0;
    if (ch / H > 2) {
      const s = (0.5 * ch) / H;
      const ya = Math.floor(Math.max(0, Y0 - 4 * s)), yb = Math.floor(Math.min(h, Y1 + 4 * s)) + 1;
      const xa = Math.floor(Math.max(0, X0 - 4 * s)), xb = Math.floor(Math.min(w, X1 + 4 * s)) + 1;
      src = blur(g, w, h, s, xa, ya, Math.min(w, xb), Math.min(h, yb)); sw = Math.min(w, xb) - xa; sh = Math.min(h, yb) - ya; ox = xa; oy = ya;
    }
    const c = new Float32Array(H * ow), ax = cw / ow, ay = ch / H;
    for (let y = 0; y < H; y++) for (let x = 0; x < ow; x++) {
      let fx = ax * x + X0 - ox, fy = ay * y + Y0 - oy;
      fx = Math.min(Math.max(fx, 0), sw - 1); fy = Math.min(Math.max(fy, 0), sh - 1);
      const ix = Math.floor(fx), iy = Math.floor(fy), ix2 = Math.min(sw - 1, ix + 1), iy2 = Math.min(sh - 1, iy + 1), tx = fx - ix, ty = fy - iy;
      c[y * ow + x] = (src[iy * sw + ix] * (1 - tx) + src[iy * sw + ix2] * tx) * (1 - ty) + (src[iy2 * sw + ix] * (1 - tx) + src[iy2 * sw + ix2] * tx) * ty;
    }
    const lo = percentile(c, 3), hi = percentile(c, 90), d = Math.max(hi - lo, 12);
    for (let i = 0; i < c.length; i++) { const v = (hi - c[i]) / d; c[i] = v < 0 ? 0 : v > 1 ? 1 : v; }
    return [c, ow];
  }

  function boxes(y, gh, gw, M) {
    const n = gh * gw, prob = new Float32Array(n), lab = new Int32Array(n), out = [];
    for (let i = 0; i < n; i++) prob[i] = 1 / (1 + Math.exp(-y[i]));
    const q = new Int32Array(n);
    for (let s = 0; s < n; s++) {
      if (prob[s] <= 0.5 || lab[s]) continue;
      let head = 0, tail = 0; q[tail++] = s; lab[s] = 1;
      let sw = 0, a = 0, b = 0, c = 0, d = 0;
      while (head < tail) {
        const k = q[head++], r = Math.floor(k / gw), cc = k % gw, p = prob[k];
        const px = (cc + 0.5) * M.cell, py = (r + 0.5) * M.cell, u = M.boxUnit;
        sw += p; a += p * (px - y[n + k] * u); b += p * (py - y[2 * n + k] * u); c += p * (px + y[3 * n + k] * u); d += p * (py + y[4 * n + k] * u);
        const nb = [r > 0 ? k - gw : -1, r < gh - 1 ? k + gw : -1, cc > 0 ? k - 1 : -1, cc < gw - 1 ? k + 1 : -1];
        for (const m of nb) if (m >= 0 && !lab[m] && prob[m] > 0.5) { lab[m] = 1; q[tail++] = m; }
      }
      const bx = [a / sw, b / sw, c / sw, d / sw];
      if (bx[2] - bx[0] >= 2 && bx[3] - bx[1] >= 3) out.push(bx);
    }
    return out;
  }

  function decode(z, C, T, charset) {
    let s = "", prev = 0; const conf = [];
    for (let t = 0; t < T; t++) {
      let best = 0, mx = -Infinity;
      for (let k = 0; k < C; k++) if (z[k * T + t] > mx) { mx = z[k * T + t]; best = k; }
      if (best !== prev && best) {
        let sum = 0; for (let k = 0; k < C; k++) sum += Math.exp(z[k * T + t] - mx);
        s += charset[best - 1]; conf.push(1 / sum);
      }
      prev = best;
    }
    return [s, conf.length ? conf.reduce((x, y) => x + y, 0) / conf.length : 0];
  }

  function create(meta, buffer) {
    const W = half(buffer);
    return {
      /** gray: Uint8Array/Float32Array w*h (0..255). Returns words, boxes normalized with the origin at the bottom left. */
      read(gray, w, h) {
        const g = gray instanceof Float32Array ? gray : Float32Array.from(gray);
        const k = meta.detLong / Math.max(w, h), C = meta.cell;
        const nh = Math.max(C, Math.round((h * k) / C) * C), nw = Math.max(C, Math.round((w * k) / C) * C);
        const x = resize(g, w, h, nw, nh);
        for (let i = 0; i < x.length; i++) x[i] = x[i] / 255 - 0.5;
        const [y, , gh, gw] = run(meta.det, W, x, 1, nh, nw);
        const kx = nw / w, ky = nh / h, words = [];
        for (const b of boxes(y, gh, gw, meta)) {
          const X0 = b[0] / kx, Y0 = b[1] / ky, X1 = b[2] / kx, Y1 = b[3] / ky;
          const [c, ow] = crop(g, w, h, X0, Y0, X1, Y1, meta);
          const [z, C2, , T] = run(meta.rec, W, c, 1, meta.recHeight, ow);
          const [t, conf] = decode(z, C2, T, meta.charset);
          if (t) words.push({ text: t, x: X0 / w, y: 1 - Y1 / h, w: (X1 - X0) / w, h: (Y1 - Y0) / h, conf });
        }
        return words;
      },
    };
  }

  const api = { create };
  if (typeof module !== "undefined" && module.exports) module.exports = api; else self.ScanReader = api;
})();
