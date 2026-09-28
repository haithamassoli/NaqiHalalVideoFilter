# Naqi website

Landing page for Naqi in English (`/`) and Arabic (`/ar/`), built with Astro.
The store buttons link to Google Play and the App Store; the APK link points at the latest GitHub release, looked up at build time.

```sh
pnpm install
pnpm dev      # http://localhost:4321/
pnpm build
```

Social preview images (`public/og-*.png`) come from `og/og.html`; run `./og/render.sh` after editing it.

Hosted on Vercel at https://naqi.assoli.site (project root directory: `web`). Pushes redeploy it; `.github/workflows/web.yml` also redeploys on each published release through the `VERCEL_DEPLOY_HOOK` secret, so the APK link stays current.
