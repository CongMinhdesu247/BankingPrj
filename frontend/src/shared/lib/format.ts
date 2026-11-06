export function wholeVnd(value: string): bigint {
  if (!/^\d+(?:\.0{1,2})?$/.test(value)) throw new Error('Expected whole VND');
  return BigInt(value.split('.')[0]);
}
export function money(value: string | bigint, currency = true): string {
  const integer = typeof value === 'bigint' ? value : wholeVnd(value);
  return new Intl.NumberFormat('vi-VN').format(integer) + (currency ? ' ₫' : '');
}
export function validAmount(value: string): boolean {
  return /^[0-9]{1,17}$/.test(value) && BigInt(value) > 0n;
}
export function dateTime(value: string, dateOnly = false): string {
  return new Intl.DateTimeFormat('vi-VN', {
    timeZone: 'Asia/Ho_Chi_Minh',
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    ...(dateOnly ? {} : { hour: '2-digit', minute: '2-digit' }),
  }).format(new Date(value));
}
export function initials(value: string): string {
  return value.trim().split(/\s+/).slice(-2).map(word => word[0]).join('').toUpperCase();
}
export function accountNumber(value: string): string { return value.replace(/(.{4})/g, '$1 ').trim(); }
export function vietnamDayStart(date: string): string { return new Date(`${date}T00:00:00+07:00`).toISOString(); }
export function vietnamNextDayStart(date: string): string {
  return new Date(new Date(`${date}T00:00:00+07:00`).getTime() + 86400000).toISOString();
}
