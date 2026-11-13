import { useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useSetUserDisabled, useUsers } from "../lib/queries";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { Input } from "../components/ui/input";
import { EmptyState, ErrorState, LoadingState } from "../components/ui/states";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";

export default function UsersPage() {
  const users = useUsers();
  const disable = useSetUserDisabled();
  const queryClient = useQueryClient();
  const [search, setSearch] = useState("");
  const [actionError, setActionError] = useState<string | null>(null);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return users.data ?? [];
    return users.data?.filter((u) => u.display_name.toLowerCase().includes(q) || u.id.toLowerCase().includes(q)) ?? [];
  }, [users.data, search]);

  const toggleDisabled = async (id: string, currentlyDisabled: boolean) => {
    setActionError(null);
    try {
      await disable.mutateAsync({ id, disabled: !currentlyDisabled });
    } catch {
      setActionError("Failed to update the user. The server rejected or did not accept the request.");
      queryClient.invalidateQueries({ queryKey: ["admin", "users"] });
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">User management</h1>
          <p className="text-sm text-muted-foreground">List, search and disable user accounts.</p>
        </div>
        {users.isSuccess && (
          <Input
            type="search"
            aria-label="Search users"
            placeholder="Search by name or ID…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="max-w-xs"
          />
        )}
      </div>

      {users.isPending ? (
        <LoadingState />
      ) : users.isError ? (
        <ErrorState message={users.error.message} onRetry={() => users.refetch()} />
      ) : filtered.length === 0 ? (
        search ? (
          <EmptyState title="No matching users" description={`No users match “${search}”.`} />
        ) : (
          <EmptyState title="No users" description="The server reports zero users." />
        )
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>Users</CardTitle>
            <CardDescription>{filtered.length} users returned by the server</CardDescription>
          </CardHeader>
          <CardContent>
            {actionError && (
              <p className="mb-3 text-sm text-destructive" role="alert">
                {actionError}
              </p>
            )}
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Display name</TableHead>
                  <TableHead>ID</TableHead>
                  <TableHead>Role</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Created</TableHead>
                  <TableHead>
                    <span className="sr-only">Actions</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((u) => (
                  <TableRow key={u.id}>
                    <TableCell className="font-medium">{u.display_name}</TableCell>
                    <TableCell className="max-w-[16ch] truncate font-mono text-xs text-muted-foreground" title={u.id}>
                      {u.id}
                    </TableCell>
                    <TableCell>
                      <Badge variant={u.role === "admin" ? "default" : "secondary"}>{u.role}</Badge>
                    </TableCell>
                    <TableCell>
                      {u.disabled ? (
                        <Badge variant="destructive">Disabled</Badge>
                      ) : (
                        <Badge variant="success">Active</Badge>
                      )}
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {u.created_at ? new Date(u.created_at).toLocaleDateString() : "—"}
                    </TableCell>
                    <TableCell className="text-right">
                      <Button
                        size="sm"
                        variant={u.disabled ? "outline" : "destructive"}
                        disabled={disable.isPending}
                        onClick={() => toggleDisabled(u.id, !!u.disabled)}
                        aria-label={u.disabled ? `Re-enable ${u.display_name}` : `Disable ${u.display_name}`}
                      >
                        {u.disabled ? "Enable" : "Disable"}
                      </Button>
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
