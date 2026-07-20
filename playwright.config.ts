import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  testMatch: 'repair-flow.spec.ts',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  timeout: 180_000,
  expect: {
    timeout: 30_000,
  },
  reporter: [['list']],
  use: {
    trace: 'off',
  },
})
