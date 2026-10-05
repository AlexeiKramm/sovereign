#!/usr/bin/env python3
"""Cleanup Lab off-device analysis.

    python3 tools/cleanup_lab_analysis.py runs.csv units.csv

Re-implements the lab's metric rules so the on-device numbers can be verified, then reports the
sweep comparison the lab exists for:

  * per-run aggregates (verified against the stored raw/cleaned text),
  * end-to-end content deleted/added (raw transcript -> committed transcript),
  * filler removal vs conversation position, with the seam overlap stripped.

Why the stripping: each cleanup pass is handed `N sentences of carried context + the new unit`
(CleanupPipeline.kt), and `LabUnitResult.cleanedUnit` repeats that overlap. Counting fillers on the
whole pass input counts the overlap again in the next pass. The aggregate (runs.csv) is computed
from the de-duplicated committed transcript and is correct; the *per-unit* curve is not, so this
script isolates the new unit before measuring it. See DECISIONS.md D-100.

The lab itself is the recorder. All numbers that drive a model/maxTurns decision are produced here.
"""
import csv
import difflib
import re
import sys
from collections import defaultdict
from datetime import datetime

FILLERS = [("um",), ("uh",), ("you", "know"), ("i", "mean"), ("kind", "of"), ("like",)]
FILLER_WORDS = {w for f in FILLERS for w in f}
NUMBER_WORDS = {
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
    "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    "hundred", "thousand", "million",
}
STOPWORDS = {
    "a", "an", "the", "and", "or", "but", "if", "then", "so", "of", "to", "in", "on", "at", "for",
    "with", "from", "by", "as", "is", "are", "was", "were", "be", "been", "am", "i", "you", "he",
    "she", "it", "we", "they", "me", "him", "her", "us", "them", "my", "your", "his", "its", "our",
    "their", "this", "that", "these", "those", "there", "here", "not", "no", "yes", "do", "does",
    "did", "done", "have", "has", "had", "will", "would", "can", "could", "should", "may", "might",
    "must", "just", "very", "really", "too", "also", "well", "right", "yeah", "okay", "ok",
}
TOKEN_RE = re.compile(r"[A-Za-z0-9']+")


def toks(text):
    return [t.lower() for t in TOKEN_RE.findall(text or "")]


def count_fillers(ts):
    n = 0
    for f in FILLERS:
        for i in range(0, len(ts) - len(f) + 1):
            if tuple(ts[i:i + len(f)]) == f:
                n += 1
    return n


def count_stutters(ts):
    return sum(1 for i in range(1, len(ts)) if ts[i] == ts[i - 1])


def count_digits(text):
    return len(re.findall(r"\d+", text or ""))


def new_region(raw_unit, model_input, cleaned_unit):
    """The cleaned output restricted to the new unit; the seam overlap is excluded."""
    raw, mi, cu = toks(raw_unit), toks(model_input), toks(cleaned_unit)
    if not raw or len(mi) <= len(raw) or mi[-len(raw):] != raw:
        return raw, cu
    boundary = len(mi) - len(raw)
    sm = difflib.SequenceMatcher(a=mi, b=cu, autojunk=False)
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if i1 <= boundary <= i2:
            return raw, (cu[j1 + (boundary - i1):] if tag == "equal" else cu[j1:])
    return raw, cu


def verify(run, units):
    """Recompute a run's aggregate from its stored text and return any mismatch."""
    rw, cw = toks(run["raw_text"]), toks(run["cleaned_text"])
    rawf, leftf = count_fillers(rw), count_fillers(cw)
    checks = {
        "raw_words": (int(run["raw_words"]), len(rw)),
        "cleaned_words": (int(run["cleaned_words"]), len(cw)),
        "raw_fillers": (int(run["raw_fillers"]), rawf),
        "leftover_fillers": (int(run["leftover_fillers"]), leftf),
        "raw_stutters": (int(run["raw_stutters"]), count_stutters(rw)),
        "leftover_stutters": (int(run["leftover_stutters"]), count_stutters(cw)),
        "digits_cleaned": (int(run["digits_cleaned"]), count_digits(run["cleaned_text"])),
    }
    bad = {k: v for k, v in checks.items() if v[0] != v[1]}
    fr = (rawf - leftf) / rawf if rawf else 1.0
    if abs(fr - float(run["filler_removal"])) > 5e-4:
        bad["filler_removal"] = (run["filler_removal"], round(fr, 4))
    return bad


def group_units(units):
    """Group unit rows into runs; merge same-config groups written within 5 s (second straddle)."""
    def t(s):
        return datetime.strptime(s, "%Y-%m-%dT%H:%M:%S").timestamp()
    groups = []
    for u in sorted(units, key=lambda r: (r["timestamp"], int(r["unit_index"]))):
        cfg = (u["fixture_id"], u["asr_model"], u["llm_model"], u["max_turns"], u["healing"], u["profile"])
        if groups and groups[-1]["cfg"] == cfg and abs(t(u["timestamp"]) - t(groups[-1]["ts"])) <= 5:
            groups[-1]["rows"].append(u)
        else:
            groups.append({"cfg": cfg, "ts": u["timestamp"], "rows": [u]})
    return groups


def e2e(run):
    """End-to-end deleted/added words and content-only deleted, from raw -> committed text."""
    rw, cw = toks(run["raw_text"]), toks(run["cleaned_text"])
    sm = difflib.SequenceMatcher(a=rw, b=cw, autojunk=False)
    rem, add = [], []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag in ("delete", "replace"):
            rem += rw[i1:i2]
        if tag in ("insert", "replace"):
            add += cw[j1:j2]
    content = [w for w in rem if w not in FILLER_WORDS and w not in STOPWORDS]
    return len(rem), len(add), len(content)


def main(runs_path, units_path):
    runs = list(csv.DictReader(open(runs_path, newline="")))
    units = list(csv.DictReader(open(units_path, newline="")))
    groups = group_units(units)
    by_ts = {g["ts"]: g["rows"] for g in groups}

    def units_for(run):
        if run["timestamp"] in by_ts:
            return by_ts[run["timestamp"]]
        cfg = (run["fixture_id"], run["asr_model"], run["llm_model"], run["max_turns"],
               run["healing"], run["profile"])
        cand = [g for g in groups if g["cfg"] == cfg]
        if not cand:
            return []
        rt = datetime.strptime(run["timestamp"], "%Y-%m-%dT%H:%M:%S").timestamp()
        best = min(cand, key=lambda g: abs(datetime.strptime(g["ts"], "%Y-%m-%dT%H:%M:%S").timestamp() - rt))
        return best["rows"]

    print("=" * 108)
    print("RUNS  (aggregate from runs.csv; '%' columns verified against the stored raw/cleaned text)")
    print("=" * 108)
    print(f"{'timestamp':19} {'fixture':22} {'llm':11} {'mt':>2} {'heal':>4} {'units':>5} {'fail':>4} "
          f"{'rawF':>5} {'leftF':>5} {'rem%':>6} {'new%':>6} {'e2eDel':>7} {'e2eAdd':>7} "
          f"{'content':>7} {'clW':>5} {'wall':>5} {'ms/u':>5}  verify")
    for run in runs:
        urs = units_for(run)
        if not urs:
            continue
        bad = verify(run, urs)
        a = b = 0
        for u in urs:
            raw, nc = new_region(u["raw_unit"], u["model_input"], u["cleaned_unit"])
            a += count_fillers(raw)
            b += count_fillers(nc)
        dele, add, content = e2e(run)
        ms_u = int(run["llm_active_ms"]) / max(1, len(urs))
        newp = 100 * (a - b) / a if a else float("nan")
        print(f"{run['timestamp']:19} {run['fixture_id'][:22]:22} {run['llm_model']:11} "
              f"{run['max_turns']:>2} {run['healing']:>4} {run['units']:>5} {run['failed_units']:>4} "
              f"{run['raw_fillers']:>5} {run['leftover_fillers']:>5} "
              f"{float(run['filler_removal'])*100:>6.1f} {newp:>6.1f} {dele:>7} {add:>7} "
              f"{content:>7} {run['cleaned_words']:>5} {int(run['wall_ms'])//1000:>5} {ms_u:>5.0f}  "
              f"{'ok' if not bad else 'MISMATCH ' + str(bad)}")

    print("\nnew-speech filler removal by turn-in-conversation bucket (overlap stripped):")
    print(f"{'config':24}{'turn0-2':>10}{'turn3-9':>10}{'turn10+':>10}")
    for run in runs:
        urs = units_for(run)
        if not urs:
            continue
        buck = {"0-2": [0, 0], "3-9": [0, 0], "10+": [0, 0]}
        for u in urs:
            t = int(u["turns_in_context"])
            k = "0-2" if t <= 2 else "3-9" if t <= 9 else "10+"
            raw, nc = new_region(u["raw_unit"], u["model_input"], u["cleaned_unit"])
            buck[k][0] += count_fillers(raw)
            buck[k][1] += count_fillers(nc)

        def pc(k):
            x, y = buck[k]
            return f"{100*(x-y)/x:.0f}%" if x else "n/a"

        cfg = f"{run['llm_model']} turn{run['max_turns']}"
        print(f"{cfg:24}{pc('0-2'):>10}{pc('3-9'):>10}{pc('10+'):>10}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2])
