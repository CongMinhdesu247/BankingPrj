import { beforeEach, expect, it, vi } from 'vitest';
import { ApiFailure, request } from '../../../shared/api/httpClient';
import { chatbotApi } from './chatbotApi';
vi.mock('../../../shared/api/httpClient', async importOriginal => ({ ...await importOriginal<typeof import('../../../shared/api/httpClient')>(), request: vi.fn() }));
beforeEach(() => { vi.mocked(request).mockReset(); });
it('identifies a missing backend route for status and send requests', async () => {
  vi.mocked(request).mockRejectedValue(new ApiFailure('RESOURCE_NOT_FOUND', 404, 'trace'));
  for (const action of [() => chatbotApi.status(), () => chatbotApi.ask('Hướng dẫn', [])]) {
    await expect(action()).rejects.toMatchObject({ code: 'CHATBOT_NOT_DEPLOYED', status: 404, requestId: 'trace' });
  }
});
it('preserves quota errors', async () => {
  vi.mocked(request).mockRejectedValue(new ApiFailure('AI_QUOTA_EXCEEDED', 429));
  await expect(chatbotApi.ask('Hướng dẫn', [])).rejects.toMatchObject({ code: 'AI_QUOTA_EXCEEDED', status: 429 });
});
