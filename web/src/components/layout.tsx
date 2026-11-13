import { useEffect, useState } from "react";
import { NavLink, Navigate, Outlet, useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../lib/auth";
import { Button } from "./ui/button";

const navItems: { to: string; label: string }[] = [
  { to: "/", label: "Dashboard" },
  { to: "/users", label: "Users" },
  { to: "/devices", label: "Devices" },
  { to: "/channels", label: "Channels" },
  { to: "/memberships", label: "Memberships" },
  { to: "/security-events", label: "Security Events" },
  { to: "/audit-logs", label: "Audit Logs" },
  { to: "/server-health", label: "Server Health" },
  { to: "/realtime", label: "Realtime Infra" },
  { to: "/network-diagnostics", label: "Network Diagnostics" },
  { to: "/configuration", label: "Configuration" },
  { to: "/system-settings", label: "System Settings" },
  { to: "/admin-roles", label: "Administrator Roles" },
  { to: "/access-control", label: "Access Control" },
];

function ThemeToggle() {
  const [dark, setDark] = useState<boolean>(() =>
    window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false,
  );

  useEffect(() => {
    document.documentElement.classList.toggle("dark", dark);
  }, [dark]);

  return (
    <Button
      variant="ghost"
      size="sm"
      aria-label={dark ? "Switch to light mode" : "Switch to dark mode"}
      title={dark ? "Light mode" : "Dark mode"}
      onClick={() => setDark((d) => !d)}
    >
      {dark ? "Light" : "Dark"}
    </Button>
  );
}

export function RequireAuth() {
  const { isAuthenticated } = useAuth();
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  return <AppLayout />;
}

export function AppLayout() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const handleLogout = async () => {
    await logout();
    queryClient.clear();
    navigate("/login", { replace: true });
  };

  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[240px_1fr]">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:bg-card focus:p-2"
      >
        Skip to content
      </a>
      <header className="sticky top-0 z-40 flex items-center justify-between border-b border-border bg-card px-4 py-2 lg:hidden">
        <span className="font-semibold">SecureTalk Admin</span>
        <div className="flex items-center gap-2">
          <ThemeToggle />
          <Button variant="ghost" size="sm" onClick={handleLogout}>
            Sign out
          </Button>
        </div>
      </header>
      <aside className="hidden border-r border-border bg-card lg:flex lg:flex-col">
        <div className="border-b border-border px-5 py-4">
          <span className="text-base font-semibold">SecureTalk</span>
          <span className="block text-xs text-muted-foreground">Admin Console</span>
        </div>
        <nav className="flex-1 overflow-y-auto p-3" aria-label="Main navigation">
          <ul className="space-y-1">
            {navItems.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  end={item.to === "/"}
                  className={({ isActive }) =>
                    `block rounded-md px-3 py-2 text-sm transition-colors ${
                      isActive
                        ? "bg-accent font-medium text-accent-foreground"
                        : "text-muted-foreground hover:bg-muted hover:text-foreground"
                    }`
                  }
                >
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <div className="border-t border-border p-3">
          <Button variant="outline" size="sm" className="w-full" onClick={handleLogout}>
            Sign out
          </Button>
        </div>
      </aside>
      <div>
        <header className="hidden items-center justify-between border-b border-border bg-card px-6 py-3 lg:flex">
          <ThemeToggle />
        </header>
        <main id="main-content" className="p-4 lg:p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
