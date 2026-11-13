import { Route, Routes } from "react-router-dom";
import { RequireAuth } from "./components/layout";
import LoginPage from "./pages/LoginPage";
import DashboardPage from "./pages/DashboardPage";
import UsersPage from "./pages/UsersPage";
import DevicesPage from "./pages/DevicesPage";
import ChannelsPage from "./pages/ChannelsPage";
import MembershipsPage from "./pages/MembershipsPage";
import SecurityEventsPage from "./pages/SecurityEventsPage";
import AuditLogsPage from "./pages/AuditLogsPage";
import ServerHealthPage from "./pages/ServerHealthPage";
import RealtimePage from "./pages/RealtimePage";
import NetworkDiagnosticsPage from "./pages/NetworkDiagnosticsPage";
import ConfigurationPage from "./pages/ConfigurationPage";
import SystemSettingsPage from "./pages/SystemSettingsPage";
import AdminRolesPage from "./pages/AdminRolesPage";
import AccessControlPage from "./pages/AccessControlPage";

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route index element={<DashboardPage />} />
        <Route path="/users" element={<UsersPage />} />
        <Route path="/devices" element={<DevicesPage />} />
        <Route path="/channels" element={<ChannelsPage />} />
        <Route path="/memberships" element={<MembershipsPage />} />
        <Route path="/security-events" element={<SecurityEventsPage />} />
        <Route path="/audit-logs" element={<AuditLogsPage />} />
        <Route path="/server-health" element={<ServerHealthPage />} />
        <Route path="/realtime" element={<RealtimePage />} />
        <Route path="/network-diagnostics" element={<NetworkDiagnosticsPage />} />
        <Route path="/configuration" element={<ConfigurationPage />} />
        <Route path="/system-settings" element={<SystemSettingsPage />} />
        <Route path="/admin-roles" element={<AdminRolesPage />} />
        <Route path="/access-control" element={<AccessControlPage />} />
      </Route>
      <Route path="*" element={<RequireAuth />} />
    </Routes>
  );
}
