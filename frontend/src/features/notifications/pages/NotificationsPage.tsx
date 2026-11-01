import { useRef, useState } from 'react';
import { Link } from 'react-router';
import { useAuth } from '../../auth/providers/AuthProvider';
import { Button, EmptyState, ErrorBanner, Loading, Pagination } from '../../../shared/components/ui';
import { useLoad } from '../../../shared/hooks/useLoad';
import { dateTime } from '../../../shared/lib/format';
import { notificationApi, type Notification } from '../api/notificationApi';
import { ApiFailure, errorMessage } from '../../../shared/api/httpClient';
import { riskLabels, type RiskLevel } from '../../fraud/api/fraudApi';
import { useNotifications } from '../providers/NotificationsProvider';
import { isSupportNotification, supportNotificationDescription, supportNotificationTarget, supportNotificationTitle } from '../lib/notificationPresentation';
import { TicketStatusBadge } from '../../support/components/TicketStatusBadge';
export function NotificationsPage() {
  const notifications = useNotifications();
  const { me } = useAuth(); const [page, setPage] = useState(0), [unread, setUnread] = useState(false);
  const load = useLoad(signal => notificationApi.list(page, unread, signal), [page, unread, notifications?.latestId, notifications?.unread]);
  const [error, setError] = useState<unknown>(null), [busy, setBusy] = useState<string | null>(null); const lock = useRef(false);
  async function markRead(id: string) {
    if (lock.current) return; lock.current = true; setBusy(id); setError(null);
    try { await notificationApi.read(id); load.reload(); notifications?.refresh(); } catch (failure) { setError(failure); }
    finally { lock.current = false; setBusy(null); }
  }
  function target(item: Notification) {
    if (isSupportNotification(item) && me?.user.roles.includes('CUSTOMER')) return supportNotificationTarget(item);
    if (item.type === 'FRAUD_DETECTED' && item.content.alertId && me?.user.roles.includes('RISK_OFFICER')) return `/fraud/alerts/${encodeURIComponent(item.content.alertId)}`;
    if (item.type === 'TRANSFER_COMPLETED' && item.content.transactionId) return `${me?.user.roles.includes('CUSTOMER') ? '/transactions' : '/staff/transactions'}/${encodeURIComponent(item.content.transactionId)}`;
    return null;
  }
  return <><div className="page-heading"><div><span className="eyebrow">HỘP THƯ CÁ NHÂN</span><h1>Thông báo</h1><p>Thông báo giao dịch và phản hồi CSKH được tự động cập nhật khi bạn đang mở web.</p></div><Button className="secondary" onClick={load.reload}>Làm mới</Button></div><section className="panel"><label className="notification-filter"><input type="checkbox" checked={unread} onChange={event => { setPage(0); setUnread(event.target.checked); }} /> Chỉ thông báo chưa đọc</label><ErrorBanner error={error ?? load.error} retry={load.error ? load.reload : undefined} />{load.loading ? <Loading /> : load.data?.content.length ? <><div className="notification-list">{load.data.content.map(item => <article className={`notification-item ${item.status === 'UNREAD' ? 'unread' : ''}`} key={item.id}><div><div className="section-heading"><h2>{isSupportNotification(item) ? supportNotificationTitle(item) : item.type === 'TRANSFER_COMPLETED' ? 'Giao dịch đã hoàn tất' : item.type === 'FRAUD_DETECTED' ? 'Có cảnh báo cần điều tra' : 'Cập nhật yêu cầu chuyển tiền'}</h2><span className="count-pill">{item.status === 'UNREAD' ? 'Chưa đọc' : 'Đã đọc'}</span></div><p>{isSupportNotification(item) ? supportNotificationDescription(item) : item.type === 'TRANSFER_COMPLETED' ? 'Bạn có thể mở chứng từ để đối chiếu giao dịch.' : item.type === 'FRAUD_DETECTED' ? `Mức rủi ro ${riskLabels[item.content.riskLevel as RiskLevel] ?? 'cần xem xét'} · ${item.content.score ?? 0}/100 điểm quy tắc.` : item.content.outcome === 'SYSTEM_FAILURE' ? 'Yêu cầu chưa xác định kết quả. Hãy đối chiếu lịch sử và giữ nguyên mã yêu cầu khi thử lại.' : errorMessage(new ApiFailure(item.content.reasonCode ?? 'INTERNAL_ERROR', 409))}</p>{isSupportNotification(item) && item.content.ticketStatus && <TicketStatusBadge status={item.content.ticketStatus} />}{isSupportNotification(item) && item.content.messagePreview && <blockquote className="notification-message-preview">{item.content.messagePreview}</blockquote>}<small>{dateTime(item.createdAt)}</small><div className="button-row">{target(item) && <Link className="text-link" to={target(item)!}>Xem chi tiết →</Link>}{item.status === 'UNREAD' && <Button className="secondary small" onClick={() => void markRead(item.id)} loading={busy === item.id} disabled={busy !== null}>Đánh dấu đã đọc</Button>}</div></div></article>)}</div><Pagination {...load.data} onChange={setPage} /></> : !load.error && <EmptyState title="Chưa có thông báo" description={unread ? 'Bạn đã đọc hết thông báo hoặc chưa có cập nhật mới.' : 'Thông báo mới sẽ xuất hiện khi có giao dịch, cập nhật tra soát hoặc phản hồi CSKH.'} />}</section></>;
}
