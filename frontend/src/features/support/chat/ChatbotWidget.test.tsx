import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import { ChatbotWidget } from './ChatbotWidget';
import { chatbotApi } from './chatbotApi';
import { ApiFailure } from '../../../shared/api/httpClient';
vi.mock('./chatbotApi', () => ({ chatbotApi: { status: vi.fn(), ask: vi.fn() } }));
beforeEach(() => { vi.mocked(chatbotApi.status).mockResolvedValue({ aiEnabled: true }); vi.mocked(chatbotApi.ask).mockResolvedValue({ reply: 'Chọn tài khoản và tháng.', source: 'GEMINI' }); });
async function mount() { render(<MemoryRouter><ChatbotWidget /></MemoryRouter>); await userEvent.click(screen.getByRole('button', { name: 'Hỏi trợ lý' })); }
it('requires consent, answers and sends bounded conversation context', async () => {
  await mount(); await userEvent.type(screen.getByLabelText('Câu hỏi của bạn'), 'Xem sao kê?');
  expect(screen.getByRole('button', { name: 'Gửi câu hỏi' })).toBeDisabled(); expect(chatbotApi.ask).not.toHaveBeenCalled();
  await userEvent.click(screen.getByRole('checkbox')); await userEvent.click(screen.getByRole('button', { name: 'Gửi câu hỏi' }));
  expect(await screen.findByText('Chọn tài khoản và tháng.')).toBeInTheDocument();
  expect(chatbotApi.ask).toHaveBeenCalledWith('Xem sao kê?', [], expect.any(AbortSignal));
  await userEvent.type(screen.getByLabelText('Câu hỏi của bạn'), 'Tiếp theo?'); await userEvent.click(screen.getByRole('button', { name: 'Gửi câu hỏi' }));
  await waitFor(() => expect(chatbotApi.ask).toHaveBeenCalledWith('Tiếp theo?', [{ role: 'user', text: 'Xem sao kê?' }, { role: 'model', text: 'Chọn tài khoản và tháng.' }], expect.any(AbortSignal)));
});
it('keeps the question after quota errors and provides the support link', async () => {
  vi.mocked(chatbotApi.ask).mockRejectedValue(new ApiFailure('AI_QUOTA_EXCEEDED', 429));
  await mount(); await userEvent.click(screen.getByRole('checkbox')); await userEvent.type(screen.getByLabelText('Câu hỏi của bạn'), 'Cần hỗ trợ');
  await userEvent.click(screen.getByRole('button', { name: 'Gửi câu hỏi' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('hết hạn mức'); expect(screen.getByLabelText('Câu hỏi của bạn')).toHaveValue('Cần hỗ trợ');
  expect(screen.getByRole('link', { name: /Mở tra soát/ })).toHaveAttribute('href', '/support');
});
it('labels local guides honestly and clears the conversation', async () => {
  vi.mocked(chatbotApi.status).mockResolvedValue({ aiEnabled: false }); vi.mocked(chatbotApi.ask).mockResolvedValue({ reply: 'Mở Lịch sử giao dịch.', source: 'GUIDE' });
  await mount(); expect(await screen.findByText('Hướng dẫn có sẵn · AI chưa bật')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Làm sao để chuyển tiền?' })); await userEvent.click(screen.getByRole('checkbox')); await userEvent.click(screen.getByRole('button', { name: 'Gửi câu hỏi' }));
  expect(await screen.findByText('Mở Lịch sử giao dịch.')).toBeInTheDocument(); expect(screen.getByText('Hướng dẫn có sẵn')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Xóa cuộc trò chuyện' })); expect(screen.queryByText('Mở Lịch sử giao dịch.')).not.toBeInTheDocument();
});
it('returns keyboard focus to the launcher on Escape', async () => {
  await mount(); await userEvent.keyboard('{Escape}'); expect(screen.queryByRole('region', { name: 'Trợ lý hỗ trợ' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Hỏi trợ lý' })).toHaveFocus();
});

it('explains a stale backend instead of silently hiding the status failure', async () => {
  vi.mocked(chatbotApi.status).mockRejectedValue(new ApiFailure('CHATBOT_NOT_DEPLOYED', 404));
  await mount(); expect(await screen.findByRole('alert')).toHaveTextContent('Backend đang dùng chưa có chatbot');
});

it('expands the panel and adjusts readable text sizes without losing a draft', async () => {
  const user = userEvent.setup(); await mount();
  await user.type(screen.getByLabelText('Câu hỏi của bạn'), 'Cần hỗ trợ');
  await user.click(screen.getByRole('button', { name: 'Phóng to khung chat' }));
  expect(screen.getByRole('button', { name: 'Thu nhỏ khung chat' })).toHaveAttribute('aria-pressed', 'true');
  await user.click(screen.getByRole('button', { name: 'Tăng cỡ chữ' }));
  expect(screen.getByLabelText('Cỡ chữ hiện tại')).toHaveTextContent('18px');
  await user.click(screen.getByRole('button', { name: 'Giảm cỡ chữ' }));
  expect(screen.getByLabelText('Cỡ chữ hiện tại')).toHaveTextContent('16px');
  await user.click(screen.getByRole('button', { name: 'Thu nhỏ khung chat' }));
  expect(screen.getByLabelText('Câu hỏi của bạn')).toHaveValue('Cần hỗ trợ');
});
