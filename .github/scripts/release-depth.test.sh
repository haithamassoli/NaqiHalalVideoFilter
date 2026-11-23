#!/bin/bash -e
# Tests release-depth.sh against made-up release lists (newest first, as the GitHub API returns them).
cd "$(dirname "$0")"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# $1 desktop releases newer than the newest Android one, then that Android release.
list() {
  {
    echo '['
    for i in $(seq "$1"); do echo "{\"tag_name\":\"windows-v0.$i.0\",\"draft\":false,\"prerelease\":false,\"assets\":[{\"name\":\"Naqi.msi\"}]},"; done
    echo '{"tag_name":"v1.9.0","draft":false,"prerelease":false,"assets":[{"name":"naqi-1.9.0.apk"}]}]'
  } > "$TMP/$1.json"
  echo "$TMP/$1.json"
}
ok() { ./release-depth.sh "$@" >/dev/null || { echo "FAIL: expected pass: $*"; exit 1; }; }
fails() { if ./release-depth.sh "$@" >/dev/null 2>&1; then echo "FAIL: expected failure: $*"; exit 1; fi; }

ok 10 "$(list 9)"     # the Android release is 10th: older builds still see it
fails 10 "$(list 10)" # 11th: pushed off their page
ok 9 "$(list 8)"      # before publishing one more desktop release
fails 9 "$(list 9)"

# Drafts are not on the anonymous page; pre-releases and tags without an .apk do not count as Android.
cat > "$TMP/mixed.json" <<'EOF'
[{"tag_name":"v2.0.0-beta1","draft":false,"prerelease":true,"assets":[{"name":"naqi.apk"}]},
 {"tag_name":"v2.0.0","draft":true,"prerelease":false,"assets":[{"name":"naqi.apk"}]},
 {"tag_name":"apple-v1.0.0","draft":false,"prerelease":false,"assets":[{"name":"Naqi.dmg"}]},
 {"tag_name":"v1.9.0","draft":false,"prerelease":false,"assets":[{"name":"naqi-1.9.0.apk"}]}]
EOF
ok 3 "$TMP/mixed.json"
fails 2 "$TMP/mixed.json"
echo '[{"tag_name":"windows-v0.1.0","draft":false,"prerelease":false,"assets":[]}]' > "$TMP/none.json"
fails 10 "$TMP/none.json"
echo "release-depth.sh: all tests passed"
