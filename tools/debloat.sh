#!/bin/bash
# Reversibly clean up the packages from a profile on one device.
# Usage: tools/debloat.sh [-y] [--include-optional] <adb-serial> <profile-file>
#   profile-file: lines "<action>[?] <package> [requires=home] [# comment]" (see tools/debloat/*.txt);
#     disable = pm disable-user --user 0, remove = pm uninstall -k --user 0, reset = pm uninstall (drops updates);
#     a line without action is "disable"; "@match" lines are ignored here; "?" entries need --include-optional;
#     "requires=home" entries are applied only when com.johnmak.atvlauncher holds the HOME role.
# Before changing anything it saves the package inventory to $DEBLOAT_LOG_DIR (default: ./backup/debloat) and
# afterwards writes a log of what it disabled; revert with tools/undebloat.sh <serial> <list-or-log>.
set -euo pipefail
ASSUME_YES=0
INCLUDE_OPTIONAL=0
while [ $# -gt 0 ]; do
  case "$1" in
    -y) ASSUME_YES=1; shift ;;
    --include-optional) INCLUDE_OPTIONAL=1; shift ;;
    *) break ;;
  esac
done
[ $# -eq 2 ] || { echo "usage: $0 [-y] [--include-optional] <adb-serial> <profile-file>" >&2; exit 2; }
SERIAL="$1"; LIST="$2"
[ -f "$LIST" ] || { echo "no such list: $LIST" >&2; exit 2; }
ADB=(adb -s "$SERIAL")
LOG_DIR="${DEBLOAT_LOG_DIR:-backup/debloat}"
TS="$(date +%Y%m%d-%H%M%S)"
TAG="$(printf '%s' "$SERIAL" | tr -c 'A-Za-z0-9' '_')"
mkdir -p "$LOG_DIR"

"${ADB[@]}" get-state >/dev/null || { echo "device $SERIAL not reachable" >&2; exit 1; }

# Entries "action package" from the profile (bash 3 compatible: parallel arrays).
ACTIONS=(); PKGS=()
HOME_HOLDER="$("${ADB[@]}" shell cmd role get-role-holders --user 0 android.app.role.HOME | tr -d '\r')"
n=0
while IFS= read -r raw; do
  n=$((n+1))
  line="${raw%%#*}"
  set -- $line
  [ $# -gt 0 ] || continue
  case "$1" in @match) continue ;; esac
  token="$1"
  case "$token" in
    disable|disable\?|remove|remove\?|reset|reset\?) shift ;;
    *) token="disable" ;;
  esac
  [ $# -ge 1 ] || { echo "line $n: missing package" >&2; exit 2; }
  pkg="$1"; shift
  echo "$pkg" | grep -Eq '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$' || { echo "line $n: bad package: $pkg" >&2; exit 2; }
  needs_home=0
  for opt in "$@"; do
    case "$opt" in
      requires=home) needs_home=1 ;;
      *) echo "line $n: unknown option: $opt" >&2; exit 2 ;;
    esac
  done
  action="${token%\?}"
  if [ "$token" != "$action" ] && [ "$INCLUDE_OPTIONAL" -ne 1 ]; then continue; fi
  if [ "$needs_home" -eq 1 ] && ! echo "$HOME_HOLDER" | grep -q 'com.johnmak.atvlauncher'; then
    echo "skip (launcher does not hold HOME): $pkg"; continue
  fi
  ACTIONS+=("$action"); PKGS+=("$pkg")
done < "$LIST"
[ ${#PKGS[@]} -gt 0 ] || { echo "no applicable entries" >&2; exit 1; }

# Inventory backup first.
INV="$LOG_DIR/inventory-$TAG-$TS"
mkdir -p "$INV"
"${ADB[@]}" shell pm list packages -f --user 0 | tr -d '\r' > "$INV/packages-all.txt"
"${ADB[@]}" shell pm list packages -d --user 0 | tr -d '\r' > "$INV/packages-disabled.txt"
"${ADB[@]}" shell pm list packages -s --user 0 | tr -d '\r' > "$INV/packages-system.txt"
echo "inventory saved: $INV"

"${ADB[@]}" shell pm list packages --user 0 | tr -d '\r' > "$INV/packages-installed.txt"
TODO=()
for i in "${!PKGS[@]}"; do
  p="${PKGS[$i]}"; act="${ACTIONS[$i]}"
  if ! grep -qx "package:$p" "$INV/packages-installed.txt"; then
    echo "skip (not installed): $p"
  elif [ "$act" = "disable" ] && grep -qx "package:$p" "$INV/packages-disabled.txt"; then
    echo "skip (already disabled): $p"
  else
    TODO+=("$act $p")
  fi
done
[ ${#TODO[@]} -gt 0 ] || { echo "nothing to do"; exit 0; }

echo "will apply on $SERIAL (reversible):"; printf '  %s\n' "${TODO[@]}"
if [ "$ASSUME_YES" -ne 1 ]; then
  read -r -p "Proceed? [y/N] " a
  [ "$a" = "y" ] || { echo "aborted"; exit 1; }
fi

LOG="$LOG_DIR/debloat-$TAG-$TS.log"
: > "$LOG"
for entry in "${TODO[@]}"; do
  act="${entry%% *}"; p="${entry#* }"
  case "$act" in
    disable) cmd=(pm disable-user --user 0 "$p"); done_word="disabled" ;;
    remove)  cmd=(pm uninstall -k --user 0 "$p"); done_word="removed" ;;
    reset)   cmd=(pm uninstall "$p"); done_word="reset" ;;
  esac
  if out="$("${ADB[@]}" shell "${cmd[@]}" 2>&1)" && { [ "$act" = "disable" ] || echo "$out" | grep -q Success; }; then
    echo "$done_word $p" | tee -a "$LOG"
  else
    echo "failed $p: $out" | tee -a "$LOG"
  fi
done
echo "log: $LOG  (undo: tools/undebloat.sh $SERIAL $LOG)"
