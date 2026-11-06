import { ArrowLeft, ArrowRight, Check, CheckCircle2, Send } from 'lucide-react';
import { Link, useLocation, useParams } from 'react-router';
import { useAccounts } from '../../accounts/providers/AccountsProvider';
import { Button, ErrorBanner, Loading } from '../../../shared/components/ui';
import { useLoad } from '../../../shared/hooks/useLoad';
import { accountNumber, dateTime, money } from '../../../shared/lib/format';
import { transactionApi } from '../api/transactionApi';
import { accountApi } from '../../accounts/api/accountApi';
import { ReceiptActions } from '../components/ReceiptActions';
export function TransferResultPage() {
  const { id = '' } = useParams();
  const { accounts } = useAccounts();
  const location = useLocation();
  const detail = useLoad(signal => transactionApi.detail(id, signal), [id]);
  const parties = useLoad(signal => {
    if (!detail.data) return Promise.resolve([]);
    const ids = [detail.data.transaction.sourceAccountId, detail.data.transaction.destinationAccountId];
    return Promise.all(ids.filter(accountId => !accounts.some(account => account.id === accountId)).map(accountId => accountApi.recipientById(accountId, signal)));
  }, [detail.data?.transaction.id, accounts]);
  const justTransferred = (location.state as { justTransferred?: boolean } | null)?.justTransferred;
  const number = (accountId: string) => {
    const account = accounts.find(item => item.id === accountId) ?? parties.data?.find(item => item.id === accountId);
    return account ? accountNumber(account.accountNumber) : 'Tài khoản đối tác';
  };
  return <>
    <Link className="back-link" to="/history"><ArrowLeft size={16} />Lịch sử giao dịch</Link>
    <div className="page-heading">
      <div>
        <span className="eyebrow">THÔNG TIN GIAO DỊCH</span>
        <h1>{justTransferred ? 'Kết quả chuyển tiền' : 'Chi tiết giao dịch'}</h1>
      </div>
    </div>
    <ErrorBanner error={detail.error} retry={detail.reload} />
    {detail.loading
      ? <Loading />
      : detail.data &&
      <section className="panel transfer-receipt">
        <div className="receipt-top">
          <span className="receipt-check"><Check size={35} strokeWidth={2.5} /></span>
          <span className="eyebrow">GIAO DỊCH ĐÃ HOÀN TẤT</span>
          <h2>{justTransferred ? 'Chuyển tiền thành công' : 'Giao dịch thành công'}</h2>
          <strong className="receipt-amount">
            {money(detail.data.transaction.amount)}
          </strong>
          <span className="receipt-date">
            {dateTime(detail.data.transaction.createdAt)}
          </span>
        </div>
        <dl className="review-details">
          <div>
            <dt>Từ tài khoản</dt>
            <dd>{number(detail.data.transaction.sourceAccountId)}</dd>
          </div>
          <div>
            <dt>Đến tài khoản</dt>
            <dd>{number(detail.data.transaction.destinationAccountId)}</dd>
          </div>
          <div>
            <dt>Nội dung</dt>
            <dd>{detail.data.transaction.description ?? 'Không có nội dung'}</dd>
          </div>
          <div>
            <dt>Mã giao dịch</dt>
            <dd className="reference-value">{detail.data.transaction.reference}</dd>
          </div>
          <div>
            <dt>Trạng thái</dt>
            <dd className="success-text"><CheckCircle2 size={16} />Hoàn tất</dd>
          </div>
        </dl>
        <div className="receipt-balances">
          {detail.data.entries.filter(entry => entry.balanceAfter !== null).map(entry =>
            <div key={entry.id}>
              <span>Số dư sau giao dịch · {number(entry.accountId)}</span>
              <strong>{money(entry.balanceAfter!)}</strong>
            </div>)}
        </div>
        <p className="subtle-note">Số dư trên chứng từ là tại thời điểm giao dịch. Xem mục Tài khoản để biết số dư hiện tại.</p>
        <div className="button-row">
          <Link className="button primary" to="/transfer"><Send size={17} />Chuyển tiền mới</Link>
          <Link className="button secondary" to="/accounts">Xem tài khoản <ArrowRight size={17} /></Link>
        </div>
        <ReceiptActions transaction={detail.data.transaction} />
        <Button className="ghost small" onClick={() => window.print()}>In chứng từ</Button>
      </section>}
  </>;
}
