import { Component, type ReactNode } from 'react';
import { Brand } from './Brand';
import { Button } from './ui';
export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    if (this.state.failed) return <main className="panel" style={{ maxWidth: 600, margin: '10vh auto', padding: 35 }}><Brand /><h1 style={{ marginTop: 30 }}>Chưa thể hiển thị trang</h1><p className="subtle-note" style={{ margin: '16px 0 25px' }}>Vui lòng tải lại và đăng nhập để tiếp tục. Nếu có yêu cầu chuyển tiền đang chờ, hãy kiểm tra yêu cầu và lịch sử trước khi tạo lần chuyển mới.</p><Button className="primary" onClick={() => window.location.reload()}>Tải lại trang</Button></main>;
    return this.props.children;
  }
}
