import { request } from '../../../shared/api/httpClient';
export interface CashflowDay { date: string; incoming: string; outgoing: string }
export interface Cashflow {
  from: string;
  to: string;
  timezone: string;
  asOf: string;
  incoming: string;
  outgoing: string;
  net: string;
  series: CashflowDay[]
}
export const cashflowApi = { get: (days: number, signal?: AbortSignal) => request<Cashflow>(`/me/cashflow?days=${days}`, { signal }) };
