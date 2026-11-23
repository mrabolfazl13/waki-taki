import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  AUTH_INVALIDATED,
  getAccessToken,
  login as apiLogin,
  logout as apiLogout,
} from "./api";

interface AuthContextValue {
  isAuthenticated: boolean;
  login: (displayName: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const queryClient = useQueryClient();
  const [authenticated, setAuthenticated] = useState<boolean>(() => getAccessToken() !== null);

  const login = useCallback(async (displayName: string, password: string) => {
    await apiLogin(displayName, password);
    setAuthenticated(true);
  }, []);

  const logout = useCallback(async () => {
    await apiLogout();
    queryClient.clear();
    setAuthenticated(false);
  }, [queryClient]);

  // A failed refresh clears tokens in api.ts and emits this event. Drop the
  // cached admin data in the same tick so a second user never sees stale data.
  useEffect(() => {
    const onInvalidated = () => {
      queryClient.clear();
      setAuthenticated(false);
    };
    window.addEventListener(AUTH_INVALIDATED, onInvalidated);
    return () => window.removeEventListener(AUTH_INVALIDATED, onInvalidated);
  }, [queryClient]);

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
