import { useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { adminRoleSchema, type AdminRole } from "../lib/schemas";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { Input, Label } from "../components/ui/input";
import { Badge } from "../components/ui/badge";
import { EmptyState, ErrorState, LoadingState, UnavailableState } from "../components/ui/states";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";
import { useAdminList } from "../lib/queries";

export default function AdminRolesPage() {
  const roles = useAdminList<AdminRole>("roles", "/admin/roles", adminRoleSchema);
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [permissions, setPermissions] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const handleCreate = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      const perms = permissions.split(",").map((p) => p.trim()).filter(Boolean);
      await api("/admin/roles", adminRoleSchema, {
        method: "POST",
        body: { name, permissions: perms },
      });
      setNotice(`Role “${name}” created.`);
      setName("");
      setPermissions("");
      queryClient.invalidateQueries({ queryKey: ["admin", "roles"] });
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
          Roles defined via POST /admin/roles (ARCHITECTURE.md §5).
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Create role</CardTitle>
          <CardDescription>Permissions are comma-separated scope names.</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleCreate} className="flex flex-col gap-3 sm:flex-row sm:items-end">
            <div className="space-y-1.5 sm:w-48">
              <Label htmlFor="role-name">Role name</Label>
              <Input id="role-name" required value={name} onChange={(e) => setName(e.target.value)} />
            </div>
            <div className="flex-1 space-y-1.5">
              <Label htmlFor="role-permissions">Permissions</Label>
              <Input
                id="role-permissions"
                placeholder="users.read, channels.delete, …"
                value={permissions}
                onChange={(e) => setPermissions(e.target.value)}
              />
            </div>
            <Button type="submit" disabled={busy}>
              {busy ? "Creating…" : "Create role"}
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

      {roles.isPending ? (
        <LoadingState />
      ) : roles.isError ? (
        <ErrorState message={roles.error.message} onRetry={() => roles.refetch()} />
      ) : (roles.data?.length ?? 0) === 0 ? (
        <EmptyState title="No roles defined" description="The server reports zero admin roles." />
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>Existing roles</CardTitle>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Permissions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {roles.data?.map((r, i) => (
                  <TableRow key={r.id ?? i}>
                    <TableCell className="font-medium">{r.name}</TableCell>
                    <TableCell>
                      {r.permissions && r.permissions.length > 0 ? (
                        <span className="flex flex-wrap gap-1.5">
                          {r.permissions.map((p) => (
                            <Badge key={p} variant="secondary">
                              {p}
                            </Badge>
                          ))}
                        </span>
                      ) : (
                        <UnavailableState title="Permissions unavailable" reason="The server did not include a permission list for this role." />
                      )}
                    </TableCell>
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
