#!/usr/bin/env python3
"""Cleanup Lab drift analysis — is the degradation conversation length or transcript position?

    python3 tools/cleanup_lab_drift.py runs.csv units.csv [--plots OUTDIR]

`maxTurns != 0` bounds the conversation, so a whole-transcript cumulative leftover/raw ratio is
valid. `maxTurns = 0` resets the conversation at every capacity rebuild, so a whole-transcript
cumulative is an average over sawtooth cycles and says nothing; for it this folds all conversations
onto a "turns in conversation" axis and also splits them by transcript third, to show whether the
within-conversation decay deepens over the transcript (a hidden factor) or repeats.

All counts are on overlap-stripped new speech (see cleanup_lab_analysis.py / D-100), normalised by
the raw ASR count in the same bucket. `--plots` writes fig1/fig2/fig3 when matplotlib is installed.
"""
import csv
import sys

from cleanup_lab_analysis import count_fillers, group_units, new_region

BINS = [(0, 2), (3, 5), (6, 8), (9, 11), (12, 14), (15, 17), (18, 20), (21, 40)]
B2 = [(0, 2), (3, 9), (10, 40)]


def label(lo, hi):
    return f"{lo}-{hi}" if hi < 40 else f"{lo}+"


def build(run, urs):
    """Per-unit raw/leftover filler counts (overlap stripped), ordered."""
    out = []
    for u in sorted(urs, key=lambda r: int(r["unit_index"])):
        raw, nc = new_region(u["raw_unit"], u["model_input"], u["cleaned_unit"])
        out.append({
            "i": int(u["unit_index"]),
            "turn": int(u["turns_in_context"]),
            "raw": count_fillers(raw),
            "left": count_fillers(nc),
            "conv_end": u["rebuilt_after"] == "true",
        })
    return out


def convs_of(units):
    out, cur = [], []
    for u in units:
        cur.append(u)
        if u["conv_end"]:
            out.append(cur); cur = []
    if cur:
        out.append(cur)
    return out


def removal(units, lo, hi):
    w = [u for u in units if lo <= u["turn"] <= hi]
    a = sum(x["raw"] for x in w); b = sum(x["left"] for x in w)
    return (100 * (a - b) / a if a else float("nan")), a


def main(runs_path, units_path):
    runs = [r for r in csv.DictReader(open(runs_path, newline="")) if r["fixture_id"] == "d09_long_dictation"]
    units = [u for u in csv.DictReader(open(units_path, newline="")) if u["fixture_id"] == "d09_long_dictation"]
    groups = group_units(units)
    by_ts = {g["ts"]: g["rows"] for g in groups}

    configs = []  # (label, max_turns, units)
    for run in runs:
        urs = by_ts.get(run["timestamp"])
        if not urs:
            continue
        configs.append((f"{run['llm_model']} turn{run['max_turns']}", run["max_turns"], build(run, urs)))

    print("Whole-transcript cumulative leftover/raw ratio (valid only for capped runs):")
    for name, mt, us in configs:
        a = b = 0
        trace = []
        for u in us:
            a += u["raw"]; b += u["left"]
            if u["i"] % 100 == 0 and a:
                trace.append(f"u{u['i']}:{100*b/a:.0f}%")
        if a:
            print(f"  {name:26} end={100*b/a:4.0f}%  {(' '.join(trace)) if mt != '0' else '(sawtooth-average, see fold)'}")

    print("\nFolded removal vs turns-in-conversation (maxTurns=0 only; capped runs are ~flat):")
    print("  " + f"{'turn bucket':>13}" + "".join(f"{n.split(' turn')[0]}-t{mt:>2}".rjust(10) for n, mt, _ in configs if mt == '0'))
    for lo, hi in BINS:
        cells = []
        for name, mt, us in configs:
            if mt != "0":
                continue
            r, n = removal(us, lo, hi)
            cells.append(f"{r:>5.0f}%({n})".rjust(10) if n else "n/a".rjust(10))
        print("  " + f"{label(lo, hi):>13}" + "".join(cells))

    print("\nDecay split by transcript third (maxTurns=0): same shape in each third = no hidden factor")
    for name, mt, us in configs:
        if mt != "0":
            continue
        cs = convs_of(us); third = max(1, len(cs) // 3)
        for gname, grp in (("early", cs[:third]), ("middle", cs[third:2 * third]), ("late", cs[2 * third:])):
            flat = [u for c in grp for u in c]
            cells = []
            for lo, hi in B2:
                r, n = removal(flat, lo, hi)
                cells.append(f"{r:>5.0f}%({n})".rjust(12))
            print(f"  {name:20} {gname:>6} ({len(grp):>2} conv) | " + " ".join(cells))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2])
