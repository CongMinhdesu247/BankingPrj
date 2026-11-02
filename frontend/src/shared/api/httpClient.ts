import type { ErrorBody } from '../types/api';

let accessToken: string | null = null;
let sessionGeneration = 0;
let onUnauthorized: (() => void) | undefined;
export const setAccessToken = (token: string | null) => { accessToken = token; sessionGeneration++; };
export const setUnauthorizedHandler = (handler: (() => void) | undefined) => { onUnauthorized = handler; };

export class ApiFailure extends Error {
  constructor(public readonly code: string, public readonly status = 0, public readonly requestId?: string) {
    super(code); this.name = 'ApiFailure';
  }
  get outcomeUnknown() { return this.status === 0 || this.status >= 500 || this.status === 429 || this.status === 401 || this.code === 'IDEMPOTENCY_CONFLICT'; }
}
const messages: Record<string, string> = { SECURITY_VERIFICATION_FAILED: 'Mật khẩu hoặc mã xác thực chưa đúng. Mã OTP đã dùng không thể dùng lại.', MFA_REQUIRED: 'Nhập thêm mã OTP hoặc mã khôi phục để đăng nhập.', INVALID_RESET_TOKEN: 'Liên kết khôi phục không hợp lệ hoặc đã hết hạn.', LIMIT_INCREASE_REQUIRED: 'Tăng hạn mức cần gửi yêu cầu để admin phê duyệt.', TEMPLATE_NOT_FOUND: 'Không tìm thấy mẫu chuyển tiền của bạn.', TICKET_NOT_FOUND: 'Không tìm thấy yêu cầu tra soát của bạn.', REQUEST_NOT_FOUND: 'Không tìm thấy yêu cầu hạn mức.', INVALID_WORKFLOW_STATE: 'Trạng thái đã thay đổi hoặc thao tác không còn khả dụng. Hãy làm mới dữ liệu.', INVALID_LIMIT: 'Hạn mức chưa hợp lệ hoặc vượt trần ngân hàng.', DATA_CONFLICT: 'Yêu cầu đang tồn tại hoặc tên đã được sử dụng. Hãy kiểm tra danh sách trước khi gửi lại.', USER_CONFLICT: 'Tên đăng nhập đã được sử dụng.',
  INVALID_CREDENTIALS: 'Tên đăng nhập hoặc mật khẩu chưa đúng.',
  UNAUTHENTICATED: 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.',
  FORBIDDEN: 'Tài khoản của bạn không có quyền thực hiện thao tác này.',
  ACCOUNT_NOT_FOUND: 'Không tìm thấy tài khoản hoặc bạn không có quyền truy cập.',
  TRANSACTION_NOT_FOUND: 'Không tìm thấy giao dịch thuộc quyền truy cập của bạn.',
  ACCOUNT_NOT_ACTIVE: 'Tài khoản nguồn hoặc tài khoản nhận hiện không hoạt động.',
  ACCOUNT_CLOSED: 'Tài khoản đã đóng.',
  INSUFFICIENT_FUNDS: 'Số dư tài khoản nguồn không đủ để chuyển tiền.',
  PER_TRANSFER_LIMIT_EXCEEDED: 'Số tiền vượt hạn mức của một lần chuyển.',
  DAILY_LIMIT_EXCEEDED: 'Số tiền vượt hạn mức chuyển còn lại trong ngày.',
  BALANCE_LIMIT_EXCEEDED: 'Tài khoản nhận không thể nhận thêm số tiền này.',
  CURRENCY_MISMATCH: 'Tài khoản phải sử dụng đồng Việt Nam (VND).',
  SAME_ACCOUNT: 'Tài khoản nguồn và tài khoản nhận phải khác nhau.',
  INVALID_AMOUNT: 'Vui lòng nhập số tiền VND nguyên lớn hơn 0.',
  INVALID_REQUEST: 'Thông tin gửi lên chưa hợp lệ. Vui lòng kiểm tra lại.',
  VALIDATION_ERROR: 'Vui lòng kiểm tra các thông tin đã nhập.',
  BENEFICIARY_CONFLICT: 'Tài khoản này đã có trong danh sách người thụ hưởng.',
  BENEFICIARY_NOT_FOUND: 'Người thụ hưởng không còn tồn tại trong danh sách của bạn.',
  IDEMPOTENCY_CONFLICT: 'Mã yêu cầu đã gắn với nội dung khác. Hãy kiểm tra lịch sử trước khi tiếp tục.',
  INVALID_IDEMPOTENCY_KEY: 'Mã yêu cầu không hợp lệ. Vui lòng liên hệ người quản lý ứng dụng.',
  ACCOUNT_BUSY: 'Tài khoản đang xử lý yêu cầu khác. Bạn có thể thử lại sau ít giây.',
  IDEMPOTENCY_BUSY: 'Yêu cầu chưa hoàn tất. Vui lòng thử lại với yêu cầu hiện tại.',
  RATE_LIMITED: 'Bạn thao tác quá nhanh. Vui lòng đợi một lát rồi thử lại.',
  THROTTLE_UNAVAILABLE: 'Dịch vụ bảo vệ yêu cầu đang tạm gián đoạn. Vui lòng thử lại sau ít giây.',
  FRAUD_ALERT_NOT_FOUND: 'Không tìm thấy cảnh báo này.',
  NOTIFICATION_NOT_FOUND: 'Không tìm thấy thông báo thuộc quyền truy cập của bạn.',
  FRAUD_RULE_NOT_FOUND: 'Không tìm thấy quy tắc này.',
  FRAUD_ALERT_CONFLICT: 'Cảnh báo đã được cập nhật. Hãy tải bản mới nhất trước khi tiếp tục.',
  FRAUD_ALERT_STATE_CONFLICT: 'Trạng thái hoặc người phụ trách đã thay đổi. Hãy tải lại cảnh báo.',
  FRAUD_RULE_CONFLICT: 'Cấu hình đã thay đổi. Hãy tải cấu hình mới nhất rồi kiểm tra lại.',
  INVALID_FRAUD_RULE: 'Ngưỡng hoặc thông số quy tắc chưa hợp lệ.',
  INVALID_ASSIGNEE: 'Người phụ trách phải là nhân viên rủi ro đang hoạt động.',
  NETWORK_ERROR: 'Không kết nối được với máy chủ. Vui lòng kiểm tra kết nối.',
  REQUEST_TIMEOUT: 'Máy chủ chưa phản hồi trong thời gian chờ.',
  INVALID_RESPONSE: 'Chưa nhận được phản hồi hợp lệ từ máy chủ.',
  STORAGE_UNAVAILABLE: 'Trình duyệt chưa cho phép lưu yêu cầu tạm. Vui lòng cho phép sessionStorage trước khi chuyển tiền.',
  INTERNAL_ERROR: 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.',
};
export function errorMessage(error: unknown): string {
  return error instanceof ApiFailure ? messages[error.code] ?? 'Chưa thể hoàn tất yêu cầu. Vui lòng thử lại.' : 'Đã có lỗi xảy ra. Vui lòng thử lại.';
}
interface Options { method?: string; body?: unknown; key?: string; signal?: AbortSignal; auth?: boolean; timeoutMs?: number }
export async function request<T>(path: string, options: Options = {}): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), options.timeoutMs ?? 15000);
  const signal = options.signal ? AbortSignal.any([controller.signal, options.signal]) : controller.signal;
  const token = options.auth === false ? null : accessToken;
  const generation = sessionGeneration;
  const headers: Record<string, string> = { Accept: 'application/json', 'X-Request-Id': crypto.randomUUID() };
  const url = `${import.meta.env.VITE_API_BASE_URL || '/api/v1'}${path}`;
  // API calls omit cookies, including ngrok's browser-warning acknowledgement.
  // Ask ngrok for the API response instead of its interstitial page.
  if (/\.ngrok-free\.(app|dev)$/.test(window.location.hostname)
      && new URL(url, window.location.href).origin === window.location.origin) {
    headers['ngrok-skip-browser-warning'] = '1';
  }
  if (token) headers.Authorization = `Bearer ${token}`;
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (options.key) headers['Idempotency-Key'] = options.key;
  try {
    const response = await fetch(url, {
      method: options.method ?? 'GET', headers, signal, credentials: 'omit', cache: 'no-store', redirect: 'error',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
    if (response.status === 401 && token && token === accessToken && generation === sessionGeneration) onUnauthorized?.();
    if (!response.ok) {
      let error: Partial<ErrorBody> = {};
      try { error = await response.json(); } catch { /* Proxies may return HTML. Never echo it. */ }
      throw new ApiFailure(error.code ?? (response.status === 429 ? 'RATE_LIMITED' : 'INTERNAL_ERROR'), response.status, error.requestId);
    }
    if (response.status === 204) return undefined as T;
    try { return await response.json() as T; }
    catch { throw new ApiFailure('INVALID_RESPONSE'); }
  } catch (error) {
    if (options.signal?.aborted) throw error;
    if (error instanceof ApiFailure) throw error;
    throw new ApiFailure(controller.signal.aborted ? 'REQUEST_TIMEOUT' : 'NETWORK_ERROR');
  } finally { clearTimeout(timer); }
}
