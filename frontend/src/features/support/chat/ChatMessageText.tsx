import { Fragment, type ReactNode } from 'react';
import { Link } from 'react-router';
const destinations: Record<string, string> = {
  '/': 'Tổng quan',
  '/accounts': 'Tài khoản',
  '/transfer': 'Chuyển tiền',
  '/history': 'Lịch sử giao dịch',
  '/support': 'Tra soát & hỗ trợ',
  '/limits': 'Hạn mức cá nhân',
  '/beneficiaries': 'Người thụ hưởng',
  '/templates': 'Mẫu chuyển tiền',
  '/statement': 'Sao kê & thu chi',
  '/notifications': 'Thông báo',
  '/security': 'Trung tâm bảo mật',
  '/profile': 'Thông tin cá nhân',
};
// A small, text-only Markdown subset. Model output never becomes HTML or an arbitrary URL.
function inline(text: string): ReactNode[] {
  return text.split(/(\*\*[^*\n]+\*\*|__[^_\n]+__|`[^`\n]+`|\[[^\]\n]+\]\([^)\n]+\))/g).map((part, index) => {
    if ((part.startsWith('**') && part.endsWith('**')) || (part.startsWith('__') && part.endsWith('__'))) return <strong key={index}>{part.slice(2, -2)}</strong>;
    if (part.startsWith('`') && part.endsWith('`')) {
      const value = part.slice(1, -1);
      return destinations[value]
        ? <Link key={index} to={value}>{destinations[value]}</Link>
        : <span key={index}>{value}</span>;
    }
    const link = part.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
    if (link) return destinations[link[2]]
      ? <Link key={index} to={link[2]}>{link[1]}</Link>
      : <span key={index}>{link[1]}</span>;
    return <Fragment key={index}>{part}</Fragment>;
  });
}
export function ChatMessageText({ text }: { text: string }) {
  return <div className="chatbot-message-text">
    {text.split(/\n\s*\n/).map((paragraph, index) =>
      <p key={index}>
        {paragraph.split('\n').map((line, lineIndex) =>
          <Fragment key={lineIndex}>
            {lineIndex > 0 && <br />}
            {inline(line.replace(/^\s{0,3}#{1,6}\s+/, '').replace(/^\s*[-*]\s+/, '• '))}
          </Fragment>)}
      </p>)}
  </div>;
}
