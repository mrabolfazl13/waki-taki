import { useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { adminRoleInputSchema, userSchema, type User } from "../lib/schemas";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { Input, Label } from "../components/ui/input";
import { Badge } from "../components/ui/badge";
import { EmptyState, ErrorState, LoadingState } from "../components/ui/states";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";
import { useAdminList } from "../lib/queries";

/**
 * Role assignment per the live backend contract: POST /admin/roles with
 * {user_id, role} returns the updated UserOut. There is no GET /admin/roles
 * (roles are the "user"/"admin" enum on accounts), so this page lists users
 * and lets an admin change a user's role by id.
 */
export default function AdminRolesPage() {
  const users = useAdminList<User>("users", "/admin/users", userSchema);
  const queryClient = useQueryClient();
  const [userId, setUserId] = useState("");
  const [role, setRole] = useState<"user" | "admin">("user");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const handleSet = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      const body = adminRoleInputSchema.parse({ user_id: userId.trim(), role });
      const updated = await api("/admin/roles", userSchema, {
        method: "POST",
        body,
      });
      setNotice(`Role of ${updated.display_name} set to ${updated.role}.`);
      setUserId("");
      queryClient.invalidateQueries({ queryKey: ["admin", "users"] });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Request failed.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Administrator roles</h1>
        <p className="text-sm text-muted-foreground">
          Role assignment via POST /admin/roles (ARCHITECTURE.md §5). Roles are the
          account-level “user”/“admin” enum — there is no per-role permission table.
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Set user role</CardTitle>
          <CardDescription>Enter the account id and choose the role to assign.</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSet} className="flex flex-col gap-3 sm:flex-row sm:items-end">
            <div className="flex-1 space-y-1.5">
              <Label htmlFor="role-user">User id</Label>
              <Input
                id="role-user"
                required
                placeholder="e.g. 8b930cade4cc…"
                value={userId}
                onChange={(e) => setUserId(e.target.value)}
              />
            </div>
            <div className="space-y-1.5 sm:w-40">
              <Label htmlFor="role-value">Role</Label>
              <select
                id="role-value"
                className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm"
                value={role}
                onChange={(e) => setRole(e.target.value === "admin" ? "admin" : "user")}
              >
                <option value="user">user</option>
                <option value="admin">admin</option>
              </select>
            </div>
            <Button type="submit" disabled={busy}>
              {busy ? "Saving…" : "Set role"}
            </Button>
          </form>
          {error && (
            <p className="mt-3 text-sm text-destructive" role="alert">
              {error}
            </p>
          )}
          {notice && (
            <p className="mt-3 text-sm text-success" role="status">
              {notice}
            </p>
          )}
        </CardContent>
      </Card>

      {users.isPending ? (
        <LoadingState />
      ) : users.isError ? (
        <ErrorState message={users.error.message} onRetry={() => users.refetch()} />
      ) : (users.data?.length ?? 0) === 0 ? (
        <EmptyState title="No users" description="The server reports zero accounts." />
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>Accounts and current roles</CardTitle>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Id</TableHead>
                  <TableHead>Display name</TableHead>
                  <TableHead>Role</TableHead>
                  <TableHead>Status</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {users.data?.map((u) => (
                  <TableRow key={u.id}>
                    <TableCell className="font-mono text-xs">{u.id.slice(0, 8)}…</TableCell>
                    <TableCell className="font-medium">{u.display_name}</TableCell>
                    <TableCell>
                      <Badge variant={u.role === "admin" ? "default" : "secondary"}>{u.role}</Badge>
                    </TableCell>
                    <TableCell>{u.disabled ? "disabled" : "active"}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
