// @ts-check
import { defineConfig, fontProviders } from 'astro/config';

export default defineConfig({
  site: 'https://naqi.assoli.site',
  i18n: { locales: ['en', 'ar'], defaultLocale: 'en' },
  fonts: [
    {
      provider: fontProviders.google(),
      name: 'Readex Pro',
      cssVariable: '--font-readex',
      weights: ['300 700'],
      subsets: ['arabic', 'latin'],
      fallbacks: ['sans-serif'],
    },
  ],
});
