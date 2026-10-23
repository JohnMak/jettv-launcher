#!/bin/bash
# Re-enable packages disabled by tools/debloat.sh.
# Usage: tools/undebloat.sh <adb-serial> <list-file-or-debloat-log>
#   Accepts a list from tools/debloat/*.txt (re-enables every entry) or a debloat log
#   (re-enables only the lines starting with "disabled ").
set -euo pipefail
[ $# -eq 2 ] || { echo "usage: $0 <adb-serial> <list-file-or-log>" >&2; exit 2; }
SERIAL="$1"; SRC="$2"
[ -f "$SRC" ] || { echo "no such file: $SRC" >&2; exit 2; }
ADB=(adb -s "$SERIAL")
"${ADB[@]}" get-state >/dev/null || { echo "device $SERIAL not reachable" >&2; exit 1; }
while IFS= read -r line; do
  line="${line%%#*}"
  set -- $line
  [ $# -gt 0 ] || continue
  if [ "$1" = "disabled" ]; then p="${2:-}"; elif [ "$1" = "failed" ]; then continue; else p="$1"; fi
  [ -n "$p" ] || continue
  if out="$("${ADB[@]}" shell pm enable "$p" 2>&1)"; then echo "enabled $p"; else echo "failed $p: $out"; fi
done < "$SRC"
