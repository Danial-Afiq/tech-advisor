
import { defineConfig } from "vitest/config";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";


export default defineConfig({
  plugins: [tailwindcss(), react()],
  // VITE_* variables come from the repo-root .env, the single source of truth
  // for the whole project. Note this means a frontend/.env is NOT read at all.
  envDir: '..',
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
})

