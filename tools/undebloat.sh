#!/bin/bash
# Restore packages cleaned up by tools/debloat.sh (enable + install-existing for the user).
# Usage: tools/undebloat.sh <adb-serial> <profile-file-or-debloat-log>
#   Accepts a profile from tools/debloat/*.txt (restores every non-"@match" entry, optional ones included)
#   or a debloat log (restores only the lines starting with "disabled ", "removed " or "reset ").
#   "reset" only dropped updates: the factory version is still there, so it needs no restore.
set -euo pipefail
[ $# -eq 2 ] || { echo "usage: $0 <adb-serial> <profile-file-or-log>" >&2; exit 2; }
SERIAL="$1"; SRC="$2"
[ -f "$SRC" ] || { echo "no such file: $SRC" >&2; exit 2; }
ADB=(adb -s "$SERIAL")
"${ADB[@]}" get-state >/dev/null || { echo "device $SERIAL not reachable" >&2; exit 1; }

restore() {
  local p="$1" out
  if out="$("${ADB[@]}" shell cmd package install-existing --user 0 "$p" 2>&1)"; then :; else echo "failed install-existing $p: $out"; return; fi
  if out="$("${ADB[@]}" shell pm enable "$p" 2>&1)"; then echo "restored $p"; else echo "failed enable $p: $out"; fi
}

while IFS= read -r line; do
  line="${line%%#*}"
  set -- $line
  [ $# -gt 0 ] || continue
  case "$1" in
    @match|failed|reset) continue ;;
    disabled|removed) p="${2:-}" ;;
    disable|remove|disable\?|remove\?) p="${2:-}" ;;
    *) p="$1" ;;
  esac
  [ -n "$p" ] || continue
  restore "$p"
done < "$SRC"
