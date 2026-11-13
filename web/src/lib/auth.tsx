import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { getAccessToken, login as apiLogin, logout as apiLogout } from "./api";

interface AuthContextValue {
  isAuthenticated: boolean;
  login: (displayName: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [authenticated, setAuthenticated] = useState<boolean>(() => getAccessToken() !== null);

  const login = useCallback(async (displayName: string, password: string) => {
    await apiLogin(displayName, password);
    setAuthenticated(true);
  }, []);

  const logout = useCallback(async () => {
    await apiLogout();
    setAuthenticated(false);
  }, []);

  // React to token loss from 401 refresh failure in api.ts.
  useEffect(() => {
    const id = window.setInterval(() => {
      setAuthenticated(getAccessToken() !== null);
    }, 2000);
    return () => window.clearInterval(id);
  }, []);

  const value = useMemo(
    () => ({ isAuthenticated: authenticated, login, logout }),
    [authenticated, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside AuthProvider");
  return ctx;
}
