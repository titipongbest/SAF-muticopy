#!/usr/bin/env bash
# Writes a summary and publishes it with screenshots/logs as the "test-results-apiNN" pre-release.
set -u
OUT=test-output
mkdir -p "$OUT/unit"
cp app/build/test-results/testDebugUnitTest/*.xml "$OUT/unit/" 2>/dev/null || true
cp unit-output.txt build-output.txt "$OUT/" 2>/dev/null || true
SUM="$OUT/summary.md"

{
  echo "## Device tests · Android API $API · ${GITHUB_SHA::7}"
  echo
  echo "### Unit tests (JVM)"
  python3 - "$OUT/unit" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
files = glob.glob(sys.argv[1] + "/*.xml")
if not files:
    print("Not run (see build errors).")
total = fail = 0
for f in files:
    root = ET.parse(f).getroot()
    for tc in root.iter("testcase"):
        total += 1
        bad = tc.find("failure")
        if bad is None:
            bad = tc.find("error")
        if bad is not None:
            fail += 1
            msg = (bad.get("message") or bad.text or "").strip().splitlines()[0][:300]
            print(f"- FAILED {tc.get('classname').split('.')[-1]}.{tc.get('name')}: {msg}")
if files:
    print(f"\n**{total - fail}/{total} passed**")
PY
  echo
  echo "### Device tests"
  if [ -s "$OUT/instrument.txt" ]; then
    echo '```'
    tail -c 60000 "$OUT/instrument.txt"
    echo '```'
  else
    echo "Not run (see build errors)."
  fi
  ERR=$(grep -hE "^e: |error:|What went wrong|FAILURE:" "$OUT/build-output.txt" "$OUT/unit-output.txt" 2>/dev/null | head -60)
  if [ -n "$ERR" ]; then
    echo
    echo "### Build errors"
    echo '```'
    echo "$ERR"
    echo '```'
  fi
} > "$SUM"

(cd "$OUT" && zip -qr test-output.zip . -x "test-output.zip")

ASSETS=("$OUT/test-output.zip")
for f in "$OUT"/test-screenshots/*.png "$OUT/last-screen.png"; do
  [ -s "$f" ] && ASSETS+=("$f")
done

TAG="test-results-api$API"
gh release delete "$TAG" --yes --cleanup-tag || true
gh release create "$TAG" "${ASSETS[@]}" \
  --prerelease --latest=false \
  --title "Device tests · API $API · ${GITHUB_SHA::7}" \
  --notes-file "$SUM" \
  --target "$GITHUB_SHA"
cat "$SUM"
