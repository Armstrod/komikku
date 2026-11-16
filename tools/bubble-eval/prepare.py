#!/usr/bin/env python3
"""Unpacks bubble debug zips (exported from debug mode) into a work folder and writes
pages.txt (for Eval.kt) and frames.txt (for Frames.kt).

usage: prepare.py WORKDIR log1.zip [log2.zip ...]
"""
import json, os, sys, zipfile

work = sys.argv[1]
os.makedirs(work, exist_ok=True)
pages, frames = [], []
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
        for b in p["final"]:
            if "frame" in b:
                frames.append(f"{label}|{img}|{rtl}|{final}|{box(b)}|{','.join(str(v) for v in b['frame'])}")
open(os.path.join(work, "pages.txt"), "w").write("\n".join(pages) + "\n")
open(os.path.join(work, "frames.txt"), "w").write("\n".join(frames) + "\n")
print(f"{len(pages)} pages, {len(frames)} hand framings -> {work}")
