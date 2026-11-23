// Resolved at build time, so the site shows the newest release after each rebuild
// (.github/workflows/web.yml triggers one on every published release).
export type Release = { url: string; version?: string; sizeMb?: number };

type GhAsset = { name: string; size: number; browser_download_url: string };
type GhRelease = { tag_name: string; draft: boolean; prerelease: boolean; assets?: GhAsset[] };

// Android tags are vX.Y.Z; Windows and Apple use windows-v… and apple-v… (docs/RELEASING.md).
// /releases/latest would follow whichever platform shipped last, so list and filter instead.
const ANDROID_TAG = /^v\d/;

// Windows ships as pre-releases while in beta, so its lookup passes prerelease = true.
// No `version` on the result means nothing matched: the page shows "Coming soon".
export async function latestRelease(repo: string, ext: string, tag = ANDROID_TAG, prerelease = false): Promise<Release> {
  const fallback = { url: `https://github.com/${repo}/releases/latest` };
  try {
    // 100 per page (GitHub's maximum) so a run of desktop releases can't push the newest Android one off it.
    const res = await fetch(`https://api.github.com/repos/${repo}/releases?per_page=100`, {
      headers: {
        Accept: 'application/vnd.github+json',
        // Optional: build machines share IPs, so the anonymous 60 requests/hour limit can run out.
        ...(process.env.GITHUB_TOKEN && { Authorization: `Bearer ${process.env.GITHUB_TOKEN}` }),
      },
    });
    if (!res.ok) return fallback;
    const releases: GhRelease[] = await res.json();
    if (releases.length === 0) return { url: `https://github.com/${repo}` }; // no release yet
    // GitHub lists newest first (by creation date), the same order /releases/latest uses.
    for (const r of releases) {
      if (r.draft || (r.prerelease && !prerelease) || !tag.test(r.tag_name)) continue;
      const asset = r.assets?.find((a) => a.name.endsWith(ext));
      if (asset) {
        return { url: asset.browser_download_url, version: r.tag_name, sizeMb: Math.round(asset.size / 1e6) };
      }
    }
    return fallback;
  } catch {
    return fallback; // offline build: still a working link
  }
}
