import { useState, type FormEvent } from 'react';
import { Button, ErrorBanner, FieldError, Loading } from '../../../shared/components/ui';
import { useLoad } from '../../../shared/hooks/useLoad';
import { validAmount } from '../../../shared/lib/format';
import { fraudApi, ruleLabels, type FraudRule } from '../api/fraudApi';
export function FraudRulesPage() {
  const load = useLoad(signal => fraudApi.rules(signal));
  const [overrides, setOverrides] = useState<Record<string, FraudRule>>({});
  return <>
    <div className="page-heading">
      <div>
        <span className="eyebrow">QUẢN TRỊ</span>
        <h1>Cấu hình quy tắc rủi ro</h1>
        <p>Thay đổi áp dụng cho những giao dịch chưa được phân tích; bằng chứng cũ được giữ nguyên.</p>
      </div>
      <Button className="secondary" onClick={() => {
        setOverrides({});
        load.reload();
      }}>Tải cấu hình mới nhất</Button>
    </div>
    <div className="notice info">Các ngưỡng phục vụ mô phỏng. Điểm được cộng và giới hạn ở 100; không phải xác suất gian lận.</div>
    <ErrorBanner error={load.error} retry={load.reload} />
    {load.loading
      ? <Loading />
      : <div className="evidence-grid">
        {load.data?.map(original => {
          const rule = overrides[original.id] ?? original;
          return <RuleEditor key={`${rule.id}:${rule.version}`} rule={rule} onSaved={saved => setOverrides(values => ({ ...values, [saved.id]: saved }))} />;
        })}
      </div>}
  </>;
}
function RuleEditor({ rule, onSaved }: { rule: FraudRule; onSaved: (rule: FraudRule) => void }) {
  const [amount, setAmount] = useState(rule.threshold.amount ?? ''),
    [count, setCount] = useState(String(rule.threshold.count ?? ''));
  const [window, setWindow] = useState(String(rule.threshold.windowSeconds ?? '')),
    [weight, setWeight] = useState(String(rule.weight)),
    [enabled, setEnabled] = useState(rule.enabled);
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null),
    [invalid, setInvalid] = useState(false);
  async function save(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    const integer = (value: string, min: number, max: number) => /^\d+$/.test(value) && Number(value) >= min && Number(value) <= max;
    if ((rule.threshold.amount !== null && !validAmount(amount)) || (rule.threshold.count !== null && !integer(count, 1, 100000)) || (rule.threshold.windowSeconds !== null && !integer(window, 1, 604800)) || !integer(weight, 0, 100)) {
      setInvalid(true);
      return;
    }
    setInvalid(false);
    setError(null);
    setBusy(true);
    try {
      onSaved(await fraudApi.updateRule(rule, {
        amount: rule.threshold.amount === null ? null : amount,
        count: rule.threshold.count === null ? null : Number(count),
        windowSeconds: rule.threshold.windowSeconds === null ? null : Number(window)
      }, Number(weight), enabled));
    }
    catch (failure) { setError(failure); }
    finally { setBusy(false); }
  }
  return <section className="panel">
    <div className="section-heading">
      <h2>{ruleLabels[rule.code]}</h2>
      <span className="count-pill">v{rule.version}</span>
    </div>
    <form className="risk-form" onSubmit={save} noValidate>
      <label className="risk-checkbox"><input
        type="checkbox"
        checked={enabled}
        onChange={event => setEnabled(event.target.checked)}
        disabled={busy} /> Bật quy tắc</label>
      {rule.threshold.amount !== null &&
        <label>Ngưỡng số tiền VND<input
          inputMode="numeric"
          value={amount}
          onChange={event => setAmount(event.target.value)}
          maxLength={17}
          disabled={busy} /></label>}
      {rule.threshold.count !== null &&
        <label>Ngưỡng số lần<input inputMode="numeric" value={count} onChange={event => setCount(event.target.value)} disabled={busy} /></label>}
      {rule.threshold.windowSeconds !== null &&
        <label>
          {rule.code === 'NEW_BENEFICIARY' ? 'Tuổi người thụ hưởng tối đa (giây)' : 'Cửa sổ thời gian (giây)'}
          <input inputMode="numeric" value={window} onChange={event => setWindow(event.target.value)} disabled={busy} />
        </label>}
      <label>Điểm đóng góp (0–100)<input inputMode="numeric" value={weight} onChange={event => setWeight(event.target.value)} disabled={busy} /></label>
      <FieldError>
        {invalid && 'Kiểm tra số tiền nguyên dương, số lần, cửa sổ 1–604800 giây và điểm 0–100.'}
      </FieldError>
      <ErrorBanner error={error} />
      <Button type="submit" className="primary" loading={busy}>Lưu phiên bản mới</Button>
    </form>
  </section>;
}
