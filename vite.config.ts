import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'prompt',
      manifest: {
        id: '/',
        name: 'RF Field Suite — 5G/LTE Drive Test & Route Diagnostics',
        short_name: 'RF Field Suite',
        description:
          'Drive Test & RF route diagnostics for 5G NR / LTE — GPS, route maps, campaign storage, analytics and PDF reports.',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        orientation: 'any',
        background_color: '#0b0f17',
        theme_color: '#0b0f17',
        lang: 'en',
        categories: ['utilities', 'productivity', 'navigation'],
        icons: [
          { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png' },
          {
            src: '/icons/maskable-192.png',
            sizes: '192x192',
            type: 'image/png',
            purpose: 'maskable',
          },
          {
            src: '/icons/maskable-512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable',
          },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,ico,png,svg,woff2}'],
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [/^\/api\//],
        cleanupOutdatedCaches: true,
        clientsClaim: true,
        runtimeCaching: [
          // Leaflet CSS from CDN (used in index.html)
          {
            urlPattern: /^https:\/\/unpkg\.com\/leaflet@1\.9\.4\/dist\/leaflet\.css$/,
            handler: 'StaleWhileRevalidate',
            options: {
              cacheName: 'leaflet-cdn',
              expiration: { maxEntries: 4, maxAgeSeconds: 60 * 60 * 24 * 30 },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
          // Map tile providers: cache a bounded set for offline use, but prefer live tiles
          {
            urlPattern: /^https:\/\/(mt[0-9]\.google\.com|mt\.google\.com|server\.arcgisonline\.com|[a-c]\.tile\.opentopomap\.org)/,
            handler: 'NetworkFirst',
            options: {
              cacheName: 'map-tiles',
              networkTimeoutSeconds: 5,
              expiration: { maxEntries: 400, maxAgeSeconds: 60 * 60 * 24 * 7 },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
        ],
      },
      devOptions: {
        enabled: false,
      },
    }),
  ],
  server: {
    host: true,
    port: 5173,
    strictPort: false,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/testSetup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
});