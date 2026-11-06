import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { useAccounts } from '../../accounts/providers/AccountsProvider';
import { selfServiceApi } from '../../../shared/api/selfServiceApi';
import { transactionApi } from '../api/transactionApi';
import { accountApi } from '../../accounts/api/accountApi';
import type { Transaction } from '../../../shared/types/api';
import { Button, ErrorBanner, Modal } from '../../../shared/components/ui';
export function ReceiptActions({ transaction }: { transaction: Transaction }) {
  const { accounts } = useAccounts();
  const [saving, setSaving] = useState(false),
    [name, setName] = useState(''),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(),
    [notice, setNotice] = useState('');
  const sourceOwned = accounts.some(a => a.id === transaction.sourceAccountId);
  async function pdf() {
    setBusy(true);
    setError(undefined);
    try {
      const detail = await transactionApi.detail(transaction.id);
      const [source, destination] = await Promise.all([accountApi.recipientById(detail.transaction.sourceAccountId), accountApi.recipientById(detail.transaction.destinationAccountId)]);
      const { receiptPdf } = await import('../lib/receiptPdf');
      const bytes = await receiptPdf(detail, source.accountNumber, destination.accountNumber);
      const url = URL.createObjectURL(new Blob([new Uint8Array(bytes).buffer], { type: 'application/pdf' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = `bien-lai-${detail.transaction.reference}.pdf`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
    catch (failure) { setError(failure); }
    finally { setBusy(false); }
  }
  async function save(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      await selfServiceApi.saveTemplate({
        name: name.trim(),
        sourceAccountId: transaction.sourceAccountId,
        destinationAccountId: transaction.destinationAccountId,
        amount: transaction.amount.split('.')[0],
        description: transaction.description
      });
      setSaving(false);
      setName('');
      setNotice('Đã lưu mẫu. Bạn có thể dùng tại mục Mẫu chuyển tiền.');
    }
    catch (failure) { setError(failure); }
    finally { setBusy(false); }
  }
  return <>
    <div className="button-row service-actions">
      <Button className="secondary" loading={busy && !saving} onClick={() => void pdf()}>Tải biên lai PDF</Button>
      {transaction.type === 'INTERNAL_TRANSFER' &&
        <>
          {sourceOwned &&
            <Button className="secondary" disabled={busy} onClick={() => {
              setSaving(true);
              setError(undefined);
            }}>Lưu mẫu chuyển tiền</Button>}
          <Link className="button secondary" to={`/support?transaction=${transaction.id}`}>Yêu cầu tra soát</Link>
        </>}
    </div>
    <ErrorBanner error={error} />
    {notice && <p className="notice info" role="status">{notice}</p>}
    {saving &&
      <Modal title="Lưu mẫu chuyển tiền" busy={busy} onClose={() => setSaving(false)}>
        <form className="service-form" onSubmit={save}>
          <label>Tên gợi nhớ<input required autoFocus maxLength={80} value={name} onChange={e => setName(e.target.value)} /></label>
          <p className="subtle-note">Mẫu lưu người nhận, số tiền và nội dung. Sử dụng mẫu không tự thực hiện chuyển tiền.</p>
          <ErrorBanner error={error} />
          <Button type="submit" className="primary" loading={busy}>Lưu mẫu</Button>
        </form>
      </Modal>}
  </>;
}
