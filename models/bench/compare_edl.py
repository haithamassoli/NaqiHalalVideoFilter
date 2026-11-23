#!/usr/bin/env python3
"""Per-frame covered area of two Android EDLs (files/bench/last-edl.json), evaluated like Edl.kt.

Prints frames where the candidate covers pixels the reference covered and it does not. Masks are
rasterized like CensorEffect's mask texture (grown one cell), without the outward feather.
"""
import argparse
import base64
import json

import numpy as np

GW, GH = 90, 160  # upright evaluation grid, 1/8 of a 720x1280 frame


def rect_mask(r):
    m = np.zeros((GH, GW), bool)
    x0, y0 = int(np.floor(r[0] * GW)), int(np.floor(r[1] * GH))
    x1, y1 = int(np.ceil(r[2] * GW)), int(np.ceil(r[3] * GH))
    m[max(0, y0):min(GH, y1), max(0, x0):min(GW, x1)] = True
    return m


def body_mask(entry):
    left, top, right, bottom, w, h, bits = entry
    raw = np.unpackbits(np.frombuffer(base64.b64decode(bits), np.uint8), bitorder="little")[: w * h]
    cells = raw.reshape(h, w).astype(bool)
    grown = np.zeros((h + 2, w + 2), bool)
    for dy in range(3):
        for dx in range(3):
            grown[dy:dy + h, dx:dx + w] |= cells
    cw, ch = (right - left) / w, (bottom - top) / h
    ys = (np.arange(GH) + 0.5) / GH
    xs = (np.arange(GW) + 0.5) / GW
    iy = np.floor((ys - top) / ch).astype(int) + 1
    ix = np.floor((xs - left) / cw).astype(int) + 1
    out = np.zeros((GH, GW), bool)
    vy, vx = (iy >= 0) & (iy < h + 2), (ix >= 0) & (ix < w + 2)
    out[np.ix_(vy, vx)] = grown[np.ix_(iy[vy], ix[vx])]
    return out


def coverage(edl, t):
    if any(a <= t <= b for a, b in edl["censorIntervalsMs"]):
        return np.ones((GH, GW), bool)
    out = np.zeros((GH, GW), bool)
    for tr in edl["faceTracks"]:
        if not tr["startMs"] <= t <= tr["endMs"]:
            continue
        kf = tr["keyframes"]
        masks = tr.get("masks")
        times = [k[0] for k in kf]
        i = int(np.searchsorted(times, t, side="right")) - 1
        if i < 0 or i == len(kf) - 1 or kf[i][0] == t:
            j = max(0, i)
            if masks and masks[j]:
                out |= body_mask(masks[j])
            else:
                out |= rect_mask(kf[j][1:])
            continue
        a, b = kf[i], kf[i + 1]
        if masks and masks[i] and masks[i + 1]:
            out |= body_mask(masks[i]) | body_mask(masks[i + 1])
        elif tr.get("union"):
            out |= rect_mask(a[1:]) | rect_mask(b[1:])
        else:
            f = (t - a[0]) / (b[0] - a[0])
            out |= rect_mask([a[k] + (b[k] - a[k]) * f for k in range(1, 5)])
    return out


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("reference")
    p.add_argument("candidate")
    p.add_argument("--frames", type=int, default=1598)
    p.add_argument("--fps", type=float, default=30)
    p.add_argument("--json", help="write per-frame rows here")
    args = p.parse_args()
    ref, cand = (json.load(open(f)) for f in (args.reference, args.candidate))
    rows, less = [], []
    for i in range(args.frames):
        t = int(i * 1000 / args.fps)
        r, c = coverage(ref, t), coverage(cand, t)
        lost = float((r & ~c).mean())
        rows.append({"frame": i, "ms": t, "ref": float(r.mean()), "cand": float(c.mean()), "lost": lost})
        if lost > 0:
            less.append(rows[-1])
    ra = np.mean([x["ref"] for x in rows])
    ca = np.mean([x["cand"] for x in rows])
    print(f"mean covered area: reference {ra:.4f}  candidate {ca:.4f}")
    print(f"frames where candidate leaves reference-covered pixels uncovered: {len(less)}")
    for x in sorted(less, key=lambda x: -x["lost"])[:40]:
        print(f"  frame {x['frame']:4d} {x['ms']:6d} ms  ref {x['ref']:.3f} cand {x['cand']:.3f} lost {x['lost']:.4f}")
    if args.json:
        json.dump(rows, open(args.json, "w"))


if __name__ == "__main__":
    main()
