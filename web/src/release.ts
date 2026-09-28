// Resolved at build time, so the site shows the newest release after each rebuild
// (.github/workflows/web.yml triggers one on every published release).
export type Release = { url: string; version?: string; sizeMb?: number };

export async function latestRelease(repo: string, ext: string): Promise<Release> {
  const fallback = { url: `https://github.com/${repo}/releases/latest` };
  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
      headers: {
        Accept: 'application/vnd.github+json',
        // Optional: build machines share IPs, so the anonymous 60 requests/hour limit can run out.
        ...(process.env.GITHUB_TOKEN && { Authorization: `Bearer ${process.env.GITHUB_TOKEN}` }),
      },
    });
    if (res.status === 404) return { url: `https://github.com/${repo}` }; // no release yet
    if (!res.ok) return fallback;
    const r = await res.json();
    const asset = r.assets?.find((a: { name: string }) => a.name.endsWith(ext));
    if (!asset) return { ...fallback, version: r.tag_name };
    return { url: asset.browser_download_url, version: r.tag_name, sizeMb: Math.round(asset.size / 1e6) };
  } catch {
    return fallback; // offline build: still a working link
  }
}
