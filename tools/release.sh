#!/usr/bin/env bash
# Release pipeline for aphone-s2t + a-readtext.
# Two SEPARATE GitHub repos, each gets its own release with a fixed-name APK
# (so Obtainium permanent links never break):
#   PT123123/aphone-s2t  -> aphones2t-release.apk
#   PT123123/a-readtext  -> areadtext-release.apk
#
# Usage:
#   bash tools/release.sh package     # build both release APKs -> dist/ + self-check
#   bash tools/release.sh verify      # only self-check dist/ APKs (reject debug cert)
#   bash tools/release.sh bump        # versionCode+1 / versionName last segment+1 (both)
#   bash tools/release.sh publish     # gate checks -> gh release create -> download verify
#
# Both apps sign with the SAME local keystore (release.p12 + keystore.properties at each
# project root). Those files are gitignored; the real keystore is backed up outside the repos.

set -euo pipefail

APHONE="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
AREAD=$APHONE/a-readtext
DIST=$APHONE/dist
mkdir -p "$DIST"

to_native() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

# build-tools: prefer the bash apksigner script (avoids the .bat stdout-hang on Windows)
BT=$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
APKSIGNER="$BT/apksigner"; [ -f "$APKSIGNER" ] || APKSIGNER="$BT/apksigner.bat"
AAPT2=$(to_native "$BT/aapt2.exe")

proj_root()  { [ "$1" = aphone ] && echo "$APHONE" || echo "$AREAD"; }
proj_fixed() { [ "$1" = aphone ] && echo "aphones2t-release.apk" || echo "areadtext-release.apk"; }
proj_repo()  { [ "$1" = aphone ] && echo "PT123123/aphone-s2t" || echo "PT123123/a-readtext"; }
proj_appid() { [ "$1" = aphone ] && echo "com.example.aphones2t" || echo "com.example.areadtext"; }

get_ver() {
  local root=$1 vc vn
  vc=$(grep -m1 "versionCode" "$root/app/build.gradle" | sed -E 's/.*versionCode[[:space:]]+([0-9]+).*/\1/')
  vn=$(grep -m1 "versionName"  "$root/app/build.gradle" | sed -E 's/.*versionName[[:space:]]+"([^"]+)".*/\1/')
  echo "$vc $vn"
}

# Source fingerprint = sha256 of every tracked + untracked(non-ignored) source file's git blob.
# Stored at build time; compared again at publish to catch "changed code, forgot to rebuild".
fingerprint() {
  ( cd "$1" && git ls-files --cached --others --exclude-standard -- \
      app/src app/build.gradle build.gradle settings.gradle gradle.properties \
    | sort | tr '\n' '\0' | xargs -0 git hash-object | sha256sum | awk '{print $1}' )
}

package_one() {
  local p=$1 root fixed src dst arch vc vn
  root=$(proj_root "$p"); fixed=$(proj_fixed "$p")
  echo ">> building $p ($(proj_repo "$p"))"
  ( cd "$root" && ./gradlew :app:assembleRelease )
  src="$root/app/build/outputs/apk/release/app-release.apk"
  [ -f "$src" ] || { echo "BUILD FAILED: $src missing"; exit 1; }
  read vc vn < <(get_ver "$root")
  dst="$DIST/$fixed"; arch="$DIST/${p}-${vn}.apk"
  cp "$src" "$dst"
  cp "$src" "$arch"
  fingerprint "$root" > "$dst.build.txt"
  echo "   -> $dst  (versionName=$vn versionCode=$vc)"
}

verify_one() {
  local p=$1 root fixed appid apk so sig badge
  root=$(proj_root "$p"); fixed=$(proj_fixed "$p"); appid=$(proj_appid "$p")
  apk="$DIST/$fixed"
  [ -f "$apk" ] || { echo "MISSING: $apk"; return 1; }
  so=$(to_native "$apk")
  sig=/tmp/rel_sig_$p.txt; "$APKSIGNER" verify --print-certs "$so" > "$sig" 2>&1
  if grep -q "CN=Android Debug" "$sig"; then
    echo "REJECT: $p is signed with the DEBUG key — not distributable"; return 1
  fi
  badge=/tmp/rel_badge_$p.txt; "$AAPT2" dump badging "$so" > "$badge" 2>&1
  local pkg; pkg=$(grep -E "^package:" "$badge" | sed -E "s/.*name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'.*/\1  code=\2  name=\3/")
  local abi; abi=$(grep -E "native-code:" "$badge" | sed -E "s/native-code: '(.*)'/\1/")
  echo "== $p : $fixed =="
  echo "   signer: $(grep -m1 'CN=' "$sig" | sed -E "s/.*(CN=[^,]+).*/\1/")"
  echo "   $pkg"
  echo "   abi: $abi"
  [ "$appid" = "$(echo "$pkg" | awk '{print $1}')" ] || { echo "APPID MISMATCH: expected $appid got $pkg"; return 1; }
}

cmd_package() { for p in aphone aread; do package_one "$p"; done; cmd_verify; }
cmd_verify()  { local rc=0; for p in aphone aread; do verify_one "$p" || rc=1; done; return $rc; }

cmd_bump() {
  for p in aphone aread; do
    local root=$1 vc vn maj min pat nc nvn
    root=$(proj_root "$p"); read vc vn < <(get_ver "$root")
    nc=$((vc+1)); IFS='.' read maj min pat <<< "$vn"; nvn="$maj.$min.$((pat+1))"
    sed -i -E "s/(versionCode[[:space:]]+)[0-9]+/\1$nc/" "$root/app/build.gradle"
    sed -i -E "s/(versionName[[:space:]]+\")[^\"]+(\")/\1$nvn\2/" "$root/app/build.gradle"
    echo "$p: $vn($vc) -> $nvn($nc)"
  done
}

cmd_publish() {
  for p in aphone aread; do
    local root fixed repo arch apk vc vn sha branch rsha dirty fp_now fp_built notes url dl
    root=$(proj_root "$p"); fixed=$(proj_fixed "$p"); repo=$(proj_repo "$p")
    apk="$DIST/$fixed"

    # GATE 1: source tree clean (only the source paths we fingerprint)
    dirty=$(gin "$root" status --porcelain -- app/src app/build.gradle build.gradle settings.gradle gradle.properties)
    if [ -n "$dirty" ]; then echo "GATE1 FAIL [$repo] uncommitted source:"; echo "$dirty"; exit 1; fi

    # GATE 2: HEAD pushed to origin/<branch>
    branch=$(gin "$root" rev-parse --abbrev-ref HEAD)
    sha=$(gin "$root" rev-parse HEAD)
    rsha=$(gin "$root" rev-parse "origin/$branch" 2>/dev/null || echo NONE)
    if [ "$sha" != "$rsha" ]; then echo "GATE2 FAIL [$repo] HEAD not on origin/$branch ($sha vs $rsha). Push first."; exit 1; fi

    # GATE 3: APK built from the current source
    [ -f "$apk" ] || { echo "GATE3 FAIL [$repo] $apk missing — run 'package' first"; exit 1; }
    fp_now=$(fingerprint "$root"); fp_built=$(cat "$apk.build.txt" 2>/dev/null || echo NONE)
    if [ "$fp_now" != "$fp_built" ]; then echo "GATE3 FAIL [$repo] APK stale (source changed after build). Rebuild."; exit 1; fi

    read vc vn < <(get_ver "$root")
    arch="$DIST/${p}-${vn}.apk"
    notes="$DIST/notes-$p.md"
    cat > "$notes" <<EOF
# $p v$vn (build $vc)

Signed release APK for Obtainium distribution.

- Repo: $repo
- Package: $(proj_appid "$p")
- Install via Obtainium: add app → GitHub → $repo → track "latest release"
- Asset: $fixed (permanent link: https://github.com/$repo/releases/latest/download/$fixed)
EOF

    echo ">> publishing $repo v$vn (tag at $sha)"
    gh release create "v$vn" "$(to_native "$apk")" "$(to_native "$arch")" \
      -R "$repo" --title "v$vn" --notes-file "$notes" --target "$sha"
    echo "   released: https://github.com/$repo/releases/tag/v$vn"

    # GATE 4: actually download the permanent link and compare sha256
    url="https://github.com/$repo/releases/latest/download/$fixed"
    dl=/tmp/rel_dl_$p.apk
    curl -sSL -o "$(to_native "$dl")" -w "   download http=%{http_code} bytes=%{size_download}\n" "$url" \
      || { echo "   WARN: download verify failed (GitHub CDN may be slow from CN)"; continue; }
    echo "   sha256 compare:"
    sha256sum "$(to_native "$dl")" "$apk" | sed 's#/tmp/rel_dl_#download  #; s#'"$DIST"'/#local    #'
  done
}

case "${1:-package}" in
  package) cmd_package ;;
  verify)  cmd_verify ;;
  bump)    cmd_bump ;;
  publish) cmd_publish ;;
  *) echo "usage: $0 [package|verify|bump|publish]"; exit 2 ;;
esac
