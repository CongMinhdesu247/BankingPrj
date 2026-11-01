import { defineConfig } from '@playwright/test';
export default defineConfig({ testDir: './e2e', fullyParallel: false, workers: 1, retries: 0, timeout: 45000,
  reporter: [['list']], use: { browserName: 'chromium', headless: true, viewport: { width: 1440, height: 1000 }, locale: 'vi-VN', timezoneId: 'Asia/Ho_Chi_Minh', trace: 'off', video: 'off', screenshot: 'only-on-failure' } });
