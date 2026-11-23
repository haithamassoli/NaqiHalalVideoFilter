#!/bin/bash -e
# Fails unless the newest Android release is among the DEPTH newest releases. Android builds from before the
# monorepo move read only /releases?per_page=10, so an Android release deeper than that is invisible to their
# update check (docs/RELEASING.md, "Page depth").
#
#   release-depth.sh       after publishing: is it still within the 10 newest?
#   release-depth.sh 9     before publishing a desktop release: will it still be after one more?
#
# A second argument reads the release list from that JSON file instead of the GitHub API (for the tests).
# "Android release" means what those builds offer: tag v<digit>, not a draft or pre-release, with an .apk.
DEPTH=${1:-10}
REPO=${GITHUB_REPOSITORY:-haithamassoli/NaqiHalalVideoFilter}
if [ -n "$2" ]; then
  RELEASES=$(cat "$2")
else
  RELEASES=$(gh api "repos/$REPO/releases?per_page=100")
fi

# Position (1-based) of the newest Android release on the anonymous page, which leaves drafts out; 0 if none.
POS=$(jq '[.[] | select(.draft | not)]
  | map((.tag_name | test("^v[0-9]")) and (.prerelease | not) and any(.assets[]?; .name | endswith(".apk")))
  | (index(true) // -1) + 1' <<<"$RELEASES")

if [ "$POS" -eq 0 ]; then
  echo "No Android release among the newest 100 releases." >&2
  exit 1
fi
if [ "$POS" -gt "$DEPTH" ]; then
  echo "The newest Android release is number $POS; it must be within the newest $DEPTH." >&2
  echo "Publish an Android release first (docs/RELEASING.md, \"Page depth\")." >&2
  exit 1
fi
echo "The newest Android release is number $POS of the newest $DEPTH: OK."
