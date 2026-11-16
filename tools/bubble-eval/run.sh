#!/bin/sh
# Compiles BubbleDetect + InkMap from this checkout with the Kotlin compiler bundled in Gradle,
# then runs Eval (default) or Frames on a work folder made by prepare.py.
# usage: run.sh WORKDIR [frames] [-v]
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
WORK=$1; shift
GRADLE_LIB=${GRADLE_LIB:-$(dirname "$(ls -d /opt/gradle-*/lib/kotlin-compiler-embeddable-*.jar | head -1)")}
STDLIB=$(ls "$GRADLE_LIB"/kotlin-stdlib-2*.jar | head -1)
OUT=$WORK/classes
mkdir -p "$OUT/src"
cp "$REPO/webgpuviewer/src/main/java/ca/mpreg/webgpuviewer/renderer/InkMap.kt" "$OUT/src/"
# Internal in the app; public here so the tools can call it.
sed 's/^internal object/object/' "$REPO/webgpuviewer/src/main/java/ca/mpreg/webgpuviewer/viewer/BubbleDetect.kt" > "$OUT/src/BubbleDetect.kt"
java -cp "$GRADLE_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$STDLIB" \
    -d "$OUT" "$OUT/src/InkMap.kt" "$OUT/src/BubbleDetect.kt" "$HERE/Eval.kt" "$HERE/Frames.kt" "$HERE/Panels.kt" 2>&1 | grep -E "error|e: " || true
if [ "$1" = "frames" ]; then shift; MAIN=FramesKt; INPUT=$WORK/frames.txt
elif [ "$1" = "panels" ]; then shift; MAIN=PanelsKt; INPUT=$WORK/panels.txt
else MAIN=EvalKt; INPUT=$WORK/pages.txt; fi
java -cp "$OUT:$STDLIB" $MAIN "$INPUT" "$@" 2>&1 | grep -v JAVA_TOOL
