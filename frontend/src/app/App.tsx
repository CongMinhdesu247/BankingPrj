import { BrowserRouter } from 'react-router';
import { AuthProvider } from '../features/auth/providers/AuthProvider';
import { AppRoutes } from './routes/AppRoutes';
import { ErrorBoundary } from '../shared/components/ErrorBoundary';
export default function App() {
  return <ErrorBoundary>
    <BrowserRouter><AuthProvider><AppRoutes /></AuthProvider></BrowserRouter>
  </ErrorBoundary>;
}
