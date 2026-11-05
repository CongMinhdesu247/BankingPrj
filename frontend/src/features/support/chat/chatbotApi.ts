import { ApiFailure, request } from '../../../shared/api/httpClient';
export interface ChatMessage { role: 'user' | 'model'; text: string }
export interface ChatAnswer { reply: string; source: 'GEMINI' | 'GUIDE' }
function chatbotFailure(error: unknown): never {
  if (error instanceof ApiFailure && error.status === 404) throw new ApiFailure('CHATBOT_NOT_DEPLOYED', 404, error.requestId);
  throw error;
}
export const chatbotApi = {
  status: (signal?: AbortSignal) => request<{ aiEnabled: boolean }>('/me/chatbot', { signal }).catch(chatbotFailure),
  ask: (message: string, history: ChatMessage[], signal?: AbortSignal) => request<ChatAnswer>('/me/chatbot', {
    method: 'POST', body: { message, history, consent: true }, signal, timeoutMs: 30000,
  }).catch(chatbotFailure),
};
