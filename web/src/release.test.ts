// node --test src/release.test.ts
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { latestRelease } from './release.ts';

test('finds the Android release behind a long run of desktop releases', async () => {
  // 40 desktop releases, then the newest Android one; the fake API honours per_page like GitHub does.
  const releases = [
    ...Array.from({ length: 40 }, (_, i) => ({ tag_name: `windows-v0.${i}.0`, draft: false, prerelease: false, assets: [] })),
    { tag_name: 'v1.9.0', draft: false, prerelease: false,
      assets: [{ name: 'naqi-1.9.0.apk', size: 50e6, browser_download_url: 'https://example.test/naqi-1.9.0.apk' }] },
  ];
  globalThis.fetch = async (url) => {
    const perPage = Number(new URL(String(url)).searchParams.get('per_page') ?? 30);
    return Response.json(releases.slice(0, perPage));
  };
  const r = await latestRelease('o/r', '.apk');
  assert.equal(r.version, 'v1.9.0');
  assert.equal(r.url, 'https://example.test/naqi-1.9.0.apk');
});
