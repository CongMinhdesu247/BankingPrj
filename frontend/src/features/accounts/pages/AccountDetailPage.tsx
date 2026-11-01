import { ArrowLeft, Copy, Send, ShieldCheck } from 'lucide-react';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { AccountCard } from '../components/AccountCard';
import { TransactionList } from '../../transfers/components/TransactionList';
import { Button, EmptyState, ErrorBanner, Loading, Pagination } from '../../../shared/components/ui';
import { useLoad } from '../../../shared/hooks/useLoad';
import { accountNumber, dateTime, money } from '../../../shared/lib/format';
import { accountApi } from '../api/accountApi';
import { transactionApi } from '../../transfers/api/transactionApi';
export function AccountDetailPage() {
  const { id = '' } = useParams(); const [page, setPage] = useState(0); const [copied, setCopied] = useState(false);
  const account = useLoad(signal => accountApi.get(id, signal), [id]);
  const history = useLoad(signal => transactionApi.history({ accountId: id, page }, signal), [id, page]);
  const limit = useLoad(signal => accountApi.limits(id, signal), [id]);
  async function copy() { try { await navigator.clipboard.writeText(account.data!.accountNumber); setCopied(true); } catch { setCopied(false); } }
  return <><Link className="back-link" to="/accounts"><ArrowLeft size={16} />Tất cả tài khoản</Link><div className="page-heading"><div><span className="eyebrow">TÀI KHOẢN THANH TOÁN</span><h1>Chi tiết tài khoản</h1><p>Theo dõi số dư, hạn mức và các giao dịch liên quan.</p></div>{account.data?.status === 'ACTIVE' && <Link className="button primary" to={`/transfer?source=${id}`}><Send size={17} />Chuyển tiền</Link>}</div><ErrorBanner error={account.error} retry={account.reload} />{account.loading ? <Loading /> : account.data && <div className="account-detail-grid"><AccountCard account={account.data} featured /><div className="panel account-facts"><dl><div><dt>Số tài khoản</dt><dd>{accountNumber(account.data.accountNumber)}<Button className="icon-button ghost" aria-label="Sao chép số tài khoản" onClick={copy}><Copy size={16} /></Button></dd></div><div><dt>Loại tiền tệ</dt><dd>Việt Nam đồng · VND</dd></div><div><dt>Ngày mở</dt><dd>{dateTime(account.data.createdAt, true)}</dd></div></dl>{copied && <small className="success-text" role="status">Đã sao chép số tài khoản.</small>}</div></div>}{account.data && <><div className="panel limit-strip"><ShieldCheck size={21} /><div><span>Hạn mức mỗi lần</span><strong>{limit.data ? money(limit.data.perTransferAmount) : '—'}</strong></div><div><span>Đã chuyển hôm nay</span><strong>{limit.data ? money(limit.data.dailyUsed) : '—'}</strong></div><div><span>Còn lại hôm nay</span><strong>{limit.data ? money(limit.data.dailyRemaining) : '—'}</strong></div><small>Ngày Việt Nam<br />Asia/Ho_Chi_Minh</small></div><ErrorBanner error={limit.error} retry={limit.reload} /><section className="panel recent-panel"><div className="section-heading"><h2>Lịch sử tài khoản</h2></div><ErrorBanner error={history.error} retry={history.reload} />{history.loading ? <Loading /> : history.data?.content.length ? <><TransactionList transactions={history.data.content} ownedIds={new Set([id])} /><Pagination {...history.data} onChange={setPage} /></> : !history.error && <EmptyState title="Chưa có giao dịch" description="Lịch sử của tài khoản này sẽ được hiển thị tại đây." />}</section></>}</>;
}
