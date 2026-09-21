import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  // VITE_* variables come from the repo-root .env, the single source of truth
  // for the whole project. Note this means a frontend/.env is NOT read at all.
  envDir: '..',
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
})