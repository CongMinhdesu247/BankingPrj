import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
interface DemoConfig {
  baseUrl: string;
  apiUrl: string;
  username: string;
  password: string;
  otherUsername: string;
  otherPassword: string;
  sourceId: string;
  sourceNumber: string;
  destinationId: string;
  destinationNumber: string
}
const demo = JSON.parse(readFileSync(resolve('.local/portal-demo.json'), 'utf8').replace(/^\uFEFF/, '')) as DemoConfig;
if (!/^http:\/\/127\.0\.0\.1:5174$/.test(demo.baseUrl) || demo.apiUrl !== 'http://127.0.0.1:8081' || demo.username !== 'demo.an' || demo.otherUsername !== 'demo.linh') throw new Error('E2E requires Start-PortalDemo.ps1 isolated demo on 5174/8081, never the personal database.');
let token: string, otherToken: string;
test.beforeAll(async ({ request }) => {
  for (const other of [false, true]) {
    const response = await request.post(`${demo.apiUrl}/api/v1/auth/login`, { data: { username: other ? demo.otherUsername : demo.username, password: other ? demo.otherPassword : demo.password } });
    expect(response.status()).toBe(200);
    const result = await response.json();
    if (other) otherToken = result.accessToken; else token = result.accessToken;
  }
});
async function login(page: Page, other = false) {
  await page.goto(`${demo.baseUrl}/login`);
  await page.getByLabel('Tên đăng nhập', { exact: true }).fill(other ? demo.otherUsername : demo.username);
  await page.getByLabel('Mật khẩu', { exact: true }).fill(other ? demo.otherPassword : demo.password);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await expect(page.getByRole('heading', { name: /Xin chào/ })).toBeVisible();
}
async function balance(request: APIRequestContext, other = false) {
  const response = await request.get(`${demo.apiUrl}/api/v1/accounts/${other ? demo.destinationId : demo.sourceId}`, { headers: { Authorization: `Bearer ${other ? otherToken : token}` } });
  expect(response.status()).toBe(200);
  return BigInt((await response.json()).balance.split('.')[0]);
}
async function historyTotal(request: APIRequestContext) {
  const response = await request.get(`${demo.apiUrl}/api/v1/me/transactions?size=100`, { headers: { Authorization: `Bearer ${token}` } });
  expect(response.status()).toBe(200);
  return (await response.json()).totalElements as number;
}
async function prepare(page: Page, amount = '1000') {
  await page.getByRole('navigation', { name: 'Điều hướng chính' }).getByRole('link', { name: 'Chuyển tiền', exact: true }).click();
  await page.getByLabel('Từ tài khoản', { exact: true }).selectOption(demo.sourceId);
  await page.getByLabel('Đến tài khoản', { exact: true }).fill(demo.destinationNumber);
  await page.getByRole('button', { name: 'Kiểm tra', exact: true }).click();
  await expect(page.getByText('Tài khoản trong hệ thống', { exact: true })).toBeVisible();
  await page.getByLabel('Số tiền', { exact: true }).fill(amount);
  await page.getByLabel('Nội dung chuyển tiền', { exact: false }).fill('Kiểm thử Portal với PostgreSQL thật');
  await page.getByRole('button', { name: 'Tiếp tục', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Kiểm tra thông tin' })).toBeVisible();
}
test('login validation, real 401, dashboard, memory-only session and desktop screenshot', async ({ page }) => {
  await page.goto(`${demo.baseUrl}/login`);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await expect(page.getByText('Vui lòng nhập tên đăng nhập.')).toBeVisible();
  await page.getByLabel('Tên đăng nhập', { exact: true }).fill(demo.username);
  await page.getByLabel('Mật khẩu', { exact: true }).fill('deliberately-wrong-demo-password');
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Tên đăng nhập hoặc mật khẩu chưa đúng.');
  await login(page);
  await expect(page.locator('.account-card.featured')).toBeVisible();
  await expect(page.locator('.transaction-row').first()).toBeVisible();
  const storage = await page.evaluate(() => ({ local: { ...localStorage }, session: { ...sessionStorage } }));
  expect(storage).toEqual({ local: {}, session: {} });
  mkdirSync(resolve('../docs/screenshots'), { recursive: true });
  await page.screenshot({ path: '../docs/screenshots/phase-7-dashboard-desktop.png', fullPage: true });
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Đăng nhập', exact: true })).toBeVisible();
});
test('real transfer commits exactly once, double click is harmless, both customer balances refresh', async ({ page, request, browser }) => {
  await login(page);
  const before = await balance(request),
    otherBefore = await balance(request, true),
    total = await historyTotal(request);
  let posts = 0;
  page.on('request', req => { if (req.method() === 'POST' && req.url().endsWith('/api/v1/transfers')) posts++; });
  await prepare(page);
  const committed = page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/api/v1/transfers'));
  await page.getByRole('button', { name: 'Xác nhận chuyển tiền' }).dblclick();
  const response = await committed;
  expect(response.status()).toBe(201);
  const detail = await response.json();
  expect(detail.entries).toHaveLength(2);
  expect(detail.entries.map((entry: { direction: string }) => entry.direction)).toEqual(['DEBIT', 'CREDIT']);
  await expect(page.getByRole('heading', { name: 'Chuyển tiền thành công' })).toBeVisible();
  expect(posts).toBe(1);
  expect(await balance(request)).toBe(before - 1000n);
  expect(await balance(request, true)).toBe(otherBefore + 1000n);
  expect(await historyTotal(request)).toBe(total + 1);
  await page.getByRole('link', { name: 'Xem tài khoản', exact: true }).click();
  await expect(page.locator('.accounts-grid .account-card')).toHaveCount(2);
  const otherContext = await browser.newContext();
  const otherPage = await otherContext.newPage();
  await login(otherPage, true);
  await expect(otherPage.getByRole('heading', { name: /Trần Gia Linh/ })).toBeVisible();
  await otherContext.close();
});
test('lost response after real commit, reload, re-login and retry reuse the original key', async ({ page, request }) => {
  await login(page);
  const before = await balance(request), total = await historyTotal(request);
  const keys: string[] = [];
  let first = true;
  await page.route('**/api/v1/transfers', async route => {
    keys.push(route.request().headers()['idempotency-key']);
    if (first) {
      first = false;
      const result = await route.fetch();
      expect(result.status()).toBe(201);
      await route.abort('failed');
    }
    else await route.continue();
  });
  await prepare(page, '1234');
  await page.getByRole('button', { name: 'Xác nhận chuyển tiền' }).click();
  await expect(page.getByRole('heading', { name: 'Chưa xác định kết quả giao dịch' })).toBeVisible();
  expect(await balance(request)).toBe(before - 1234n);
  const stored = await page.evaluate(() => sessionStorage.getItem('banking.pendingTransfer.v1'));
  expect(stored).not.toBeNull();
  expect(stored!).not.toMatch(/accessToken|password/);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Đăng nhập', exact: true })).toBeVisible();
  await page.getByLabel('Tên đăng nhập', { exact: true }).fill(demo.username);
  await page.getByLabel('Mật khẩu', { exact: true }).fill(demo.password);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Chưa xác định kết quả giao dịch' })).toBeVisible();
  const replay = page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/api/v1/transfers'));
  await page.getByRole('button', { name: /Thử lại cùng yêu cầu/ }).click();
  expect((await replay).headers()['idempotency-replayed']).toBe('true');
  await expect(page.getByRole('heading', { name: 'Chuyển tiền thành công' })).toBeVisible();
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBe(keys[1]);
  expect(await balance(request)).toBe(before - 1234n);
  expect(await historyTotal(request)).toBe(total + 1);
  expect(await page.evaluate(() => sessionStorage.getItem('banking.pendingTransfer.v1'))).toBeNull();
});
test('a new transfer intent generates a new key', async ({ page }) => {
  await login(page);
  const keys: string[] = [];
  page.on('request', req => {
    if (req.method() === 'POST' && req.url().endsWith('/api/v1/transfers')) keys.push(req.headers()['idempotency-key']);
  });
  for (const amount of ['1111', '2222']) {
    await prepare(page, amount);
    await page.getByRole('button', { name: 'Xác nhận chuyển tiền' }).click();
    await expect(page.getByRole('heading', { name: 'Chuyển tiền thành công' })).toBeVisible();
  }
  expect(keys).toHaveLength(2);
  expect(keys[0]).not.toBe(keys[1]);
});
test('beneficiary create, edit, transfer prefilling and soft delete use real APIs', async ({ page, request }) => {
  const previous = await request.get(`${demo.apiUrl}/api/v1/me/beneficiaries`, { headers: { Authorization: `Bearer ${token}` } });
  for (const item of (await previous.json()).content) if (item.destinationAccountId === demo.sourceId && item.nickname?.startsWith('Người nhận E2E')) await request.delete(`${demo.apiUrl}/api/v1/me/beneficiaries/${item.id}`, { headers: { Authorization: `Bearer ${token}` } });
  await login(page);
  await page.getByRole('navigation', { name: 'Điều hướng chính' }).getByRole('link', { name: 'Người thụ hưởng', exact: true }).click();
  await page.getByRole('button', { name: 'Thêm người nhận', exact: true }).click();
  await page.getByLabel('Số tài khoản', { exact: true }).fill(demo.sourceNumber);
  await page.getByRole('button', { name: 'Kiểm tra', exact: true }).click();
  await expect(page.getByText('Tài khoản trong hệ thống', { exact: true })).toBeVisible();
  const name = `Người nhận E2E ${Date.now()}`;
  await page.getByRole('textbox', { name: 'Tên gợi nhớ (không bắt buộc)', exact: true }).fill(name);
  await page.getByRole('button', { name: 'Lưu người thụ hưởng' }).click();
  const card = page.locator('.beneficiary-card').filter({ has: page.getByRole('heading', { name, exact: true }) });
  await expect(card).toBeVisible();
  await card.getByRole('button', { name: `Sửa ${name}` }).click();
  await page.getByRole('textbox', { name: 'Tên gợi nhớ (không bắt buộc)', exact: true }).fill(`${name} mới`);
  await page.getByRole('button', { name: 'Lưu người thụ hưởng' }).click();
  const edited = page.locator('.beneficiary-card').filter({ has: page.getByRole('heading', { name: `${name} mới`, exact: true }) });
  await edited.getByRole('link', { name: 'Chuyển tiền' }).click();
  await expect(page.getByLabel('Đến tài khoản', { exact: true })).toHaveValue(demo.sourceNumber);
  await page.getByRole('navigation', { name: 'Điều hướng chính' }).getByRole('link', { name: 'Người thụ hưởng', exact: true }).click();
  await page.getByRole('button', { name: `Xóa ${name} mới` }).click();
  await page.getByRole('button', { name: 'Xóa người thụ hưởng', exact: true }).click();
  await expect(page.getByRole('heading', { name: `${name} mới`, exact: true })).not.toBeVisible();
});
test('history pagination, inclusive date filters and empty states', async ({ page }) => {
  await login(page);
  await page.getByRole('navigation', { name: 'Điều hướng chính' }).getByRole('link', { name: 'Lịch sử giao dịch', exact: true }).click();
  await expect(page.locator('.transaction-row')).toHaveCount(10);
  await page.getByRole('button', { name: 'Trang sau' }).click();
  await expect(page.locator('.pagination')).toContainText('Trang 2/');
  await page.getByLabel('Từ ngày', { exact: true }).fill('2100-01-01');
  await page.getByLabel('Đến hết ngày', { exact: true }).fill('2100-01-01');
  await page.getByRole('button', { name: 'Lọc giao dịch' }).click();
  await expect(page.getByText('Chưa có giao dịch trong khoảng này', { exact: true })).toBeVisible();
  await page.getByLabel('Từ ngày', { exact: true }).fill('2100-01-02');
  await page.getByRole('button', { name: 'Lọc giao dịch' }).click();
  await expect(page.getByRole('alert')).toContainText('Ngày bắt đầu không được sau ngày kết thúc.');
});
test('foreign account URL stays protected and a 401 returns to login', async ({ page }) => {
  await page.goto(`${demo.baseUrl}/accounts/${demo.destinationId}`);
  await page.getByLabel('Tên đăng nhập', { exact: true }).fill(demo.username);
  await page.getByLabel('Mật khẩu', { exact: true }).fill(demo.password);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Không tìm thấy tài khoản');
  await page.route('**/api/v1/accounts/*', route => route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 'UNAUTHENTICATED' }) }));
  await page.getByRole('navigation', { name: 'Điều hướng chính' }).getByRole('link', { name: 'Tài khoản', exact: true }).click();
  await page.locator(`a[href="/accounts/${demo.sourceId}"]`).click();
  await expect(page.getByRole('heading', { name: 'Đăng nhập', exact: true })).toBeVisible();
  await expect(page.getByRole('status')).toContainText('hết hạn');
});
test('mobile navigation, transfer review and screenshots have no horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page);
  await expect(page.locator('.account-card.featured')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: '../docs/screenshots/phase-7-dashboard-mobile.png', fullPage: true });
  await page.getByRole('button', { name: 'Mở menu' }).click();
  await page.getByRole('navigation', { name: 'Điều hướng chính' }).getByRole('link', { name: 'Chuyển tiền' }).click();
  await page.getByLabel('Đến tài khoản', { exact: true }).fill(demo.destinationNumber);
  await page.getByRole('button', { name: 'Kiểm tra', exact: true }).click();
  await expect(page.getByText('Tài khoản trong hệ thống', { exact: true })).toBeVisible();
  await page.getByLabel('Số tiền', { exact: true }).fill('1000');
  await page.screenshot({ path: '../docs/screenshots/phase-7-transfer-mobile.png', fullPage: true });
  await page.getByRole('button', { name: 'Tiếp tục', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Kiểm tra thông tin' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole('button', { name: 'Chỉnh sửa', exact: true }).click();
  await expect(page.getByRole('dialog')).not.toBeVisible();
});
test('loading and empty customer account states stay usable', async ({ page }) => {
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const empty = { content: [], page: 0, size: 100, totalElements: 0, totalPages: 0, first: true, last: true };
  await page.route('**/api/v1/me/accounts?*', async route => {
    await gate;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(empty) });
  });
  try {
    await login(page);
    await expect(page.getByLabel('Đang tải tổng quan', { exact: true })).toBeVisible();
    release();
    await expect(page.getByText('Tài khoản đầu tiên đang chờ bạn')).toBeVisible();
  }
  finally { release(); }
  await page.getByRole('navigation', { name: 'Điều hướng chính' }).getByRole('link', { name: 'Chuyển tiền', exact: true }).click();
  await expect(page.getByText('Chưa có tài khoản nguồn khả dụng')).toBeVisible();
});
