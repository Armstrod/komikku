# Bubble detection evaluation

Replays bubble debug logs - the zips exported from the reader's bubble debug mode - through
`BubbleDetect` on the JVM, with no Android SDK: the greyscale page images in the log are the
ink maps detection ran on, and the corrected bubbles in it are the ground truth.

```sh
tools/bubble-eval/prepare.py /tmp/be ~/logs/bubble-debug-*.zip
tools/bubble-eval/run.sh /tmp/be          # recall, extra finds, reading order, time per page
tools/bubble-eval/run.sh /tmp/be -v       # per page: missed bubbles and computed order
tools/bubble-eval/run.sh /tmp/be frames   # framing rules vs frames set by hand
tools/bubble-eval/run.sh /tmp/pd panels -o /tmp/pd/out   # panel shapes vs panels drawn by hand
```

`panels` reads `panels.txt` (`label|image|rtl|bubble boxes|hand panels`, normalised, boxes
`;`-separated) made from page-fix reports, and prints, for each panel drawn by hand, the
found panel overlapping it most (IoU of bounds). `-o` writes each page's panel polygons.

Text recognition (ML Kit) doesn't run here, so "extra" counts candidates the app would then
confirm or drop by reading the page; recall and order are what the app gets before that.
Reading order is scored on the corrected boxes, so it measures ordering alone. Pages whose
order wasn't corrected keep the app's order of the time, so treat order on them as weaker.

The logs hold copies of manga pages: keep them out of the repository.
