import { Sprout } from 'lucide-react';
export function Brand({ light = false }: { light?: boolean }) {
  return <div className={`brand ${light ? 'brand-light' : ''}`}>
    <span className="brand-mark"><Sprout size={27} strokeWidth={1.7} /></span>
    <span>
      <strong>AGRIBANK<span className="brand-dot">.</span></strong>
      <small>KHÔNG GIAN NGÂN HÀNG SỐ</small>
    </span>
  </div>;
}
