#!/usr/bin/env python3
"""Unpacks bubble debug zips (exported from debug mode) into a work folder and writes
pages.txt (for Eval.kt), frames.txt (for Frames.kt) and panels.txt (for Panels.kt: panels
drawn by hand or saved in panel order, from page-fix reports).

usage: prepare.py WORKDIR log1.zip [log2.zip ...]
"""
import json, os, sys, zipfile

work = sys.argv[1]
os.makedirs(work, exist_ok=True)
pages, frames, panels = [], [], []
for n, z in enumerate(sys.argv[2:]):
    dest = os.path.join(work, f"log{n + 1}")
    with zipfile.ZipFile(z) as f:
        f.extractall(dest)
    log = json.load(open(os.path.join(dest, "log.json")))
    for p in log["pages"]:
        label = f"L{n + 1}:" + p["page"].split("| ")[-1]
        img = os.path.join(dest, p["images"][0])
        rtl = int(p["rightToLeft"])
        box = lambda b: ",".join(str(v) for v in b["bounds"])
        final = ";".join(box(b) for b in p["final"])
        found = ";".join(box(b) for b in p["found"])
        pages.append(f"{label}|{img}|{rtl}|{final}|{found}")
        # Panels as corrected: saved in order, else drawn by hand. Panel stops aren't bubbles.
        drawn = [q["bounds"] for q in p.get("panels", [])]
        for q in p.get("panelsFinal", []):
            xs, ys = q["points"][0::2], q["points"][1::2]
            drawn.append([min(xs), min(ys), max(xs), max(ys)])
        if drawn:
            bubbles = ";".join(box(b) for b in p["final"] if not (b["manual"] and b.get("panel") == b["bounds"]))
            hand = ";".join(",".join(str(v) for v in d) for d in drawn)
            panels.append(f"{label}|{img}|{str(bool(rtl)).lower()}|{bubbles}|{hand}")
        for b in p["final"]:
            if "frame" in b:
                frames.append(f"{label}|{img}|{rtl}|{final}|{box(b)}|{','.join(str(v) for v in b['frame'])}")
open(os.path.join(work, "pages.txt"), "w").write("\n".join(pages) + "\n")
open(os.path.join(work, "frames.txt"), "w").write("\n".join(frames) + "\n")
open(os.path.join(work, "panels.txt"), "w").write("\n".join(panels) + "\n")
print(f"{len(pages)} pages, {len(frames)} hand framings, {len(panels)} pages with panels -> {work}")
