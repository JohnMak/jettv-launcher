#!/bin/bash
# Reversibly disable the packages from a list on one device (`pm disable-user --user 0`). Nothing is uninstalled.
# Usage: tools/debloat.sh [-y] <adb-serial> <list-file>
#   list-file: one package per line; "#" starts a comment (see tools/debloat/*.txt)
# Before changing anything it saves the package inventory to $DEBLOAT_LOG_DIR (default: ./backup/debloat) and
# afterwards writes a log of what it disabled; revert with tools/undebloat.sh <serial> <list-or-log>.
set -euo pipefail
ASSUME_YES=0
if [ "${1:-}" = "-y" ]; then ASSUME_YES=1; shift; fi
[ $# -eq 2 ] || { echo "usage: $0 [-y] <adb-serial> <list-file>" >&2; exit 2; }
SERIAL="$1"; LIST="$2"
[ -f "$LIST" ] || { echo "no such list: $LIST" >&2; exit 2; }
ADB=(adb -s "$SERIAL")
LOG_DIR="${DEBLOAT_LOG_DIR:-backup/debloat}"
TS="$(date +%Y%m%d-%H%M%S)"
TAG="$(printf '%s' "$SERIAL" | tr -c 'A-Za-z0-9' '_')"
mkdir -p "$LOG_DIR"

"${ADB[@]}" get-state >/dev/null || { echo "device $SERIAL not reachable" >&2; exit 1; }

# Package names from the list: strip comments and blanks.
PKGS=()
while IFS= read -r line; do
  line="${line%%#*}"; line="$(echo "$line" | tr -d '[:space:]')"
  [ -n "$line" ] && PKGS+=("$line")
done < "$LIST"
[ ${#PKGS[@]} -gt 0 ] || { echo "list is empty" >&2; exit 1; }

# Inventory backup first.
INV="$LOG_DIR/inventory-$TAG-$TS"
mkdir -p "$INV"
"${ADB[@]}" shell pm list packages -f --user 0 | tr -d '\r' > "$INV/packages-all.txt"
"${ADB[@]}" shell pm list packages -d --user 0 | tr -d '\r' > "$INV/packages-disabled.txt"
"${ADB[@]}" shell pm list packages -s --user 0 | tr -d '\r' > "$INV/packages-system.txt"
echo "inventory saved: $INV"

"${ADB[@]}" shell pm list packages --user 0 | tr -d '\r' > "$INV/packages-installed.txt"
TODO=()
for p in "${PKGS[@]}"; do
  if ! grep -qx "package:$p" "$INV/packages-installed.txt"; then
    echo "skip (not installed): $p"
  elif grep -qx "package:$p" "$INV/packages-disabled.txt"; then
    echo "skip (already disabled): $p"
  else
    TODO+=("$p")
  fi
done
[ ${#TODO[@]} -gt 0 ] || { echo "nothing to do"; exit 0; }

echo "will disable on $SERIAL (reversible):"; printf '  %s\n' "${TODO[@]}"
if [ "$ASSUME_YES" -ne 1 ]; then
  read -r -p "Proceed? [y/N] " a
  [ "$a" = "y" ] || { echo "aborted"; exit 1; }
fi

LOG="$LOG_DIR/debloat-$TAG-$TS.log"
: > "$LOG"
for p in "${TODO[@]}"; do
  if out="$("${ADB[@]}" shell pm disable-user --user 0 "$p" 2>&1)"; then
    echo "disabled $p" | tee -a "$LOG"
  else
    echo "failed $p: $out" | tee -a "$LOG"
  fi
done
echo "log: $LOG  (undo: tools/undebloat.sh $SERIAL $LOG)"
