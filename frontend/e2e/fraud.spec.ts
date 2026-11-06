import { test, expect, type Page } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
interface Demo {
  baseUrl: string;
  apiUrl: string;
  username: string;
  password: string;
  riskUsername: string;
  riskPassword: string;
  secondRiskUsername: string;
  secondRiskPassword: string;
  adminUsername: string;
  adminPassword: string;
  staffUsername: string;
  staffPassword: string
}
const demo = JSON.parse(readFileSync(resolve('.local/portal-demo.json'), 'utf8').replace(/^\uFEFF/, '')) as Demo;
if (demo.baseUrl !== 'http://127.0.0.1:5174' || demo.apiUrl !== 'http://127.0.0.1:8081' || demo.riskUsername !== 'demo.risk') throw new Error('Fraud E2E requires the isolated Phase 9 portal demo.');
let riskToken: string,
  secondRiskId: string,
  riskId: string,
  customerToken: string,
  adminToken: string;

test.beforeAll(async ({ request }) => {
  for (const [username, password, role] of [[demo.riskUsername, demo.riskPassword, 'risk'], [demo.secondRiskUsername, demo.secondRiskPassword, 'risk2'], [demo.username, demo.password, 'customer'], [demo.adminUsername, demo.adminPassword, 'admin']]) {
    const response = await request.post(`${demo.apiUrl}/api/v1/auth/login`, { data: { username, password } });
    expect(response.status()).toBe(200);
    const body = await response.json();
    if (role === 'risk') {
      riskToken = body.accessToken;
      riskId = body.user.id;
    }
    if (role === 'risk2') secondRiskId = body.user.id;
    if (role === 'customer') customerToken = body.accessToken;
    if (role === 'admin') adminToken = body.accessToken;
  }
});
async function login(page: Page, role: 'risk' | 'risk2' | 'admin' | 'staff' | 'customer' = 'risk', path = '/') {
  const credentials = role === 'risk'
    ? [demo.riskUsername, demo.riskPassword]
    : role === 'risk2'
      ? [demo.secondRiskUsername, demo.secondRiskPassword]
      : role === 'admin'
        ? [demo.adminUsername, demo.adminPassword]
        : role === 'staff' ? [demo.staffUsername, demo.staffPassword] : [demo.username, demo.password];
  await page.goto(`${demo.baseUrl}${path}`);
  await page.getByLabel('Tên đăng nhập', { exact: true }).fill(credentials[0]);
  await page.getByLabel('Mật khẩu', { exact: true }).fill(credentials[1]);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await expect(page.getByRole('main')).toBeVisible();
}
test('risk dashboard and server-side alert filters use real analysis data', async ({ page }) => {
  await login(page);
  await expect(page.getByRole('heading', { name: 'Tổng quan rủi ro' })).toBeVisible();
  await page.getByRole('link', { name: 'Rất cao', exact: false }).click();
  await expect(page.getByRole('heading', { name: 'Cảnh báo giao dịch' })).toBeVisible();
  await expect(page.getByLabel('Mức rủi ro')).toHaveValue('CRITICAL');
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await page.getByLabel('Mức rủi ro').selectOption('HIGH');
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await page.getByLabel('Trạng thái điều tra').selectOption('CONFIRMED_FRAUD');
  await expect(page.getByText('Không có cảnh báo phù hợp')).toBeVisible();
  await page.getByRole('navigation').getByRole('link', { name: 'Tổng quan rủi ro', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Tổng quan rủi ro' })).toBeVisible();
  mkdirSync(resolve('../docs/screenshots'), { recursive: true });
  await page.screenshot({ path: '../docs/screenshots/phase-9-risk-dashboard.png', fullPage: true });
});
test('officer reviews four matches, assigns, records notes and resolves without changing the ledger', async ({ page, request }) => {
  const alerts = await (await request.get(`${demo.apiUrl}/api/v1/fraud-alerts?riskLevel=CRITICAL`, { headers: { Authorization: `Bearer ${riskToken}` } })).json();
  const investigationId = alerts.content[0].id;
  const transactionId = alerts.content[0].transactionId;
  const transactionUrl = `${demo.apiUrl}/api/v1/transactions/${transactionId}`;
  const before = await (await request.get(transactionUrl, { headers: { Authorization: `Bearer ${riskToken}` } })).json();
  await login(page, 'risk', `/fraud/alerts/${investigationId}`);
  await expect(page.locator('.evidence-card')).toHaveCount(4);
  await expect(page.locator('.risk-score')).toContainText('100');
  await page.getByLabel('Người phụ trách').selectOption(riskId);
  await page.getByRole('button', { name: 'Lưu phân công' }).click();
  await expect(page.getByRole('button', { name: 'Lưu kết luận' })).toBeVisible();
  await page.getByRole('button', { name: 'Lưu kết luận' }).click();
  await expect(page.getByText('Vui lòng ghi rõ lý do kết luận.')).toBeVisible();
  await page.getByLabel('Ghi chú điều tra').fill('Đã đối chiếu dữ liệu mô phỏng và chứng từ, cảnh báo nhầm.');
  await page.getByRole('button', { name: 'Lưu kết luận' }).click();
  await expect(page.getByText(/Đã kết luận: Cảnh báo nhầm/)).toBeVisible();
  await expect(page.locator('.risk-timeline li')).toHaveCount(2);
  const after = await (await request.get(transactionUrl, { headers: { Authorization: `Bearer ${riskToken}` } })).json();
  expect(after).toEqual(before);
  await page.screenshot({ path: '../docs/screenshots/phase-9-investigation-desktop.png', fullPage: true });
});
test('two officers see a real stale-version conflict and reload the winning assignment', async ({ browser, request }) => {
  const alerts = await (await request.get(`${demo.apiUrl}/api/v1/fraud-alerts?riskLevel=HIGH&status=OPEN`, { headers: { Authorization: `Bearer ${riskToken}` } })).json();
  const id = alerts.content[0].id;
  const firstContext = await browser.newContext(),
    secondContext = await browser.newContext();
  try {
    const first = await firstContext.newPage(), second = await secondContext.newPage();
    await login(first, 'risk', `/fraud/alerts/${id}`);
    await login(second, 'risk2', `/fraud/alerts/${id}`);

    await first.getByLabel('Người phụ trách').selectOption(riskId);
    await second.getByLabel('Người phụ trách').selectOption(secondRiskId);
    await first.getByRole('button', { name: 'Lưu phân công' }).click();
    await expect(first.getByRole('button', { name: 'Lưu kết luận' })).toBeVisible();
    await second.getByRole('button', { name: 'Lưu phân công' }).click();
    await expect(second.getByRole('alert')).toContainText('Cảnh báo đã được cập nhật');
    await expect(second.getByRole('button', { name: 'Lưu phân công' })).toBeDisabled();
    await second.getByRole('button', { name: 'Tải bản mới nhất' }).click();
    await expect(second.getByLabel('Người phụ trách')).toHaveValue(riskId);
    await expect(second.getByRole('button', { name: 'Lưu kết luận' })).toHaveCount(0);
  }
  finally {
    await firstContext.close();
    await secondContext.close();
  }
});
test('admin updates rule versions while old alert evidence stays immutable', async ({ page, request }) => {
  const alerts = await (await request.get(`${demo.apiUrl}/api/v1/fraud-alerts?riskLevel=CRITICAL`, { headers: { Authorization: `Bearer ${riskToken}` } })).json();
  const investigationId = alerts.content[0].id;
  const old = await (await request.get(`${demo.apiUrl}/api/v1/fraud-alerts/${investigationId}`, { headers: { Authorization: `Bearer ${riskToken}` } })).json();
  const initialRules = await (await request.get(`${demo.apiUrl}/api/v1/fraud-rules`, { headers: { Authorization: `Bearer ${adminToken}` } })).json();
  const version = initialRules.find((rule: { code: string }) => rule.code === 'HIGH_AMOUNT').version;
  await login(page, 'admin');
  await expect(page.getByRole('heading', { name: 'Cấu hình quy tắc rủi ro' })).toBeVisible();
  const panel = page.locator('section.panel').filter({ has: page.getByRole('heading', { name: 'Số tiền lớn', exact: true }) });
  await panel.getByLabel('Ngưỡng số tiền VND').fill('100000001');
  await panel.getByRole('button', { name: 'Lưu phiên bản mới' }).click();
  await expect(panel.getByText(`v${version + 1}`, { exact: true })).toBeVisible();
  const newer = await (await request.get(`${demo.apiUrl}/api/v1/fraud-alerts/${investigationId}`, { headers: { Authorization: `Bearer ${riskToken}` } })).json();
  expect(newer.matches).toEqual(old.matches);
  expect(newer.ruleSnapshot).toEqual(old.ruleSnapshot);
  await panel.getByLabel('Ngưỡng số tiền VND').fill('100000000');
  await panel.getByRole('button', { name: 'Lưu phiên bản mới' }).click();
  await expect(panel.getByText(`v${version + 2}`, { exact: true })).toBeVisible();
  const rules = await request.get(`${demo.apiUrl}/api/v1/fraud-rules`, { headers: { Authorization: `Bearer ${adminToken}` } });
  expect(rules.status()).toBe(200);
});
test('customer cannot enter fraud and staff can trace customer to account and ledger', async ({ page, request }) => {
  const denied = await request.get(`${demo.apiUrl}/api/v1/fraud-alerts`, { headers: { Authorization: `Bearer ${customerToken}` } });
  expect(denied.status()).toBe(403);
  await login(page, 'customer', '/fraud/alerts');
  await expect(page.getByRole('heading', { name: /Xin chào/ })).toBeVisible();
  await page.getByRole('button', { name: 'Đăng xuất', exact: true }).click();
  await login(page, 'staff');
  await page.getByLabel('Mã, tên hoặc email khách hàng').fill('PORTAL-LINH');
  await page.getByRole('button', { name: 'Tìm kiếm', exact: true }).click();
  await page.getByRole('link', { name: 'Trần Gia Linh', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Hồ sơ khách hàng' })).toBeVisible();
  await page.locator('tbody a').first().click();
  await expect(page.getByRole('heading', { name: 'Chi tiết tài khoản' })).toBeVisible();
  await page.locator('tbody a').first().click();
  await expect(page.getByRole('heading', { name: 'Đối chiếu giao dịch' })).toBeVisible();
  await expect(page.getByText('Ghi nợ', { exact: true })).toBeVisible();
  await expect(page.getByText('Ghi có', { exact: true })).toBeVisible();
});
test('mobile risk investigation is readable without horizontal page overflow', async ({ page, request }) => {
  const alerts = await (await request.get(`${demo.apiUrl}/api/v1/fraud-alerts?riskLevel=CRITICAL`, { headers: { Authorization: `Bearer ${riskToken}` } })).json();
  const investigationId = alerts.content[0].id;
  await login(page, 'risk', `/fraud/alerts/${investigationId}`);
  await expect(page.getByRole('heading', { name: 'Điều tra giao dịch' })).toBeVisible();
  await expect(page.locator('.evidence-card')).toHaveCount(4);
  await page.screenshot({ path: '../docs/screenshots/phase-9-investigation-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('button', { name: 'Mở menu', exact: true })).toBeVisible();
  await expect(page.locator('aside.sidebar')).not.toBeInViewport();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({
    path: '../docs/screenshots/phase-9-investigation-mobile.png',
    fullPage: true,
    animations: 'disabled'
  });
});
