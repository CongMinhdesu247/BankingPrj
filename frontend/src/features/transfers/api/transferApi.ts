import { request } from '../../../shared/api/httpClient';
import type { TransactionDetail, TransferPayload } from '../../../shared/types/api';
export const transferApi = {
  send: (payload: TransferPayload, key: string) => request<TransactionDetail>('/transfers', { method: 'POST', body: payload, key }),
};
