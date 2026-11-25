import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// One code base, two builds: "morph-local" is served by Morph Local; "morph-public" arrives in phase 3 with a browser
// backend. (Vite reserves the bare name "local".)
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  build: {
    outDir: mode === 'morph-public' ? 'dist-public' : 'dist-local',
    // The Content-Security-Policy of Morph Local allows files from its own origin only: nothing may be inlined.
    assetsInlineLimit: 0,
    modulePreload: { polyfill: false },
    sourcemap: false,
  },
}));
