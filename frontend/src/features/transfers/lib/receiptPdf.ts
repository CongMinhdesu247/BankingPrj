import { PDFDocument, rgb } from 'pdf-lib';
import fontkit from '@pdf-lib/fontkit';
import fontUrl from '../../../assets/fonts/BeVietnamPro-Regular.ttf?url';
import type { TransactionDetail } from '../../../shared/types/api';
import { dateTime, money } from '../../../shared/lib/format';

export async function receiptPdf(detail: TransactionDetail, sourceNumber: string, destinationNumber: string): Promise<Uint8Array> {
  const response = await fetch(fontUrl);
  if (!response.ok) throw new Error('Receipt font unavailable');
  const doc = await PDFDocument.create();
  doc.registerFontkit(fontkit);
  const font = await doc.embedFont(await response.arrayBuffer(), { subset: true });
  const supported = new Set(font.getCharacterSet());
  const clean = (text: string) => Array.from(text.normalize('NFC')).map(c => /[\r\n\t]/.test(c) ? ' ' : supported.has(c.codePointAt(0)!) ? c : '?').join('');
  const ink = rgb(.14, .19, .22), wine = rgb(.56, .14, .28), muted = rgb(.39, .45, .49);
  let page = doc.addPage([595.28, 841.89]), y = 785;
  function lines(text: string, size: number, width = 475): string[] {
    const result: string[] = [];
    let line = '';
    for (const char of clean(text)) {
      if (font.widthOfTextAtSize(line + char, size) > width && line) {
        result.push(line.trimEnd());
        line = '';
      }
      line += char;
    }
    if (line) result.push(line);
    return result.length ? result : [''];
  }
  function text(value: string, size = 11, color = ink) {
    for (const line of lines(value, size)) {
      if (y < 95) {
        page = doc.addPage([595.28, 841.89]);
        y = 785;
      }
      page.drawText(line, { x: 60, y, size, font, color });
      y -= size + 8;
    }
  }
  text('AGRIBANK PORTFOLIO', 16, wine);
  y -= 10;
  text('BIÊN LAI GIAO DỊCH', 23, ink);
  text('Giao dịch hoàn tất', 11, muted);
  y -= 12;
  text(money(detail.transaction.amount), 28, wine);
  y -= 14;
  for (const [label, value] of [
    ['Mã tham chiếu', detail.transaction.reference],
    ['Thời gian Việt Nam (GMT+7)', dateTime(detail.transaction.createdAt)],
    ['Tài khoản chuyển', sourceNumber], ['Tài khoản nhận', destinationNumber],
    ['Nội dung', detail.transaction.description || 'Không có nội dung'],
    ['Loại giao dịch', detail.transaction.type === 'OPENING' ? 'Vốn mở sổ mô phỏng' : 'Chuyển tiền nội bộ VND'],
  ]) {
    text(label, 9, muted);
    text(value, 11);
    y -= 12;
  }
  y -= 8;
  text('Biên lai trong môi trường mô phỏng. Không sử dụng tiền thật.', 9, muted);
  text('Tài liệu không có chữ ký số và không thay thế chứng từ ngân hàng.', 9, muted);
  const pages = doc.getPages();
  pages.forEach((item, index) => {
    item.drawLine({ start: { x: 60, y: 65 }, end: { x: 535, y: 65 }, thickness: .6, color: rgb(.86, .88, .90) });
    item.drawText(`AGRIBANK PORTFOLIO - ${index + 1}/${pages.length}`, { x: 60, y: 45, size: 8, font, color: muted });
  });
  doc.setTitle(`Biên lai ${detail.transaction.reference}`);
  doc.setAuthor('Banking Portfolio');
  return doc.save();
}
