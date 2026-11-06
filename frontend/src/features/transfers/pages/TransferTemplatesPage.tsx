import { useState } from 'react';
import { Link } from 'react-router';
import { Button, EmptyState, ErrorBanner, Loading, Modal, Pagination } from '../../../shared/components/ui';
import { useLoad } from '../../../shared/hooks/useLoad';
import { money } from '../../../shared/lib/format';
import { selfServiceApi as api, type Template } from '../../../shared/api/selfServiceApi';
export function TransferTemplatesPage() {
  const [page, setPage] = useState(0),
    [removing, setRemoving] = useState<Template | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>();
  const load = useLoad(signal => api.templates(page, signal), [page]);
  async function remove() {
    if (!removing) return;
    setBusy(true);
    setError(undefined);
    try {
      await api.deleteTemplate(removing.id);
      setRemoving(null);
      setPage(0);
      load.reload();
    }
    catch (reason) { setError(reason); }
    finally { setBusy(false); }
  }
  return <>
    <div className="page-heading">
      <div>
        <span className="eyebrow">CHUYỂN TIỀN THUẬN TIỆN HƠN</span>
        <h1>Mẫu chuyển tiền</h1>
        <p>Lưu từ giao dịch đã thực hiện. Mỗi lần dùng mẫu, bạn vẫn kiểm tra và xác nhận một giao dịch mới.</p>
      </div>
      <Link className="button primary" to="/history">Chọn giao dịch để lưu mẫu</Link>
    </div>
    <section className="panel">
      <ErrorBanner error={load.error} retry={load.reload} />
      <ErrorBanner error={error} />
      {load.loading
        ? <Loading />
        : load.data?.content.length
          ? <>
            <div className="template-grid">
              {load.data.content.map(template =>
                <article className="template-card" key={template.id}>
                  <h2>{template.name}</h2>
                  <strong>{money(template.amount)}</strong>
                  <p>Đến {template.destinationAccountNumber}</p>
                  <p className="statement-description">
                    {template.description || 'Không có nội dung'}
                  </p>
                  <div className="button-row">
                    <Link className="button primary small" to={`/transfer?template=${template.id}`}>Sử dụng mẫu</Link>
                    <Button className="secondary small" onClick={() => setRemoving(template)}>Xóa mẫu</Button>
                  </div>
                </article>)}
            </div>
            <Pagination {...load.data} onChange={setPage} />
          </>
          : !load.error &&
          <EmptyState title="Chưa có mẫu chuyển tiền" description="Mở chi tiết giao dịch chuyển đi và chọn Lưu mẫu để sử dụng lại." />}
    </section>
    {removing &&
      <Modal title="Xóa mẫu chuyển tiền?" busy={busy} onClose={() => setRemoving(null)}>
        <p>Xóa mẫu “{removing.name}” sẽ không thay đổi giao dịch đã thực hiện.</p>
        <div className="modal-actions">
          <Button className="secondary" onClick={() => setRemoving(null)} disabled={busy}>Giữ lại</Button>
          <Button className="danger" loading={busy} onClick={() => void remove()}>Xóa mẫu</Button>
        </div>
      </Modal>}
  </>;
}
