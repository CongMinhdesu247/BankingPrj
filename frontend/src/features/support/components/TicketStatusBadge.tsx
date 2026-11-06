export const ticketStatusLabels: Record<string, string> = { OPEN: 'Mới gửi', IN_PROGRESS: 'Đang xử lý', RESOLVED: 'Đã phản hồi kết quả', CLOSED: 'Đã đóng' };
export function TicketStatusBadge({ status }: { status: string }) {
  const known = Object.hasOwn(ticketStatusLabels, status);
  return <span className={`ticket-status ticket-status-${known ? status.toLowerCase() : 'unknown'}`}>
    <span aria-hidden="true" className="ticket-status-dot" />
    {ticketStatusLabels[status] ?? status}
  </span>;
}
