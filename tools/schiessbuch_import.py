#!/usr/bin/env python3
"""
Adds the shooting sessions from a CHSchiessbuch email to an Excel file.

The email is the one the app sends with "E-Mail" (German or English, one or more
sessions). Every series becomes one row; the user given with --user is the first column.
The Excel file is created with a header row when it doesn't exist yet. Rows that are
already in the file (same user, date, description, series and shots) are skipped, so the
same email can be imported twice without duplicates.

Usage:
    python3 schiessbuch_import.py --user "Massimo" --excel resultate.xlsx email.txt
    python3 schiessbuch_import.py --user "Massimo" --excel resultate.xlsx mail.eml
    pbpaste | python3 schiessbuch_import.py --user "Massimo" --excel resultate.xlsx   (macOS clipboard)

Needs:  pip install openpyxl
"""
import argparse
import datetime as dt
import email
import email.policy
import os
import re
import sys

try:
    from openpyxl import Workbook, load_workbook
    from openpyxl.styles import Font
    from openpyxl.utils import get_column_letter
except ImportError:
    sys.exit("openpyxl fehlt. Installieren mit:  pip install openpyxl")

HEADER = ["Benutzer", "Datum", "Kategorie", "Disziplin", "Beschreibung", "Schiessstand", "Waffe", "Bedingungen",
          "Serie", "Format", "Schüsse", "Resultat", "Maximum", "Resultat 10 Schuss A10", "Innenzehner (X)",
          "Tiefschuss", "Probe (zählt nicht)", "Notizen", "Fotos", "Importiert am"]
WIDTHS = [14, 11, 13, 16, 26, 14, 12, 18, 6, 34, 60, 9, 9, 12, 10, 10, 10, 30, 6, 17]

MONTHS = {"jan": 1, "feb": 2, "mär": 3, "mar": 3, "apr": 4, "mai": 5, "may": 5, "jun": 6, "jul": 7,
          "aug": 8, "sep": 9, "okt": 10, "oct": 10, "nov": 11, "dez": 12, "dec": 12}
CATEGORIES = {"training", "meisterschaft", "andere", "championship", "other"}
# Field lines of a session, German and English
FIELDS = {
    "disziplin": "discipline", "discipline": "discipline",
    "beschreibung": "description", "description": "description",
    "schiessstand": "range", "range": "range",
    "waffe": "weapon", "weapon": "weapon",
    "bedingungen": "conditions", "conditions": "conditions",
    "notizen": "notes", "notes": "notes",
    "scheibenfotos": "photos", "target photos": "photos",
}
DATE_LINE = re.compile(r"^(?P<date>.+?\d{4})\s+[—–-]\s+(?P<cat>.+)$")
SERIES_LINE = re.compile(r"^\s*(?:Serie|Series)\s+(?P<n>\d+)\s+\[(?P<fmt>.*?)\]:\s*(?P<rest>.*)$")


def parse_date(text):
    """'Mi., 4. Nov. 2026', 'Wed 4 Nov 2026', 'Wed, 4 Nov 2026', '04.11.2026' or '2026-11-04' → date."""
    t = text.strip()
    m = re.search(r"(\d{4})-(\d{1,2})-(\d{1,2})", t)
    if m:
        return dt.date(int(m[1]), int(m[2]), int(m[3]))
    m = re.search(r"(\d{1,2})[./](\d{1,2})[./](\d{4})", t)
    if m:
        return dt.date(int(m[3]), int(m[2]), int(m[1]))
    m = re.search(r"(\d{1,2})\.?\s+([A-Za-zÄÖÜäöü]+)\.?\s+(\d{4})", t)
    if m:
        mon = MONTHS.get(m[2].lower()[:3].replace("ae", "ä"))
        if mon:
            return dt.date(int(m[3]), mon, int(m[1]))
    return None


def number(text):
    try:
        v = float(text.replace("'", "").replace(",", "."))
        return int(v) if v == int(v) else v
    except ValueError:
        return None


def parse_series(line):
    m = SERIES_LINE.match(line)
    if not m:
        return None
    rest = m["rest"]
    left, sep, right = rest.rpartition(" = ")
    if not sep:
        return None
    score = re.match(r"\s*([\d.,']+)\s*/\s*(\d+)(.*)$", right)
    if not score:
        return None
    tail = score[3]
    xs = re.search(r"(\d+)\s*X\b", tail)
    tief = re.search(r"Tiefschuss\s+(\d+)", tail)
    prac = re.search(r"\((?:Probe|practice)\s+(\d+)", tail)
    total, mx = number(score[1]), int(score[2])
    shots = left.strip()
    if shots in ("—", "-"):
        shots = ""
    return {
        "series": int(m["n"]), "format": m["fmt"], "shots": shots,
        "score": total, "max": mx,
        "norm": round(total / mx * 100, 1) if (total is not None and mx) else None,
        "x": int(xs[1]) if xs else 0,
        "tief": int(tief[1]) if tief else None,
        "practice": int(prac[1]) if prac else None,
    }


def parse_email(text):
    """Returns a list of sessions, each with its fields and series."""
    sessions, cur = [], None
    for raw in text.replace("\r\n", "\n").split("\n"):
        line = raw.rstrip()
        if not line.strip():
            continue
        d = DATE_LINE.match(line.strip())
        if d and d["cat"].strip().lower() in CATEGORIES and parse_date(d["date"]):
            cur = {"date": parse_date(d["date"]), "category": d["cat"].strip(), "series": []}
            sessions.append(cur)
            continue
        if cur is None:
            continue
        s = parse_series(line)
        if s:
            cur["series"].append(s)
            continue
        key, sep, value = line.strip().partition(": ")
        if sep and key.lower() in FIELDS:
            cur[FIELDS[key.lower()]] = value.strip()
    return [s for s in sessions if s["series"]]


def read_input(paths):
    """Text of the given files (.txt or .eml), or of standard input."""
    if not paths:
        return [sys.stdin.read()]
    texts = []
    for p in paths:
        with open(p, "rb") as f:
            data = f.read()
        if p.lower().endswith(".eml"):
            msg = email.message_from_bytes(data, policy=email.policy.default)
            part = msg.get_body(preferencelist=("plain",))
            texts.append(part.get_content() if part else "")
        else:
            texts.append(data.decode("utf-8", errors="replace"))
    return texts


def open_sheet(path):
    if os.path.exists(path):
        wb = load_workbook(path)
        return wb, wb.active, False
    wb = Workbook()
    ws = wb.active
    ws.title = "Resultate"
    ws.append(HEADER)
    for c in ws[1]:
        c.font = Font(bold=True)
    for i, w in enumerate(WIDTHS, 1):
        ws.column_dimensions[get_column_letter(i)].width = w
    ws.freeze_panes = "B2"
    return wb, ws, True


def row_key(user, date, desc, n, shots, score):
    d = date.isoformat() if isinstance(date, (dt.date, dt.datetime)) else str(date or "")
    return (str(user or "").strip().lower(), d[:10], str(desc or ""), int(n or 0), str(shots or ""), str(score))


def main():
    ap = argparse.ArgumentParser(description="CHSchiessbuch-E-Mail in eine Excel-Datei übernehmen (eine Zeile pro Serie).")
    ap.add_argument("--user", "-u", required=True, help="Name des Schützen; steht in der ersten Spalte")
    ap.add_argument("--excel", "-x", default="schiessbuch.xlsx", help="Excel-Datei (wird erstellt, wenn sie fehlt)")
    ap.add_argument("--allow-duplicates", action="store_true", help="auch bereits vorhandene Zeilen nochmals eintragen")
    ap.add_argument("files", nargs="*", help="E-Mail als .txt oder .eml; ohne Angabe wird der Text von der Eingabe gelesen")
    a = ap.parse_args()

    sessions = [s for t in read_input(a.files) for s in parse_email(t)]
    if not sessions:
        sys.exit("Keine Übung gefunden. Ist es der Text einer E-Mail aus CHSchiessbuch?")

    wb, ws, created = open_sheet(a.excel)
    existing = set()
    for r in ws.iter_rows(min_row=2, values_only=True):
        if r and r[0] is not None:
            existing.add(row_key(r[0], r[1], r[4], r[8], r[10], r[11]))

    now = dt.datetime.now().replace(microsecond=0)
    added = skipped = 0
    for s in sessions:
        for r in s["series"]:
            key = row_key(a.user, s["date"], s.get("description", ""), r["series"], r["shots"], r["score"])
            if key in existing and not a.allow_duplicates:
                skipped += 1
                continue
            existing.add(key)
            ws.append([a.user, s["date"], s["category"], s.get("discipline", ""), s.get("description", ""),
                       s.get("range", ""), s.get("weapon", ""), s.get("conditions", ""),
                       r["series"], r["format"], r["shots"], r["score"], r["max"], r["norm"], r["x"],
                       r["tief"], r["practice"], s.get("notes", ""), number(s.get("photos", "")) or None, now])
            ws.cell(ws.max_row, 2).number_format = "DD.MM.YYYY"
            ws.cell(ws.max_row, len(HEADER)).number_format = "DD.MM.YYYY HH:MM"
            added += 1
    wb.save(a.excel)
    print(f"{'Neue Datei ' if created else ''}{a.excel}: {added} Zeilen hinzugefügt"
          f"{f', {skipped} schon vorhanden' if skipped else ''} ({len(sessions)} Übung{'en' if len(sessions) != 1 else ''}).")


if __name__ == "__main__":
    main()
