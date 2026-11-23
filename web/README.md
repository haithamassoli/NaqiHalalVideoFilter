# Naqi website

Landing page for Naqi in English (`/`) and Arabic (`/ar/`), built with Astro.
The download section links to Google Play, the App Store (iPhone, iPad and Mac) and the newest Android APK on GitHub. The Windows card shows "Coming soon" until a `windows-v…` release with an `.msi` exists, then links to it; both releases are looked up at build time (`src/release.ts`, tested by `node --test src/release.test.ts`). The browser section links to two extensions made by other developers: HaramBlur and Musicless.

```sh
pnpm install
pnpm dev      # http://localhost:4321/
pnpm build
```

Social preview images (`public/og-*.png`) come from `og/og.html`; run `./og/render.sh` after editing it.

Hosted on Vercel at https://naqi.assoli.site (project root directory: `web`). Pushes redeploy it; `.github/workflows/web.yml` also redeploys on each published release through the `VERCEL_DEPLOY_HOOK` secret, so the APK link stays current.
