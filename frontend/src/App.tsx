// Hash routing: the backend serves the build as static files without an SPA fallback, so deep links use `#/…`.
import { HashRouter, Navigate, Route, Routes } from 'react-router-dom';
import { ToastProvider } from './components/Toast';
import { StoreProvider } from './state/store';
import { Layout } from './components/Layout';
import { AlertsPage } from './pages/feed/AlertsPage';
import { AlertDrawer } from './pages/alert/AlertDrawer';
import { UnroutedPage } from './pages/unrouted/UnroutedPage';
import { RulesPage } from './pages/rules/RulesPage';
import { RuleEditor } from './pages/rules/RuleEditor';
import { RunbooksPage } from './pages/runbooks/RunbooksPage';
import { RecipientsPage } from './pages/recipients/RecipientsPage';
import { SourcesPage } from './pages/sources/SourcesPage';
import { SimulatorPage } from './pages/simulator/SimulatorPage';
import { FailedPage } from './pages/failed/FailedPage';
import { AuthGate } from './auth/AuthGate';

export function App() {
  return (
    <HashRouter>
      <ToastProvider>
        <AuthGate>
        <StoreProvider>
          <Layout>
            <Routes>
              <Route path="/" element={<Navigate to="/alerts" replace />} />
              <Route path="/alerts" element={<AlertsPage />} />
              <Route path="/unrouted" element={<UnroutedPage />} />
              <Route path="/rules" element={<RulesPage />} />
              <Route path="/runbooks" element={<RunbooksPage />} />
              <Route path="/recipients" element={<RecipientsPage />} />
              <Route path="/sources" element={<SourcesPage />} />
              <Route path="/simulator" element={<SimulatorPage />} />
              <Route path="/failed" element={<FailedPage />} />
              <Route path="*" element={<Navigate to="/alerts" replace />} />
            </Routes>
          </Layout>
          <AlertDrawer />
          <RuleEditor />
        </StoreProvider>
        </AuthGate>
      </ToastProvider>
    </HashRouter>
  );
}
