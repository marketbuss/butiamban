#!/usr/bin/env bash
# gadgetforge/install.sh — one-liner for Mac/Linux PCs
#
# usage from the repo root:
#   bash gadgetforge/install.sh <target_apk_name>
#   # e.g. bash gadgetforge/install.sh animalcompany
#
# requires: git, adb, curl. optional: gh (GitHub CLI), frida (pip install frida-tools)
set -euo pipefail

NAME="${1:-animalcompany}"
PKG="${GADGETFORGE_PKG:-com.DeadlyCrocCorp.AnimalCompany}"
BRANCH="${GADGETFORGE_BRANCH:-claude/ollie-bench-deployment-u4k02l}"
REMOTE_ORIGIN="${GADGETFORGE_REMOTE:-origin}"

say()  { printf "\033[1;36m[gf]\033[0m %s\n" "$*"; }
die()  { printf "\033[1;31m[err]\033[0m %s\n" "$*" >&2; exit 1; }

say "0. check tools"
command -v adb  >/dev/null || die "adb missing (install Android platform-tools)"
command -v git  >/dev/null || die "git missing"
command -v curl >/dev/null || die "curl missing"

say "1. check device"
adb start-server >/dev/null
DEV=$(adb devices | awk '$2=="device"{print $1}' | head -1)
[ -z "$DEV" ] && die "no Quest connected or usb debugging not allowed"
say "  device: $DEV"

say "2. pull Store apk ($PKG)"
APK_PATH=$(adb shell pm path "$PKG" 2>/dev/null | sed 's/package://' | tr -d '\r' | head -1)
if [ -z "$APK_PATH" ]; then
  die "$PKG not installed on the Quest. install from Store first, then re-run."
fi
say "  path on device: $APK_PATH"
adb pull "$APK_PATH" "gadgetforge/input/${NAME}.apk" >/dev/null
say "  pulled $(du -h gadgetforge/input/${NAME}.apk | awk '{print $1}')"

say "3. push to CI"
git add "gadgetforge/input/${NAME}.apk" "gadgetforge/scripts/mod.js" 2>/dev/null || true
git commit -m "gadgetforge: patch ${NAME}" >/dev/null || say "  nothing new to commit — triggering workflow dispatch"
git push "$REMOTE_ORIGIN" "$BRANCH"

say "4. wait for CI build"
if command -v gh >/dev/null; then
  RUN_ID=$(gh api "repos/{owner}/{repo}/actions/runs?branch=$BRANCH&per_page=1" --jq '.workflow_runs[0].id')
  gh run watch "$RUN_ID" --exit-status
else
  say "  gh CLI not installed — polling REST API every 20s"
  REPO=$(git config --get remote.$REMOTE_ORIGIN.url \
         | sed -E 's#(git@|https://)github.com[:/]##; s#\.git$##')
  while :; do
    STAT=$(curl -s "https://api.github.com/repos/$REPO/actions/runs?branch=$BRANCH&per_page=1" \
           | python3 -c "import sys,json;d=json.load(sys.stdin)['workflow_runs'][0];print(d['status'],d.get('conclusion') or '')")
    echo "    ci: $STAT"
    case "$STAT" in
      completed*success*) break ;;
      completed*)         die "ci failed: $STAT" ;;
    esac
    sleep 20
  done
fi

say "5. fetch patched apk"
git fetch "$REMOTE_ORIGIN" apk-builds
git show "$REMOTE_ORIGIN/apk-builds:build/gadgetforge/${NAME}.gadget.apk" > "${NAME}.gadget.apk"
sha256sum "${NAME}.gadget.apk" | head -1

say "6. install on Quest"
adb uninstall "$PKG" >/dev/null || say "  (original not installed, that's fine)"
adb install -r "${NAME}.gadget.apk"

say "7. launch"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null

if command -v frida >/dev/null; then
  say "8. forward frida and attach (ctrl+c when done)"
  adb forward tcp:27042 tcp:27042
  sleep 2
  frida -U -n Gadget -l gadgetforge/scripts/mod.js || true
else
  say "8. frida CLI not installed — install with: pip install frida-tools"
  say "   then: adb forward tcp:27042 tcp:27042 && frida -U -n Gadget -l gadgetforge/scripts/mod.js"
fi

say "done. edit /sdcard/Download/animalcompany.cfg on the Quest to retune live."
