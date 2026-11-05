import { useEffect, useRef, useState, type CSSProperties, type FormEvent } from 'react';
import { Link } from 'react-router';
import { MessageCircle, Send, X, RotateCcw, Maximize2, Minimize2, Minus, Plus } from 'lucide-react';
import { chatbotApi, type ChatMessage } from './chatbotApi';
import { errorMessage } from '../../../shared/api/httpClient';
import { ChatMessageText } from './ChatMessageText';
import './chatbot.css';
const suggestions = ['Làm sao để chuyển tiền?', 'Tôi muốn tăng hạn mức', 'Xem sao kê ở đâu?'];
export function ChatbotWidget() {
  const [open, setOpen] = useState(false), [consent, setConsent] = useState(false);
  const [messages, setMessages] = useState<(ChatMessage & { source?: string })[]>([]);
  const [draft, setDraft] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const [expanded, setExpanded] = useState(false), [fontSize, setFontSize] = useState(16);
  const [aiEnabled, setAiEnabled] = useState<boolean>();
  const pending = useRef<AbortController | null>(null), locked = useRef(false);
  const input = useRef<HTMLTextAreaElement>(null), launcher = useRef<HTMLButtonElement>(null), end = useRef<HTMLDivElement>(null);
  useEffect(() => () => pending.current?.abort(), []);
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    void chatbotApi.status(controller.signal).then(status => setAiEnabled(status.aiEnabled)).catch(reason => { if (!controller.signal.aborted) setError(errorMessage(reason)); });
    input.current?.focus();
    return () => controller.abort();
  }, [open]);
  useEffect(() => { if (open) end.current?.scrollIntoView?.({ block: 'nearest' }); }, [messages, busy, open, expanded, fontSize]);
  function close() { setOpen(false); launcher.current?.focus(); }
  async function send(event: FormEvent) {
    event.preventDefault();
    const text = draft.trim();
    if (!text || !consent || locked.current) return;
    locked.current = true; setBusy(true); setError('');
    const controller = new AbortController(); pending.current = controller;
    try {
      const answer = await chatbotApi.ask(text, messages.slice(-10).map(({ role, text }) => ({ role, text: text.slice(0, 2000) })), controller.signal);
      if (controller.signal.aborted) return;
      setMessages(previous => [...previous, { role: 'user', text }, { role: 'model', text: answer.reply, source: answer.source }].slice(-40) as typeof messages);
      setDraft('');
    } catch (reason) { if (!controller.signal.aborted) setError(errorMessage(reason)); }
    finally { locked.current = false; if (!controller.signal.aborted) { setBusy(false); input.current?.focus(); } }
  }
  return <div className={`chatbot-widget ${expanded ? 'chatbot-expanded' : ''}`} style={{ '--chatbot-font-size': `${fontSize}px` } as CSSProperties}>
    {open && <section id="customer-chatbot" className="chatbot-panel" role="region" aria-label="Trợ lý hỗ trợ" onKeyDown={event => { if (event.key === 'Escape') close(); }}>
      <header className="chatbot-header"><div><strong>Trợ lý hỗ trợ</strong><small>{aiEnabled === true ? 'Hỏi đáp cùng Gemini AI' : aiEnabled === false ? 'Hướng dẫn có sẵn · AI chưa bật' : 'Hướng dẫn sử dụng website'}</small></div>
        <button type="button" aria-label={expanded ? 'Thu nhỏ khung chat' : 'Phóng to khung chat'} title={expanded ? 'Thu nhỏ khung chat' : 'Phóng to khung chat'} aria-pressed={expanded} onClick={() => setExpanded(value => !value)}>{expanded ? <Minimize2 size={20} /> : <Maximize2 size={20} />}</button>
        <button type="button" disabled={busy} aria-label="Xóa cuộc trò chuyện" onClick={() => { setMessages([]); setError(''); }}><RotateCcw size={18} /></button>
        <button type="button" aria-label="Đóng trò chuyện" onClick={close}><X size={21} /></button></header>
      <div className="chatbot-reading-tools" role="group" aria-label="Điều chỉnh cỡ chữ"><span>Cỡ chữ</span><button type="button" aria-label="Giảm cỡ chữ" disabled={fontSize <= 14} onClick={() => setFontSize(size => Math.max(14, size - 2))}><Minus size={14} />A</button><output aria-live="polite" aria-label="Cỡ chữ hiện tại">{fontSize}px</output><button type="button" aria-label="Tăng cỡ chữ" disabled={fontSize >= 22} onClick={() => setFontSize(size => Math.min(22, size + 2))}>A<Plus size={14} /></button></div>
      <div className="chatbot-messages" role="log" aria-label="Nội dung trò chuyện" aria-live="polite" aria-relevant="additions">
        <p className="chatbot-welcome">Bạn cần hỗ trợ thao tác nào?</p>
        {messages.map((item, index) => <article key={index} className={`chatbot-message chatbot-${item.role}`}><small>{item.role === 'user' ? 'Bạn' : item.source === 'GEMINI' ? 'Gemini AI' : 'Hướng dẫn có sẵn'}</small>{item.role === 'model' ? <ChatMessageText text={item.text} /> : <p>{item.text}</p>}</article>)}
        {busy && <p role="status">Đang chuẩn bị câu trả lời…</p>}<div ref={end} />
      </div>
      <div className="chatbot-actions">{!messages.length && <div className="chatbot-suggestions">{suggestions.map(text => <button type="button" key={text} disabled={busy} onClick={() => { setDraft(text); input.current?.focus(); }}>{text}</button>)}</div>}
        <p className="chatbot-notice">Chỉ hỏi về thao tác. Không nhập mật khẩu, OTP, số tài khoản hoặc dữ liệu cá nhân. AI có thể trả lời sai; hãy kiểm tra trên giao diện.</p>
        <label className="chatbot-consent"><input type="checkbox" checked={consent} disabled={busy} onChange={event => setConsent(event.target.checked)} /><span>Tôi đồng ý gửi nội dung chat đến Google Gemini khi AI được bật. Gói miễn phí có thể dùng nội dung để cải thiện sản phẩm.</span></label>
        {error && <p className="chatbot-error" role="alert">{error} Câu hỏi vẫn được giữ để bạn thử lại.</p>}
        <form onSubmit={event => void send(event)}><label className="sr-only" htmlFor="chatbot-question">Câu hỏi của bạn</label><textarea id="chatbot-question" ref={input} rows={2} maxLength={2000} value={draft} disabled={busy} onChange={event => setDraft(event.target.value)} placeholder="Nhập câu hỏi về cách sử dụng…" /><button type="submit" disabled={busy || !consent || !draft.trim()} aria-label="Gửi câu hỏi"><Send size={20} /></button></form>
        <Link to="/support" onClick={close}>Cần nhân viên kiểm tra giao dịch? Mở tra soát →</Link>
      </div>
    </section>}
    <button ref={launcher} type="button" className="chatbot-launcher" aria-expanded={open} aria-controls="customer-chatbot" onClick={() => open ? close() : setOpen(true)}><MessageCircle size={22} />{open ? 'Thu gọn' : 'Hỏi trợ lý'}</button>
  </div>;
}
