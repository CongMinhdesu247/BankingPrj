import { useState } from 'react';
import { Button, ErrorBanner, Loading, Pagination, EmptyState } from '../../../shared/components/ui';
import { useLoad } from '../../../shared/hooks/useLoad';
import { dateTime } from '../../../shared/lib/format';
import { customerToolsApi } from '../../accounts/api/customerToolsApi';
export function SecurityPage() {
  const [page, setPage] = useState(0);
  const load = useLoad(signal => customerToolsApi.logins(page, signal), [page]);
  return <>
    <div className="page-heading">
      <div>
        <span className="eyebrow">BẢO VỆ TÀI KHOẢN</span>
        <h1>Lịch sử đăng nhập</h1>
        <p>Kiểm tra những lần đăng nhập thành công và không thành công của tài khoản bạn.</p>
      </div>
      <Button className="secondary" onClick={load.reload}>Làm mới</Button>
    </div>
    <div className="notice info">Nếu thấy hoạt động bất thường, hãy đăng xuất trên thiết bị dùng chung và liên hệ ngân hàng để được hỗ trợ. Không cung cấp mật khẩu cho người khác.</div>
    <section className="panel">
      <ErrorBanner error={load.error} retry={load.reload} />
      {load.loading
        ? <Loading />
        : load.data?.content.length
          ? <>
            <div className="risk-table-wrap">
              <table className="risk-table">
                <thead>
                  <tr>
                    <th>Thời gian Việt Nam</th>
                    <th>Kết quả đăng nhập</th>
                  </tr>
                </thead>
                <tbody>
                  {load.data.content.map((event, i) =>
                    <tr key={`${event.createdAt}-${i}`}>
                      <td>{dateTime(event.createdAt)}</td>
                      <td>
                        <span className={`status-badge ${event.success ? 'active' : 'frozen'}`}>
                          {event.success ? 'Thành công' : 'Không thành công'}
                        </span>
                      </td>
                    </tr>)}
                </tbody>
              </table>
            </div>
            <Pagination {...load.data} onChange={setPage} />
          </>
          : !load.error &&
          <EmptyState title="Chưa có lịch sử" description="Lịch sử đăng nhập của bạn sẽ xuất hiện tại đây." />}
    </section>
  </>;
}
