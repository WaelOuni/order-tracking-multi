import { defineConfig, devices } from '@playwright/test';

// PLAYWRIGHT_LIVE=1 utilise l'application Docker réelle, sans masquer ses API.
// Sinon un simple serveur de fichiers + page.route isole le comportement de l'UI.
const live = process.env.PLAYWRIGHT_LIVE === '1';
const baseURL = process.env.PLAYWRIGHT_BASE_URL || (live ? 'http://127.0.0.1:8080' : 'http://127.0.0.1:4173');

export default defineConfig({
  testDir: './ui-tests',
  testMatch: '**/*.spec.js',
  fullyParallel: !live,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 2,
  timeout: live ? 60_000 : 20_000,
  expect: { timeout: live ? 30_000 : 5_000 },
  grep: live ? /@live/ : undefined,
  grepInvert: live ? undefined : /@live/,
  reporter: [['list'], ['html', { open: 'never' }], ['junit', { outputFile: 'test-results/ui-junit.xml' }]],
  use: {
    baseURL,
    locale: 'fr-FR',
    timezoneId: 'Europe/Paris',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: live ? undefined : {
    command: 'node ui-tests/static-server.mjs',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 15_000,
  },
});
