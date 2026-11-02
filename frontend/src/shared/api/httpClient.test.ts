import { afterEach, expect, it, vi } from 'vitest';
import { ApiFailure, errorMessage, request, setAccessToken, setUnauthorizedHandler } from './httpClient';
afterEach(() => { setAccessToken(null); setUnauthorizedHandler(undefined); vi.unstubAllEnvs(); });
it.each(['demo.ngrok-free.dev', 'demo.ngrok-free.app'])('requests JSON without the ngrok warning on %s, without sending cookies', async hostname => {
  vi.stubGlobal('window', { location: new URL(`https://${hostname}/login`) });
  const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
  vi.stubGlobal('fetch', fetch);
  await request('/me');
  const options = fetch.mock.calls[0][1];
  expect(options.headers['ngrok-skip-browser-warning']).toBe('1');
  expect(options.credentials).toBe('omit');
});
it('does not send the ngrok header on localhost', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
  vi.stubGlobal('fetch', fetch);
  await request('/me');
  expect(fetch.mock.calls[0][1].headers).not.toHaveProperty('ngrok-skip-browser-warning');
});
it('does not send the ngrok header to an API on another origin', async () => {
  vi.stubGlobal('window', { location: new URL('https://demo.ngrok-free.dev/login') });
  vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.test/api/v1');
  const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
  vi.stubGlobal('fetch', fetch);
  await request('/me');
  expect(fetch.mock.calls[0][1].headers).not.toHaveProperty('ngrok-skip-browser-warning');
});
it('sends the exact money string and original idempotency key without cookies', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 201 })); vi.stubGlobal('fetch', fetch); setAccessToken('in-memory-test-token');
  await request('/transfers', { method: 'POST', body: { amount: '99999999999999999' }, key: 'same-key' });
  const options = fetch.mock.calls[0][1]; expect(options.headers.Authorization).toBe('Bearer in-memory-test-token'); expect(options.headers['Idempotency-Key']).toBe('same-key'); expect(options.body).toContain('"99999999999999999"'); expect(options.credentials).toBe('omit'); expect(localStorage.length).toBe(0);
});
it.each([[401, 'UNAUTHENTICATED'], [403, 'FORBIDDEN'], [409, 'INSUFFICIENT_FUNDS'], [429, 'RATE_LIMITED'], [503, 'THROTTLE_UNAVAILABLE'], [500, 'INTERNAL_ERROR']])('maps HTTP %s to a safe localized error', async (status, code) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code, requestId: 'safe-request' }), { status })));
  const error = await request('/me').catch(error => error) as ApiFailure; expect(error).toBeInstanceOf(ApiFailure); expect(error.status).toBe(status); expect(error.requestId).toBe('safe-request'); expect(errorMessage(error)).not.toBe(code);
});
it('expires only the current authenticated session on 401', async () => {
  const unauthorized = vi.fn(); setUnauthorizedHandler(unauthorized); setAccessToken('old-session');
  let resolve!: (response: Response) => void;
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(done => { resolve = done; })));
  const pending = request('/me').catch(error => error); setAccessToken('new-session'); resolve(new Response('{}', { status: 401 })); await pending; expect(unauthorized).not.toHaveBeenCalled();
});
it('calls the expiry handler on a current token’s 401', async () => {
  const unauthorized = vi.fn(); setUnauthorizedHandler(unauthorized); setAccessToken('current-session'); vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 401 })));
  await request('/me').catch(() => undefined); expect(unauthorized).toHaveBeenCalledOnce();
});
it('recognizes a new session even if the issuer returns the same JWT string', async () => {
  const unauthorized = vi.fn(); setUnauthorizedHandler(unauthorized); setAccessToken('same-jwt-within-one-second');
  let resolve!: (response: Response) => void; vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(done => { resolve = done; })));
  const pending = request('/me').catch(error => error); setAccessToken('same-jwt-within-one-second'); resolve(new Response('{}', { status: 401 })); await pending; expect(unauthorized).not.toHaveBeenCalled();
});
it('treats network loss and a malformed success body as unknown outcomes', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline'))); expect((await request('/transfers').catch(error => error) as ApiFailure).outcomeUnknown).toBe(true);
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('bad-json', { status: 201 }))); expect((await request('/transfers').catch(error => error) as ApiFailure).code).toBe('INVALID_RESPONSE');
});
it('has a bounded timeout and never automatically retries POST', async () => {
  vi.useFakeTimers(); const fetch = vi.fn((_url, options: RequestInit) => new Promise((_resolve, reject) => options.signal!.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))));
  vi.stubGlobal('fetch', fetch); const pending = request('/transfers', { method: 'POST', timeoutMs: 50 }).catch(error => error); await vi.advanceTimersByTimeAsync(51); expect((await pending as ApiFailure).code).toBe('REQUEST_TIMEOUT'); expect(fetch).toHaveBeenCalledOnce();
});
