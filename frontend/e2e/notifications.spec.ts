import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
const demo = JSON.parse(readFileSync(resolve('.local/portal-demo.json'), 'utf8').replace(/^\uFEFF/, '')) as {
  baseUrl: string;
  apiUrl: string;
  username: string;
  password: string;
  otherUsername: string;
  otherPassword: string;
  riskUsername: string;
  riskPassword: string;
  sourceId: string;
  destinationId: string
};
if (demo.apiUrl !== 'http://127.0.0.1:8081' || demo.baseUrl !== 'http://127.0.0.1:5174') throw new Error('Notification E2E requires isolated portal demo.');
let customer: string, other: string, risk: string;
test.beforeAll(async ({ request }) => {
  for (const role of ['customer', 'other', 'risk']) {
    const username = role === 'customer' ? demo.username : role === 'other' ? demo.otherUsername : demo.riskUsername;
    const password = role === 'customer' ? demo.password : role === 'other' ? demo.otherPassword : demo.riskPassword;
    const response = await request.post(`${demo.apiUrl}/api/v1/auth/login`, { data: { username, password } });
    expect(response.status()).toBe(200);
    const token = (await response.json()).accessToken;
    if (role === 'customer') customer = token;
    else if (role === 'other') other = token; else risk = token;
  }
});
async function login(page: Page, riskUser = false) {
  await page.goto(`${demo.baseUrl}/notifications`);
  await page.getByLabel('Tên đăng nhập', { exact: true }).fill(riskUser ? demo.riskUsername : demo.username);
  await page.getByLabel('Mật khẩu', { exact: true }).fill(riskUser ? demo.riskPassword : demo.password);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Thông báo', exact: true })).toBeVisible();
}
test('customer reads a Kafka-delivered notification and another customer cannot mark it read', async ({ page, request }) => {
  const endpoint = `${demo.apiUrl}/api/v1/me/notifications?unreadOnly=true`;
  const initial = await (await request.get(endpoint, { headers: { Authorization: `Bearer ${customer}` } })).json();
  expect(initial.totalElements).toBeGreaterThan(0);
  const notice = initial.content[0];
  const denied = await request.patch(`${demo.apiUrl}/api/v1/me/notifications/${notice.id}/read`, { headers: { Authorization: `Bearer ${other}` } });
  expect(denied.status()).toBe(404);
  await login(page);
  await page.getByLabel('Chỉ thông báo chưa đọc').check();
  await page.getByRole('button', { name: 'Đánh dấu đã đọc', exact: true }).first().click();
  await expect.poll(async () => (await (await request.get(endpoint, { headers: { Authorization: `Bearer ${customer}` } })).json()).totalElements).toBe(initial.totalElements - 1);
  await page.getByRole('link', { name: 'Xem chi tiết →', exact: true }).first().click();
  await expect(page.getByRole('heading', { name: 'Giao dịch thành công', exact: true })).toBeVisible();
});
test('failed transfer delivers an in-app message without a completed transaction', async ({ page, request }) => {
  const headers = { Authorization: `Bearer ${customer}` };
  const before = await (await request.get(`${demo.apiUrl}/api/v1/me/transactions?size=1`, { headers })).json();
  const failed = await request.post(`${demo.apiUrl}/api/v1/transfers`, {
    headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() }, data: {
      sourceAccountId: demo.sourceId,
      destinationAccountId: demo.destinationId,
      amount: '99999999',
      currency: 'VND',
      description: 'Phase 10 failed attempt fixture'
    }
  });
  expect(failed.status()).toBe(409);
  expect((await failed.json()).code).toBe('INSUFFICIENT_FUNDS');
  await expect.poll(async () => {
    const inbox = await (await request.get(`${demo.apiUrl}/api/v1/me/notifications`, { headers })).json();
    return inbox.content.some((item: { type: string }) => item.type === 'TRANSFER_FAILED');
  }).toBe(true);
  const after = await (await request.get(`${demo.apiUrl}/api/v1/me/transactions?size=1`, { headers })).json();
  expect(after.totalElements).toBe(before.totalElements);
  await login(page);
  await expect(page.getByText('Số dư tài khoản nguồn không đủ để chuyển tiền.')).toBeVisible();
});
test('risk officer follows a fraud notification into the investigation', async ({ page, request }) => {
  const response = await request.get(`${demo.apiUrl}/api/v1/me/notifications`, { headers: { Authorization: `Bearer ${risk}` } });
  const inbox = await response.json();
  expect(inbox.content.every((item: { type: string }) => item.type === 'FRAUD_DETECTED')).toBe(true);
  expect(inbox.totalElements).toBeGreaterThan(0);
  await login(page, true);
  await expect(page.getByRole('heading', { name: 'Có cảnh báo cần điều tra' }).first()).toBeVisible();
  await page.screenshot({ path: '../docs/screenshots/phase-10-notifications-risk.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('navigation', { name: 'Điều hướng chính' })).not.toBeInViewport();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  await page.screenshot({
    path: '../docs/screenshots/phase-10-notifications-mobile.png',
    fullPage: true,
    animations: 'disabled'
  });
  await page.getByRole('link', { name: 'Xem chi tiết →', exact: true }).first().click();
  await expect(page.getByRole('heading', { name: 'Điều tra giao dịch' })).toBeVisible();
});
