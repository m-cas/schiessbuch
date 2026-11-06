// Reads the text of range result strips (Swiss 300 m / field shooting printouts) into shots and series.
// JavaScript port of the ShootingResultScanner parser (StripParser, GenericSheetParser, HeaderParser,
// LayoutAnalyzer, ResultAssembler). OCR itself is done by Tesseract.js in index.html.
(function (root) {
  "use strict";
  const TOL = 0.051;
  const round1 = (x) => Math.round(x * 10) / 10;
  const ringForHundred = (h) => (h <= 0 ? 0 : Math.ceil(h / 10));
  const fmt = (x) => (x === Math.round(x) ? String(x) : x.toFixed(1));
  const isLetter = (c) => /\p{L}/u.test(c);
  const isAlnum = (c) => /[\p{L}\p{N}]/u.test(c);
  const firstMatch = (re, text) => { const m = re.exec(text); return m ? Array.from(m, (x) => x === undefined ? null : x) : null; };

  // ---------- model helpers ----------
  const shot = (number, value, hundred = null, inner = false) => ({ number, value, hundred, inner });
  const series = (number, shots, printedTotal = null, printedHundredTotal = null) => ({ number, shots, printedTotal, printedHundredTotal });
  const sComputed = (s) => round1(s.shots.reduce((a, x) => a + x.value, 0));
  const sTotal = (s) => (s.printedTotal != null ? s.printedTotal : sComputed(s));
  const sComputedH = (s) => (s.shots.length && s.shots.every((x) => x.hundred != null) ? s.shots.reduce((a, x) => a + x.hundred, 0) : null);
  const sHundred = (s) => (s.printedHundredTotal != null ? s.printedHundredTotal : sComputedH(s));
  const sConsistent = (s) => {
    const c = [];
    if (s.printedTotal != null) c.push(Math.abs(s.printedTotal - sComputed(s)) < TOL);
    if (s.printedHundredTotal != null && sComputedH(s) != null) c.push(s.printedHundredTotal === sComputedH(s));
    return c.length ? !c.includes(false) : null;
  };
  function newResult(mode, lines) {
    return { scoringMode: mode, recognizedLines: lines, sighters: [], series: [], printedTotal: null, printedHundredTotal: null,
      innerTenCount: null, deepShots: [], warnings: [], copiesRead: 1, copiesAgree: null,
      program: null, targetLabel: null, date: null, time: null, discipline: null,
      shooterName: null, birthYear: null, category: null, club: null, weapon: null, organizer: null, event: null, competition: null,
      awardThreshold: null, payoutThreshold: null };
  }
  const rShots = (r) => r.series.flatMap((s) => s.shots);
  const rComputed = (r) => round1(r.series.reduce((a, s) => a + sTotal(s), 0));
  const rTotal = (r) => (r.printedTotal != null ? r.printedTotal : rComputed(r));
  const rHundred = (r) => {
    if (r.printedHundredTotal != null) return r.printedHundredTotal;
    const p = r.series.map(sHundred);
    return !p.length || p.includes(null) ? null : p.reduce((a, v) => a + v, 0);
  };
  const rMax = (r) => rShots(r).length * (r.scoringMode === "hundred" ? 100 : 10);
  const rVerified = (r) => r.warnings.length === 0 && (r.printedTotal != null || r.series.some((s) => sConsistent(s) === true));

  // ---------- generic sheet helpers ----------
  const DATE_RE = /\b\d{1,2}[./-]\d{1,2}[./-]\d{2,4}\b/;
  const DATE_RE_G = /\b\d{1,2}[./-]\d{1,2}[./-]\d{2,4}\b/g;
  function stripDatesAndTimes(line) {
    return line.replace(DATE_RE_G, " ").replace(/\b\d{4}-\d{2}-\d{2}\b/g, " ").replace(/\b\d{1,2}:\d{2}(?::\d{2})?\b/g, " ");
  }
  function parseToken(raw) {
    let t = raw.replace(/^[,;'"`´_~]+|[,;'"`´_~]+$/g, "");
    if (!t || t.startsWith("-")) return null;
    if (t === "X" || t === "x") return { value: 10, decimals: 0, inner: true };
    let inner = false;
    if (t.endsWith("*") || t.endsWith("+")) { inner = true; t = t.slice(0, -1); }
    if (!/[0-9]/.test(t)) return null;
    let s = [...t].map((c) => ("OoQ".includes(c) ? "0" : "lIi|!".includes(c) ? "1" : c === "," ? "." : c)).join("");
    while (s.endsWith(".")) s = s.slice(0, -1);
    if (!/^\d{1,4}(\.\d{1,2})?$/.test(s)) return null;
    const dec = s.includes(".") ? s.split(".")[1].length : 0;
    return { value: parseFloat(s), decimals: dec, inner };
  }
  const numberTokens = (text) => text.split(/[\s|;=:()[\]{}/]+/).map(parseToken).filter(Boolean);
  const isIndexSequence = (tk) => tk.length >= 4 && tk.every((x) => !x.decimals) && tk.every((x, i) => i === 0 || x.value === tk[i - 1].value + 1);
  function stripSeriesLabel(line) {
    for (const re of [/^\s*(?:serie|series|série|passe|pass|s)\s*\.?\s*(\d{1,2})\s*[:.)\-]?\s+/i, /^\s*(\d{1,2})\s*[.):]\s+/]) {
      const m = re.exec(line);
      if (m) return [parseInt(m[1], 10), line.slice(m[0].length)];
    }
    return [null, line];
  }
  const G_NEUTRAL = new Set(["summe", "sum", "somme", "somma", "zwischentotal", "subtotal", "serie", "series", "série", "passe", "pass",
    "ring", "ringe", "punkte", "pkt", "pts", "points", "punti", "schuss", "shot", "shots", "coups", "colpi"]);
  const TOTAL_KW = ["total", "gesamt", "gesamttotal", "endresultat", "resultat", "résultat", "ergebnis", "result", "totale", "risultato", "score"];
  const INNER_KW = ["innenzehner", "innen-zehner", "mouche", "mouches", "mouchen", "inner", "x-count", "xs", "centri"];
  const DISC_KW = ["luftgewehr", "luftpistole", "kleinkaliber", "sportpistole", "schnellfeuer", "ordonnanz", "gewehr 300", "gewehr 50",
    "pistole 25", "pistole 50", "air rifle", "air pistol", "smallbore", "carabine", "pistolet", "fusil", "carabina", "pistola", "10m", "25m", "50m", "300m"];
  const containsWords = (line) => line.split(/\s+/).some((w) => {
    const x = w.toLowerCase().replace(/^[\p{P}]+|[\p{P}]+$/gu, "");
    return x.length >= 2 && [...x].every(isLetter) && !G_NEUTRAL.has(x);
  });
  const containsKeyword = (lower, kws) => { const ws = new Set(lower.split(/[^\p{L}-]+/u)); return kws.some((k) => ws.has(k)); };
  const looksDecimal = (lines) => lines.flatMap((l) => numberTokens(stripDatesAndTimes(l))).filter((t) => t.decimals === 1 && t.value <= 10.9).length >= 3;

  function genericParse(rawLines, cfg) {
    const lines = rawLines.map((l) => l.trim()).filter(Boolean);
    const mode = cfg.scoringMode !== "automatic" ? cfg.scoringMode : looksDecimal(lines) ? "decimal" : "integer";
    const maxShot = mode === "decimal" ? 10.9 : 10;
    const r = newResult(mode, rawLines);
    const out = [];
    let loose = [];
    let next = 1;
    const append = (shots, total, label) => out.push(series(label != null ? label : out.length + 1, shots, total));
    const chunk = (n) => (cfg.shotsPerSeries > 0 ? cfg.shotsPerSeries : n > 10 && n % 10 === 0 ? 10 : null);
    const flush = (total) => {
      if (!loose.length) return;
      const size = total == null ? chunk(loose.length) : null;
      if (size && size < loose.length) for (let i = 0; i < loose.length; i += size) append(loose.slice(i, i + size), null);
      else append(loose, total);
      loose = [];
    };
    for (const line of lines) {
      if (!r.date) { const d = firstMatch(DATE_RE, line); if (d) r.date = d[0]; }
      if (!r.shooterName) {
        const m = firstMatch(/^\s*(?:name|schütze|schützin|tireur|tireuse|tiratore|tiratrice|shooter|athlete|athlet)\s*[:.]?\s+(.+)$/i, line);
        if (m) { r.shooterName = m[m.length - 1].trim(); continue; }
      }
      const lower0 = line.toLowerCase();
      if (!r.discipline && DISC_KW.some((k) => lower0.includes(k))) r.discipline = line;
      const [label, body] = stripSeriesLabel(stripDatesAndTimes(line));
      const lower = body.toLowerCase();
      const tokens = numberTokens(body);
      if (!tokens.length || isIndexSequence(tokens)) continue;
      const isTotal = containsKeyword(lower, TOTAL_KW), isInner = containsKeyword(lower, INNER_KW);
      if (isTotal || isInner) {
        const big = isTotal ? tokens.filter((t) => t.value > maxShot).map((t) => t.value) : [];
        const total = big.length ? Math.max(...big) : null;
        if (total != null) r.printedTotal = total;
        if (isInner) { const n = tokens.find((t) => !t.decimals && t.value < 200 && t.value !== total); if (n) r.innerTenCount = n.value; }
        continue;
      }
      let values;
      if (mode === "decimal") values = tokens.filter((t) => t.decimals === 1);
      else {
        values = tokens.filter((t) => !t.decimals);
        if (values.length === 2 && values[1].value <= maxShot && values[0].value >= next && values[0].value <= next + 3) {
          next = values[0].value + 1; values = values.slice(1);
        }
      }
      if (!values.length) continue;
      if (containsWords(body) && label == null && values.length < 3) continue;
      const shots = values.filter((t) => t.value <= maxShot).map((t) => shot(null, t.value, null, t.inner));
      const bigs = values.filter((t) => t.value > maxShot).map((t) => t.value);
      const sum = round1(shots.reduce((a, x) => a + x.value, 0));
      if (shots.length && bigs.length) {
        if (shots.length >= 2 || label != null) { flush(null); append(shots, bigs.find((b) => Math.abs(b - sum) < TOL) ?? bigs[0], label); }
        else { loose.push(...shots); flush(bigs[0]); }
      } else if (shots.length >= 3) { flush(null); append(shots, null, label); }
      else if (shots.length) loose.push(...shots);
      else if (loose.length) {
        const ls = round1(loose.reduce((a, x) => a + x.value, 0));
        flush(bigs.find((b) => Math.abs(b - ls) < TOL) ?? bigs[0]);
      } else if (out.length && out[out.length - 1].printedTotal == null) {
        const last = out[out.length - 1];
        last.printedTotal = bigs.find((b) => Math.abs(b - sComputed(last)) < TOL) ?? bigs[0];
      } else if (r.printedTotal == null && out.length) {
        const ss = round1(out.reduce((a, s) => a + sTotal(s), 0));
        const g = bigs.find((b) => Math.abs(b - ss) < TOL);
        if (g != null) r.printedTotal = g;
      }
    }
    flush(null);
    r.series = out;
    if (!out.length) r.warnings = [{ type: "noShots" }];
    else {
      for (const s of out) if (sConsistent(s) === false) r.warnings.push({ type: "seriesTotal", series: s.number, printed: s.printedTotal, computed: sComputed(s) });
      if (r.printedTotal != null) {
        const c = round1(out.reduce((a, s) => a + sTotal(s), 0));
        if (Math.abs(r.printedTotal - c) >= TOL) r.warnings.push({ type: "grandTotal", printed: r.printedTotal, computed: c });
      }
    }
    return r;
  }

  // ---------- strip parser ----------
  const PG_RE = /^\s*[PF][g9qs]\s*(\d{1,3})\s+(\d{1,3}\s*\/\s*\d{2,4})/i;
  const SCHEIBE_RE = /^\s*scheibe\s*(\d{1,3}(?:\s*\/\s*\d{1,3})?)/i;
  const DEEP_RE = /^\s*[TI1l]\s*\/\s*[PF]\s*(\d{1,2})\s+(\d{1,3})\b/i;
  const SUBTOTAL_WORDS = ["subtot", "zwischen", "summe", "sous-tot", "somme", "somma"];
  const TOTAL_WORDS = ["tota", "gesamt", "résultat", "resultat", "risultato"];
  const NEUTRAL = new Set(["ring", "ringe", "pkt", "pts", "punkte", "points", "punti"]);
  const SINGLE_DIGITS = { O: 0, o: 0, I: 1, l: 1, Z: 2, J: 3, S: 5, G: 6, B: 8 };
  // "Pg 32  23/0005", "Scheibe 3/14", or a misread header that still ends in a run number ("g32 23/0005")
  const LABEL_END_RE = /^\s*\S{1,6}\s+\d{1,3}\/\d{4}\s*$/;
  const isHeaderLine = (l) => PG_RE.test(l) || SCHEIBE_RE.test(l) || LABEL_END_RE.test(l);
  const deepShot = (line) => { const m = DEEP_RE.exec(line); return m ? { shot: +m[1], value: +m[2] } : null; };

  function makeRow(line) {
    const trimmed = line.trim();
    const cleaned = stripDatesAndTimes(trimmed);
    const nums = [];
    let sighter = false, words = false;
    for (const piece of cleaned.split(/[\s|;=:()[\]{}]+/)) {
      let t = [...piece];
      while (t.length && !isAlnum(t[0])) t.shift();
      let inner = false;
      while (t.length && !isAlnum(t[t.length - 1])) { if ("*✱✲✳#".includes(t[t.length - 1])) inner = true; t.pop(); }
      if (!t.length) continue;
      let s = t.join("");
      if (!/[0-9]/.test(s)) {
        if (s === "P" || s === "p") { sighter = true; continue; }
        if (s === "X" || s === "x") { nums.push({ value: 10, inner: true, text: s }); continue; }
        if (s in SINGLE_DIGITS) { const d = SINGLE_DIGITS[s]; nums.push({ value: d, inner, text: String(d) }); continue; }
        if (s.length >= 2 && [...s].every(isLetter) && !NEUTRAL.has(s.toLowerCase())) words = true;
        continue;
      }
      if (s.length > 1 && (s.endsWith("P") || s.endsWith("p"))) { sighter = true; s = s.slice(0, -1); }
      if (s.length > 1 && s.startsWith("P")) { sighter = true; s = s.slice(1); }
      s = [...s].map((c) => ("OoQD".includes(c) ? "0" : "lIi|!".includes(c) ? "1" : c === "B" ? "8" : c === "S" ? "5" : c === "Z" ? "2" : c === "G" ? "6" : c)).join("");
      if (!/^\d{1,4}$/.test(s)) continue;
      nums.push({ value: parseInt(s, 10), inner, text: s });
    }
    const opens = trimmed && "([{<".includes(trimmed[0]);
    const closes = trimmed && ")]}>".includes(trimmed[trimmed.length - 1]);
    return { raw: trimmed, lower: trimmed.toLowerCase(), nums, isSighter: sighter, hasWords: words, isBlank: !trimmed, isParen: !!(opens || closes) };
  }

  function detectLayout(rows) {
    let dual = 0, ringS = 0, hundredS = 0;
    for (const row of rows) {
      if (row.hasWords || row.isParen || row.nums.length < 2 || deepShot(row.raw)) continue;
      const r = row.nums.slice(1).map((n) => n.value);
      if (r.length >= 2 && r[r.length - 1] > 10 && ringForHundred(r[r.length - 1]) === r[r.length - 2]) dual++;
      else if (r.length === 1 && r[0] > 10) hundredS++;
      else ringS++;
    }
    if (dual > ringS + hundredS) return "rh";
    if (hundredS > ringS) return "h";
    return "r";
  }
  const nextShotNumber = (i, rows) => { const r = rows.slice(i + 1).find((x) => !x.hasWords && !x.isBlank && x.nums.length >= 2); return r ? r.nums[0].value : null; };

  function readMetadata(row, r) {
    if (!r.date) { const d = firstMatch(DATE_RE, row.raw); if (d) r.date = d[0]; }
    if (!r.time) { const t = firstMatch(/\b\d{1,2}:\d{2}(?::\d{2})?\b/, row.raw); if (t) r.time = t[0]; }
    let m = PG_RE.exec(row.raw);
    if (m) { r.program = m[1]; r.targetLabel = m[2].replace(/ /g, ""); return; }
    m = SCHEIBE_RE.exec(row.raw);
    if (m) { r.targetLabel = m[1].replace(/ /g, ""); return; }
    if (!r.program && row.lower.includes("progr")) r.program = row.raw;
    // "21/0001" without the "Pg" in front (misread or cut off)
    if (!r.targetLabel) { m = /(?:^|\s)(\d{1,3}\/\d{3,4})\s*$/.exec(row.raw); if (m) r.targetLabel = m[1]; }
  }

  function stripParse(lines) {
    const rows = lines.map(makeRow);
    const layout = detectLayout(rows);
    // how often the shot numbering starts again at 1 (once: sighters before; more often: groups numbered from 1)
    let restarts = 0, seen = false;
    for (const row of rows) {
      if (row.hasWords || row.isSighter || row.nums.length < 2 || isHeaderLine(row.raw) || deepShot(row.raw)) continue;
      if (row.nums[0].value === 1) { if (seen) restarts++; seen = true; }
    }
    const r = newResult(layout === "h" ? "hundred" : "integer", lines);
    const st = { sighters: [], series: [], pending: [], last: 0, deep: [], pt: null, pht: null, missing: [], warnings: [] };
    const pendingSum = () => st.pending.reduce((a, x) => a + x.value, 0);
    function makeShot(number, vals) {
      const last = vals[vals.length - 1];
      if (!last) return null;
      if (layout === "h") {
        // "10 > 75 874": the series total printed on the last shot row
        if (vals.length >= 2) {
          const a = vals[vals.length - 2];
          if (a.value <= 100 && (last.value > 100 || (st.pending.length && last.value === pendingSum() + a.value)))
            return { shot: shot(number, a.value, a.value, a.inner), sub: last.value };
        }
        return { shot: shot(number, last.value, last.value, last.inner), sub: null };
      }
      if (layout === "rh") {
        if (vals.length >= 2 && last.value > 10) {
          const ring = vals[vals.length - 2];
          if (ringForHundred(last.value) !== ring.value) st.warnings.push({ type: "shotValue", shot: number, value: ring.value, hundred: last.value });
          return { shot: shot(number, ring.value, last.value, ring.inner || last.inner), sub: null };
        }
        if (last.value > 10) return { shot: shot(number, ringForHundred(last.value), last.value), sub: null };
        return { shot: shot(number, last.value, null, last.inner), sub: null };
      }
      if (vals.length === 1) return last.value <= 10 ? { shot: shot(number, last.value, null, last.inner), sub: null } : null;
      const a = vals[vals.length - 2];
      const isSub = last.value > 10 || (st.pending.length && last.value === pendingSum() + a.value);
      if (isSub && a.value <= 10) return { shot: shot(number, a.value, null, a.inner), sub: last.value };
      if (last.value > 10) return null;
      return { shot: shot(number, last.value, null, last.inner), sub: null };
    }
    function close(values = []) {
      if (!st.pending.length) {
        // a subtotal line right after the series was closed (e.g. by the parentheses line) still belongs to it
        const prev = st.series[st.series.length - 1];
        if (values.length && prev && prev.printedTotal == null && prev.printedHundredTotal == null) { st.pending = prev.shots; st.series.pop(); }
        else return;
      }
      const s = series(st.series.length + 1, st.pending);
      if (layout !== "rh") { if (values.length) s.printedTotal = values[values.length - 1]; }
      else if (values.length >= 2) { s.printedTotal = values[values.length - 2]; s.printedHundredTotal = values[values.length - 1]; }
      else if (values.length) { const v = values[0]; if (v > 10 * st.pending.length) s.printedHundredTotal = v; else s.printedTotal = v; }
      st.series.push(s); st.pending = [];
    }
    function setTotals(values) {
      if (!values.length) return;
      const lastV = values[values.length - 1];
      if (layout !== "rh") {
        const computed = Math.round(round1(st.series.reduce((a, s) => a + sTotal(s), 0)));
        const cand = values.find((v) => v === computed) ?? lastV;
        if (st.pt == null || cand === computed) st.pt = cand;
      } else if (values.length >= 2) { st.pt = values[values.length - 2]; st.pht = lastV; }
      else { const n = st.series.reduce((a, s) => a + s.shots.length, 0); if (lastV > 10 * n) st.pht = lastV; else st.pt = lastV; }
    }
    function commit(number, vals) {
      const made = makeShot(number, vals);
      if (!made) return false;
      st.pending.push(made.shot); st.last = number;
      if (made.sub != null) close([made.sub]);
      return true;
    }
    rows.forEach((row, i) => {
      if (row.isBlank) { close(); return; }
      readMetadata(row, r);
      if (isHeaderLine(row.raw)) return;
      const d = deepShot(row.raw);
      if (d) { st.deep.push(d); return; }
      if (row.isParen) { close(); return; }   // best shot of the series repeated in parentheses: ends the series
      if (SUBTOTAL_WORDS.some((w) => row.lower.includes(w))) { close(row.nums.map((n) => n.value)); return; }
      if (TOTAL_WORDS.some((w) => row.lower.includes(w))) { close(); setTotals(row.nums.map((n) => n.value)); return; }
      if (row.hasWords || !row.nums.length) return;
      const nums = row.nums;
      if (row.isSighter) {
        if (nums.length >= 2) { const m = makeShot(nums[0].value, nums.slice(1)); if (m) st.sighters.push(m.shot); }
        return;
      }
      // numbering starts again at 1 after a few shots: those were the sighters (Probe), e.g. "1 9 / 2 10 19", then "1 + 10 …"
      // Several restarts: groups numbered from 1 each (Obligatorisch on the form: 5 shots per group) - a new series.
      if (nums.length >= 2 && nums[0].value === 1 && st.last > 0) {
        const before = st.series.flatMap((x) => x.shots).concat(st.pending);
        if (restarts === 1 && !st.sighters.length && st.pt == null && before.length <= 5) { st.sighters = before; st.series = []; st.pending = []; st.pt = null; }
        else close();
        st.last = 0;
      }
      const expected0 = st.last + 1;
      // a stray character in front of the shot number (dash of the line above, dirt): "6 20 + 7 89"
      if (nums.length >= 3 && nums[0].value !== expected0 && nums[1].value === expected0 && nums[0].text.length === 1) nums.shift();
      const expected = expected0, first = nums[0];
      if (nums.length >= 2) {
        let index = null;
        if (first.value === expected) index = expected;
        else if (first.value > expected && first.value <= expected + 3) index = first.value;
        else if (first.value > expected && nums.length <= 4 && first.text.length > String(expected).length && first.text.startsWith(String(expected))) index = expected;
        if (index != null && commit(index, nums.slice(1))) {
          for (let k = expected; k < index; k++) st.missing.push(k);
          return;
        }
      }
      if (st.last === 0) return;
      if (layout === "rh" && first.value <= st.last && nums.length >= 3) { close(); return; }
      if (layout === "r" && nums.length <= 2 && first.value <= 10 && nextShotNumber(i, rows) === expected + 1 && commit(expected, nums)) return;
      close(); setTotals(nums.map((n) => n.value));
    });
    close();
    r.sighters = st.sighters; r.series = st.series; r.deepShots = st.deep; r.printedTotal = st.pt; r.printedHundredTotal = st.pht;
    r.warnings = st.warnings.concat(validate(r, st.missing));
    return r;
  }

  function validate(r, missing) {
    if (!r.series.length) return [{ type: "noShots" }];
    const w = [];
    if (missing.length) w.push({ type: "missing", numbers: missing });
    for (const s of r.series) {
      if (s.printedTotal != null && Math.abs(s.printedTotal - sComputed(s)) >= TOL) w.push({ type: "seriesTotal", series: s.number, printed: s.printedTotal, computed: sComputed(s) });
      const c = sComputedH(s);
      if (s.printedHundredTotal != null && c != null && s.printedHundredTotal !== c) w.push({ type: "seriesHundred", series: s.number, printed: s.printedHundredTotal, computed: c });
    }
    // the grand total disagrees but every series matches its own printed subtotal: the total was misread
    if (r.printedTotal != null && Math.abs(r.printedTotal - rComputed(r)) >= TOL && r.series.length > 1
        && r.series.every((s) => s.printedTotal != null && Math.abs(s.printedTotal - sComputed(s)) < TOL)) r.printedTotal = rComputed(r);
    if (r.printedTotal != null && Math.abs(r.printedTotal - rComputed(r)) >= TOL) w.push({ type: "grandTotal", printed: r.printedTotal, computed: rComputed(r) });
    if (r.printedHundredTotal != null) {
      const p = r.series.map(sHundred);
      if (!p.includes(null)) { const c = p.reduce((a, v) => a + v, 0); if (c !== r.printedHundredTotal) w.push({ type: "hundredTotal", printed: r.printedHundredTotal, computed: c }); }
    }
    return w;
  }

  // ---------- result parser ----------
  const DEFAULT_CFG = { scoringMode: "automatic", shotsPerSeries: null };
  function parseAll(lines, cfg = DEFAULT_CFG) {
    const sections = [[]];
    for (const l of lines) { if (isHeaderLine(l) && sections[sections.length - 1].length) sections.push([]); sections[sections.length - 1].push(l); }
    return sections.filter((s) => s.length).map((sec) => {
      if (cfg.scoringMode === "decimal" || (cfg.scoringMode === "automatic" && looksDecimal(sec))) return genericParse(sec, cfg);
      const s = stripParse(sec);
      return s.series.length ? s : genericParse(sec, cfg);
    }).filter((r) => r.series.length);
  }

  // ---------- header (sideways text) ----------
  function nameMatch(line) {
    const m = /^(\p{Lu}[\p{L}'’\-]+(?:\s+\p{Lu}[\p{L}'’\-]+){1,3})\s+((?:19|20)\d{2})(?:\s+([A-Z]{1,2}))?$/u.exec(line);
    return m ? { name: m[1], year: +m[2], category: m[3] || null } : null;
  }
  const WEAPON = ["sturmgewehr", "stgw", "karabiner", "standardgewehr", "freigewehr", "langgewehr", "ordonnanz", "pistole", "gewehr", "fass", "fusil", "pistolet"];
  const BOILER = ["einzelschütze", "kranzkarte", "naturalgabe", "auszeichnung", "auszahlung", "kopie", "original"];
  const isWeapon = (l) => WEAPON.some((w) => l.toLowerCase().includes(w));
  const isBoiler = (l) => BOILER.some((w) => l.toLowerCase().includes(w));
  const meaningful = (l) => { const n = [...l].filter(isLetter).length; return !(n < 4 || (l.length <= 7 && l === l.toUpperCase())); };
  function parseHeader(raw) {
    const h = { score: 0 };
    const lines = raw.map((l) => l.trim()).filter(Boolean);
    const joined = lines.join("\n");
    let m = /auszeichnung\s*ab\s*:?\s*(\d{2,4})/i.exec(joined); if (m) h.awardThreshold = +m[1];
    m = /auszahlung\s*ab\s*:?\s*(\d{2,4})/i.exec(joined); if (m) h.payoutThreshold = +m[1];
    const ni = lines.findIndex((l) => nameMatch(l));
    if (ni >= 0) {
      const nm = nameMatch(lines[ni]);
      Object.assign(h, { shooterName: nm.name, birthYear: nm.year, category: nm.category });
      const before = lines.slice(0, ni).filter(meaningful);
      if (before.length >= 2) h.organizer = before[before.length - 2];
      h.event = before[before.length - 1];
      const after = lines.slice(ni + 1).filter(meaningful);
      if (after[0] && !isWeapon(after[0])) h.club = after[0];
      const w = after.findIndex(isWeapon);
      if (w >= 0) { h.weapon = after[w]; if (w + 1 < after.length && !isBoiler(after[w + 1])) h.competition = after[w + 1]; }
    }
    h.score = ["shooterName", "club", "weapon", "event", "competition"].filter((k) => h[k]).length + (h.awardThreshold ? 1 : 0) + (h.payoutThreshold ? 1 : 0);
    return h;
  }
  function applyHeader(h, results) {
    for (const r of results) for (const k of ["shooterName", "birthYear", "category", "club", "weapon", "organizer", "event", "competition"]) if (r[k] == null && h[k] != null) r[k] = h[k];
    for (const [k, v] of [["awardThreshold", h.awardThreshold], ["payoutThreshold", h.payoutThreshold]]) {
      if (v == null) continue;
      const r = results.find((x) => rMax(x) > 0 && v <= rMax(x) && v >= rMax(x) / 2);
      if (r) r[k] = v;
    }
  }

  // ---------- layout: word boxes → lines (box: x, y, w, h normalized, y up from the bottom) ----------
  const median = (v) => { if (!v.length) return 0; const s = [...v].sort((a, b) => a - b); return s[Math.floor(s.length / 2)]; };
  function rowsOf(frags) {
    const sorted = [...frags].sort((a, b) => (b.y + b.h / 2) - (a.y + a.h / 2));
    const rows = [], mids = [];
    for (const f of sorted) {
      const mid = f.y + f.h / 2;
      if (rows.length && Math.abs(mids[mids.length - 1] - mid) < Math.min(rows[rows.length - 1][0].h, f.h) * 0.6) {
        const row = rows[rows.length - 1]; row.push(f);
        mids[mids.length - 1] = row.reduce((a, x) => a + x.y + x.h / 2, 0) / row.length;
      } else { rows.push([f]); mids.push(mid); }
    }
    const gaps = mids.slice(1).map((m, i) => mids[i] - m);
    const typical = median(gaps);
    const out = [];
    rows.forEach((row, i) => {
      if (i > 0 && typical > 0 && gaps[i - 1] > typical * 1.7) out.push("");
      out.push(row.sort((a, b) => a.x - b.x).map((f) => f.text).join(" "));
    });
    return out;
  }
  function isAnchor(w, words) {
    if (/^[Pp][gq]\d{0,3}$/.test(w.text)) return true;
    if (!/^[PF][9s]\d{0,3}$/.test(w.text)) return false;
    return words.some((v) => v.x > w.x + w.w && v.x - (w.x + w.w) < 0.35 && Math.abs((v.y + v.h / 2) - (w.y + w.h / 2)) < w.h && /^\d{1,3}\/\d{2,4}$/.test(v.text));
  }
  // Copies printed side by side repeat every row at the same horizontal distance: same text, same height.
  // Returns the x where the right copy starts, or null. Needs that pattern in at least half the rows,
  // so a value equal to its shot number ("9 > 9") in a single copy doesn't count.
  function copySplit(words) {
    if (words.length < 8) return null;
    const mid = (w) => w.y + w.h / 2;
    const pairs = [];
    for (const a of words) for (const b of words) {
      if (b === a || b.x <= a.x || a.text !== b.text || !/\d/.test(a.text)) continue;   // digits only: arrows and smudges repeat too
      if (Math.abs(mid(a) - mid(b)) < 0.5 * Math.min(a.h, b.h)) pairs.push([a, b, b.x - a.x]);
    }
    if (pairs.length < 4) return null;
    const bins = {};
    for (const [, , dx] of pairs) { const k = Math.round(dx / 0.01); for (const j of [k - 1, k, k + 1]) bins[j] = (bins[j] || 0) + 1; }
    const best = +Object.keys(bins).sort((x, y) => bins[y] - bins[x])[0];
    const near = pairs.filter((p) => Math.abs(p[2] - best * 0.01) <= 0.015);
    const dx = near.reduce((t, p) => t + p[2], 0) / near.length;
    const rows = rowsOf(words.map((w) => ({ ...w })));
    const rowMids = new Set(near.map((p) => Math.round(mid(p[0]) / Math.max(0.004, p[0].h * 0.8))));
    if (near.length < 4 || rowMids.size < Math.max(4, 0.5 * rows.length)) return null;
    const left = Math.min(...near.map((p) => p[0].x));
    // split in the middle of the widest empty strip just before where the right copy lines up
    const lo = left + dx - 0.15, hi = left + dx + 0.01;
    const iv = words.filter((w) => w.x + w.w > lo && w.x < hi).map((w) => [Math.max(lo, w.x), Math.min(hi, w.x + w.w)]).sort((p, q) => p[0] - q[0]);
    let bestGap = [hi - 0.01, 0], cur = lo;
    for (const [x0, x1] of iv) { if (x0 - cur > bestGap[1]) bestGap = [(cur + x0) / 2, x0 - cur]; cur = Math.max(cur, x1); }
    if (hi - cur > bestGap[1]) bestGap = [(cur + hi) / 2, hi - cur];
    return bestGap[0];
  }
  function columns(words) {
    if (!words.length) return [];
    const anchors = words.filter((w) => isAnchor(w, words)).map((w) => w.x).sort((a, b) => a - b);
    const clusters = [];
    for (const x of anchors) if (!clusters.length || x - clusters[clusters.length - 1] >= 0.06) clusters.push(x);
    let starts = [0];
    if (clusters.length > 1) { starts = clusters.map((x) => Math.max(0, x - 0.03)); starts[0] = 0; }
    let buckets = starts.map(() => []);
    for (const w of words) { let i = 0; starts.forEach((s, k) => { if (w.x >= s) i = k; }); buckets[i].push(w); }
    // a copy whose header was misread is still found by its repeating rows
    for (let guard = 0; guard < 6; guard++) {
      let changed = false;
      buckets = buckets.flatMap((b) => {
        const x = copySplit(b);
        if (x == null) return [b];
        const l = b.filter((w) => w.x < x), r = b.filter((w) => w.x >= x);
        // both sides must look like a printout: at least 3 rows with two or more numbers
        const full = (ws) => rowsOf(ws).filter((line) => (line.match(/\d+/g) || []).length >= 2).length >= 3;
        if (!l.length || !r.length || !full(l) || !full(r)) return [b];
        changed = true; return [l, r];
      });
      if (!changed) break;
    }
    return buckets.map(rowsOf).filter((r) => r.length);
  }

  // ---------- copies printed side by side ----------
  function lev(a, b) {
    const d = Array.from({ length: b.length + 1 }, (_, j) => j);
    for (let i = 1; i <= a.length; i++) {
      let p = d[0]; d[0] = i;
      for (let j = 1; j <= b.length; j++) { const t = d[j]; d[j] = Math.min(d[j] + 1, d[j - 1] + 1, p + (a[i - 1] !== b[j - 1] ? 1 : 0)); p = t; }
    }
    return d[b.length];
  }
  // two readings of the same printout: same target (a misread character or two allowed), or mostly the same shots
  function sameCopy(a, b) {
    if (a.targetLabel && b.targetLabel) {
      if (a.targetLabel === b.targetLabel) return true;
      // two cleanly read labels that differ are two runs ("23/0004", "23/0005"); only a malformed one may be a misreading
      const clean = (l) => /^\d{1,2}\/\d{4}$/.test(l);
      if (clean(a.targetLabel) && clean(b.targetLabel)) return false;
      return lev(a.targetLabel, b.targetLabel) <= 2 && Math.abs(rShots(a).length - rShots(b).length) <= 1;
    }
    const x = rShots(a).map((s) => s.value), y = rShots(b).map((s) => s.value);
    if (x.length < 4 || x.length !== y.length) return false;
    return x.filter((v, i) => v === y[i]).length >= 0.7 * x.length;
  }
  // neither copy adds up: take each series from the copy whose series matches its printed subtotal
  function combineCopies(copies) {
    const base = copies[0];
    if (!copies.every((c) => c.series.length === base.series.length && c.series.every((s, i) => s.shots.length === base.series[i].shots.length))) return null;
    const r = { ...base, series: base.series.map((s, i) => {
      const ok = copies.map((c) => c.series[i]).find((x) => sConsistent(x) === true);
      return ok || s;
    }), warnings: [] };
    r.printedTotal = copies.map((c) => c.printedTotal).find((t) => t != null && Math.abs(t - rComputed(r)) < TOL) ?? base.printedTotal;
    r.warnings = validate(r, []);
    return rVerified(r) ? r : null;
  }
  function mergeCopies(results) {
    const groups = [];
    for (const r of results) {
      const g = groups.find((gr) => gr.some((c) => sameCopy(c, r) && (c.program == null || r.program == null || c.program === r.program || lev(c.program, r.program) <= 1)));
      if (g) g.push(r); else groups.push([r]);
    }
    return groups.map((copies) => {
      if (copies.length === 1) return copies[0];
      const rank = (r) => [rVerified(r) ? 0 : 1, r.warnings.length, -rShots(r).length];
      const cmp = (a, b) => { const x = rank(a), y = rank(b); for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] - y[i]; return 0; };
      let best = [...copies].sort(cmp)[0];
      if (!rVerified(best)) best = combineCopies(copies) || best;
      // the label as printed: the form the copies agree on, else the one shaped like "21/0004"
      const labels = copies.map((c) => c.targetLabel).filter(Boolean);
      const shaped = labels.filter((l) => /^\d{1,3}\/\d{1,4}$/.test(l) && !/^\d{3}\/\d{4}$/.test(l));
      const common = labels.find((l, i) => labels.indexOf(l) !== i);
      best.targetLabel = common || shaped.find((l) => /^\d{2}\/\d{4}$/.test(l)) || shaped[0] || best.targetLabel;
      const agree = new Set(copies.map((c) => rShots(c).map((s) => s.value).join(","))).size === 1;
      best.copiesRead = copies.length; best.copiesAgree = agree;
      if (!agree && !rVerified(best)) best.warnings.push({ type: "copies" });
      if (!best.deepShots.length) { const o = copies.reduce((a, c) => (c.deepShots.length > a.deepShots.length ? c : a)); best.deepShots = o.deepShots; }
      return best;
    });
  }

  // Results from reading the photo turned by 180° (a strip lying upside down): only printouts with a cleanly read
  // run number and at least 4 shots count (an upright strip read upside down gives junk), merged with the first pass.
  function addTurned(results, turned) {
    const ok = turned.filter((r) => r.targetLabel && /^\d{1,3}\/\d{4}$/.test(r.targetLabel) && rShots(r).length >= 4);
    return ok.length ? mergeCopies(results.concat(ok)) : results;
  }

  const api = { parseAll, parseHeader, applyHeader, columns, rowsOf, mergeCopies, addTurned, isHeaderLine,
    total: rTotal, hundredTotal: rHundred, verified: rVerified, shots: rShots, seriesTotal: sTotal, seriesHundred: sHundred, fmt };
  if (typeof module !== "undefined" && module.exports) module.exports = api; else root.ScanParser = api;
})(typeof self !== "undefined" ? self : this);
