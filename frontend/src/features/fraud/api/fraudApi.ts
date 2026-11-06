import { request } from '../../../shared/api/httpClient';
import type { Page } from '../../../shared/types/api';
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type AlertStatus = 'OPEN' | 'IN_REVIEW' | 'CONFIRMED_FRAUD' | 'FALSE_POSITIVE';
export type RuleCode = 'HIGH_AMOUNT' | 'VELOCITY' | 'NEW_BENEFICIARY' | 'FAILED_LOGIN_CONTEXT';
export interface RuleThreshold {
  amount: string | null;
  count: number | null;
  windowSeconds: number | null
}
export interface FraudRule {
  id: string;
  code: RuleCode;
  version: number;
  threshold: RuleThreshold;
  weight: number;
  enabled: boolean;
  updatedBy: string | null;
  updatedAt: string
}
export interface FraudAlert {
  id: string;
  transactionId: string;
  reference: string;
  amount: string;
  currency: string;
  transactionAt: string;
  sourceAccountId: string;
  destinationAccountId: string;
  analysisVersion: number;
  score: number;
  riskLevel: RiskLevel;
  status: AlertStatus;
  assignedTo: string | null;
  assignedUsername: string | null;
  notes: string | null;
  resolvedAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string
}
export interface RuleMatch {
  id: string;
  ruleCode: RuleCode;
  ruleVersion: number;
  scoreContribution: number;
  observedValue: Record<string, unknown>;
  thresholdSnapshot: RuleThreshold;
  reason: string
}
export interface AlertDetail {
  alert: FraudAlert;
  matches: RuleMatch[];
  ruleSnapshot: FraudRule[];
  contextSnapshot: Record<string, unknown>;
  history: {
    action: string;
    actorId: string;
    details: Record<string, unknown>;
    createdAt: string
  }[]
}
export interface Officer { id: string; username: string }
export interface FraudSummary {
  totalAlerts: number;
  open: number;
  inReview: number;
  confirmedFraud: number;
  falsePositive: number;
  highPriorityOpen: number;
  analyzedTransfers: number;
  pendingTransfers: number;
  byRiskLevel: Record<RiskLevel, number>;
  lastAnalyzedAt: string | null
}
export const ruleLabels: Record<RuleCode, string> = {
  HIGH_AMOUNT: 'Số tiền lớn',
  VELOCITY: 'Tần suất chuyển tiền',
  NEW_BENEFICIARY: 'Người nhận mới',
  FAILED_LOGIN_CONTEXT: 'Đăng nhập sai trước giao dịch'
};
export const statusLabels: Record<AlertStatus, string> = {
  OPEN: 'Chưa phân công',
  IN_REVIEW: 'Đang điều tra',
  CONFIRMED_FRAUD: 'Xác nhận gian lận',
  FALSE_POSITIVE: 'Cảnh báo nhầm'
};
export const riskLabels: Record<RiskLevel, string> = { LOW: 'Thấp', MEDIUM: 'Trung bình', HIGH: 'Cao', CRITICAL: 'Rất cao' };
export const fraudApi = {
  summary: (signal?: AbortSignal) => request<FraudSummary>('/fraud/summary', { signal }),
  alerts: (query: { page?: number; status?: string; riskLevel?: string; transactionId?: string } = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams({ page: String(query.page ?? 0), size: '20' });
    if (query.status) params.set('status', query.status);
    if (query.riskLevel) params.set('riskLevel', query.riskLevel);
    if (query.transactionId) params.set('transactionId', query.transactionId);
    return request<Page<FraudAlert>>(`/fraud-alerts?${params}`, { signal });
  },
  detail: (id: string, signal?: AbortSignal) => request<AlertDetail>(`/fraud-alerts/${encodeURIComponent(id)}`, { signal }),
  officers: (signal?: AbortSignal) => request<Officer[]>('/fraud/officers', { signal }),
  assign: (id: string, expectedVersion: number, assignedTo: string | null) => request<AlertDetail>(`/fraud-alerts/${encodeURIComponent(id)}/assignment`, { method: 'PATCH', body: { expectedVersion, assignedTo } }),
  resolve: (id: string, expectedVersion: number, resolution: 'CONFIRMED_FRAUD' | 'FALSE_POSITIVE', notes: string) => request<AlertDetail>(`/fraud-alerts/${encodeURIComponent(id)}/resolution`, { method: 'PATCH', body: { expectedVersion, resolution, notes } }),
  rules: (signal?: AbortSignal) => request<FraudRule[]>('/fraud-rules', { signal }),
  updateRule: (rule: FraudRule, threshold: RuleThreshold, weight: number, enabled: boolean) => request<FraudRule>(`/fraud-rules/${encodeURIComponent(rule.id)}`, { method: 'PATCH', body: { expectedVersion: rule.version, threshold, weight, enabled } }),
};
