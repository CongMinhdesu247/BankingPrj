import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import type { Me } from '../../../shared/types/api';
import { fraudApi, type AlertDetail, type FraudAlert, type FraudRule } from '../api/fraudApi';
import { ApiFailure } from '../../../shared/api/httpClient';
import { ProtectedRoute } from '../../../app/routes/ProtectedRoute';
import { FraudDashboardPage } from './FraudDashboardPage';
import { FraudAlertsPage } from './FraudAlertsPage';
import { FraudInvestigationPage } from './FraudInvestigationPage';
import { FraudRulesPage } from './FraudRulesPage';
vi.mock('../api/fraudApi', async importOriginal => {
  const original = await importOriginal<typeof import('../api/fraudApi')>();
  return {
    ...original, fraudApi: {
      summary: vi.fn(),
      alerts: vi.fn(),
      detail: vi.fn(),
      officers: vi.fn(),
      assign: vi.fn(),
      resolve: vi.fn(),
      rules: vi.fn(),
      updateRule: vi.fn()
    }
  };
});
let me = { user: { id: 'risk-1', username: 'risk.one', roles: ['RISK_OFFICER'] }, customer: null } as Me;
vi.mock('../../auth/providers/AuthProvider', () => ({ useAuth: () => ({ me }) }));
const alert: FraudAlert = {
  id: 'alert-1',
  transactionId: 'transaction-1',
  reference: 'TRF-TEST',
  amount: '100000000.00',
  currency: 'VND',
  transactionAt: '2026-10-27T12:00:00Z',
  sourceAccountId: 'source',
  destinationAccountId: 'destination',
  analysisVersion: 1,
  score: 70,
  riskLevel: 'HIGH',
  status: 'OPEN',
  assignedTo: null,
  assignedUsername: null,
  notes: null,
  resolvedAt: null,
  version: 0,
  createdAt: '2026-10-27T12:00:01Z',
  updatedAt: '2026-10-27T12:00:01Z'
};
const detail: AlertDetail = {
  alert,
  matches: [{
    id: 'match-1',
    ruleCode: 'HIGH_AMOUNT',
    ruleVersion: 1,
    scoreContribution: 40,
    observedValue: { amount: '100000000.00' },
    thresholdSnapshot: { amount: '100000000', count: null, windowSeconds: null },
    reason: 'AMOUNT_AT_OR_ABOVE_THRESHOLD'
  }],
  contextSnapshot: {},
  ruleSnapshot: [],
  history: []
};
const rule: FraudRule = {
  id: 'rule-1',
  code: 'HIGH_AMOUNT',
  version: 1,
  threshold: { amount: '100000000', count: null, windowSeconds: null },
  weight: 40,
  enabled: true,
  updatedBy: null,
  updatedAt: '1970-01-01T00:00:00Z'
};
beforeEach(() => {
  vi.clearAllMocks();
  me = { user: { id: 'risk-1', username: 'risk.one', roles: ['RISK_OFFICER'] }, customer: null } as Me;
  vi.mocked(fraudApi.detail).mockResolvedValue(detail);
  vi.mocked(fraudApi.officers).mockResolvedValue([{ id: 'risk-1', username: 'risk.one' }]);
  vi.mocked(fraudApi.rules).mockResolvedValue([rule]);
  vi.mocked(fraudApi.alerts).mockResolvedValue({ content: [alert], page: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true });
});
function mount(element: React.ReactNode, path = '/fraud') {
  return render(<MemoryRouter initialEntries={[path]}>
    <Routes>
      <Route path="/fraud/alerts/:id" element={element} />
      <Route path="*" element={element} />
    </Routes>
  </MemoryRouter>);
}
it('shows real summary values and makes the meaning of risk scores explicit', async () => {
  vi.mocked(fraudApi.summary).mockResolvedValue({
    totalAlerts: 7,
    open: 3,
    inReview: 2,
    confirmedFraud: 1,
    falsePositive: 1,
    highPriorityOpen: 2,
    analyzedTransfers: 28,
    pendingTransfers: 4,
    byRiskLevel: { LOW: 1, MEDIUM: 3, HIGH: 2, CRITICAL: 1 },
    lastAnalyzedAt: '2026-10-27T12:00:00Z'
  });
  mount(<FraudDashboardPage />);
  expect(await screen.findByText('28')).toBeInTheDocument();
  expect(screen.getByText(/không phải xác suất/)).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /Rất cao/ })).toHaveAttribute('href', '/fraud/alerts?riskLevel=CRITICAL');
});
it('shows a controlled loading failure and retries the dashboard', async () => {
  vi.mocked(fraudApi.summary).mockRejectedValue(new ApiFailure('INTERNAL_ERROR', 500));
  mount(<FraudDashboardPage />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Máy chủ đang gặp sự cố');
  await userEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
  expect(fraudApi.summary).toHaveBeenCalledTimes(2);
});
it('sends server-side status and risk filters and handles an empty result', async () => {
  mount(<FraudAlertsPage />, '/fraud/alerts?riskLevel=HIGH');
  await screen.findByText('TRF-TEST');
  expect(fraudApi.alerts).toHaveBeenLastCalledWith({ page: 0, status: '', riskLevel: 'HIGH' }, expect.any(AbortSignal));
  vi.mocked(fraudApi.alerts).mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true });
  await userEvent.selectOptions(screen.getByLabelText('Trạng thái điều tra'), 'IN_REVIEW');
  expect(await screen.findByText('Không có cảnh báo phù hợp')).toBeInTheDocument();
  expect(fraudApi.alerts).toHaveBeenLastCalledWith({ page: 0, status: 'IN_REVIEW', riskLevel: 'HIGH' }, expect.any(AbortSignal));
});
it('assigns with the current version then requires notes to resolve with the next version', async () => {
  const assigned: AlertDetail = { ...detail, alert: { ...alert, assignedTo: 'risk-1', assignedUsername: 'risk.one', status: 'IN_REVIEW', version: 1 } };
  vi.mocked(fraudApi.assign).mockResolvedValue(assigned);
  vi.mocked(fraudApi.resolve).mockResolvedValue({
    ...assigned, alert: {
      ...assigned.alert,
      status: 'FALSE_POSITIVE',
      notes: 'Đã đối chiếu',
      resolvedAt: '2026-10-27T12:10:00Z',
      version: 2
    }
  });
  mount(<FraudInvestigationPage />, '/fraud/alerts/alert-1');
  await screen.findByText('Số tiền đạt hoặc vượt ngưỡng');
  const user = userEvent.setup();
  await user.selectOptions(screen.getByLabelText('Người phụ trách'), 'risk-1');
  await user.click(screen.getByRole('button', { name: 'Lưu phân công' }));
  expect(fraudApi.assign).toHaveBeenCalledWith('alert-1', 0, 'risk-1');
  await user.click(await screen.findByRole('button', { name: 'Lưu kết luận' }));
  expect(fraudApi.resolve).not.toHaveBeenCalled();
  expect(screen.getByText('Vui lòng ghi rõ lý do kết luận.')).toBeInTheDocument();
  await user.type(screen.getByLabelText('Ghi chú điều tra'), ' Đã đối chiếu ');
  await user.click(screen.getByRole('button', { name: 'Lưu kết luận' }));
  expect(fraudApi.resolve).toHaveBeenCalledWith('alert-1', 1, 'FALSE_POSITIVE', 'Đã đối chiếu');
  expect(await screen.findByText(/Giao dịch gốc được giữ nguyên/)).toBeInTheDocument();
});
it('blocks mutation after a stale-version conflict until the officer reloads', async () => {
  vi.mocked(fraudApi.assign).mockRejectedValue(new ApiFailure('FRAUD_ALERT_CONFLICT', 409));
  mount(<FraudInvestigationPage />, '/fraud/alerts/alert-1');
  const button = await screen.findByRole('button', { name: 'Lưu phân công' });
  await userEvent.click(button);
  await screen.findByRole('alert');
  expect(button).toBeDisabled();
  await userEvent.click(screen.getByRole('button', { name: 'Tải bản mới nhất' }));
  expect(await screen.findByRole('button', { name: 'Lưu phân công' })).toBeEnabled();
});
it('never offers resolution to an officer who is not the assignee', async () => {
  vi.mocked(fraudApi.detail).mockResolvedValue({ ...detail, alert: { ...alert, status: 'IN_REVIEW', assignedTo: 'risk-other' } });
  mount(<FraudInvestigationPage />, '/fraud/alerts/alert-1');
  await screen.findByText('Chỉ người đang phụ trách mới có thể kết luận cảnh báo.');
  expect(screen.queryByRole('button', { name: 'Lưu kết luận' })).not.toBeInTheDocument();
});
it('keeps money thresholds as exact strings and rejects fractional amounts before updating', async () => {
  mount(<FraudRulesPage />);
  await screen.findByRole('heading', { name: 'Số tiền lớn' });
  const user = userEvent.setup(), input = screen.getByLabelText('Ngưỡng số tiền VND');
  await user.clear(input);
  await user.type(input, '1.5');
  await user.click(screen.getByRole('button', { name: 'Lưu phiên bản mới' }));
  expect(fraudApi.updateRule).not.toHaveBeenCalled();
  await user.clear(input);
  await user.type(input, '99999999999999999');
  vi.mocked(fraudApi.updateRule).mockResolvedValue({ ...rule, version: 2 });
  await user.click(screen.getByRole('button', { name: 'Lưu phiên bản mới' }));
  expect(fraudApi.updateRule).toHaveBeenCalledWith(rule, { amount: '99999999999999999', count: null, windowSeconds: null }, 40, true);
  expect(await screen.findByText('v2')).toBeInTheDocument();
});
it('keeps risk-only routes inaccessible to a customer session', () => {
  me = { ...me, user: { ...me.user, roles: ['CUSTOMER'] } };
  render(<MemoryRouter initialEntries={['/fraud']}>
    <Routes>
      <Route path="/" element={<div>Customer home</div>} />
      <Route element={<ProtectedRoute roles={['RISK_OFFICER']} />}>
        <Route path="/fraud" element={<div>Private fraud evidence</div>} />
      </Route>
    </Routes>
  </MemoryRouter>);
  expect(screen.getByText('Customer home')).toBeInTheDocument();
  expect(screen.queryByText('Private fraud evidence')).not.toBeInTheDocument();
});
