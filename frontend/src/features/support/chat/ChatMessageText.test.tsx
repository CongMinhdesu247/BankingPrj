import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { expect, it } from 'vitest';
import { ChatMessageText } from './ChatMessageText';
it('renders bold and route names without exposing Markdown markers', () => {
  const { container } = render(<MemoryRouter>
    <ChatMessageText text={'Vào **Hạn mức cá nhân** (`/limits`).\n\n- Xem số tiền còn lại.'} />
  </MemoryRouter>);
  expect(container.querySelector('strong')).toHaveTextContent('Hạn mức cá nhân');
  expect(screen.getByRole('link', { name: 'Hạn mức cá nhân' })).toHaveAttribute('href', '/limits');
  expect(container).not.toHaveTextContent('**');
  expect(container).not.toHaveTextContent('`');
  expect(container).toHaveTextContent('• Xem số tiền còn lại.');
});
it('keeps arbitrary HTML and unsafe links inert without damaging amounts', () => {
  const { container } = render(<MemoryRouter>
    <ChatMessageText text={'<img src=x onerror=alert(1)> [bấm đây](https://example.com) [x](javascript:alert) 100 + 50 = 150 triệu'} />
  </MemoryRouter>);
  expect(container.querySelector('img')).toBeNull();
  expect(screen.queryByRole('link')).not.toBeInTheDocument();
  expect(container).toHaveTextContent('100 + 50 = 150 triệu');
});
