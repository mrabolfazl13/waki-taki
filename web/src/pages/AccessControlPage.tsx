import { useUsers } from "../lib/queries";
import { Badge } from "../components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { EmptyState, ErrorState, LoadingState } from "../components/ui/states";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";
import { TruncatedId } from "./GenericListPage";

export default function AccessControlPage() {
  const users = useUsers();

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Access control</h1>
        <p className="text-sm text-muted-foreground">
          Administrators and their access level, derived from the live user list.
        </p>
      </div>

      {users.isPending ? (
        <LoadingState />
      ) : users.isError ? (
        <ErrorState message={users.error.message} onRetry={() => users.refetch()} />
      ) : (users.data?.length ?? 0) === 0 ? (
        <EmptyState title="No users" description="The server reports zero users." />
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>Accounts by role</CardTitle>
            <CardDescription>
              {(users.data?.filter((u) => u.role === "admin").length ?? 0)} administrator(s) of{" "}
              {users.data?.length} account(s)
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Display name</TableHead>
                  <TableHead>ID</TableHead>
                  <TableHead>Role</TableHead>
                  <TableHead>Status</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {users.data
                  ?.filter((u) => u.role === "admin")
                  .map((u) => (
                    <TableRow key={u.id}>
                      <TableCell className="font-medium">{u.display_name}</TableCell>
                      <TableCell>
                        <TruncatedId id={u.id} />
                      </TableCell>
                      <TableCell>
                        <Badge>{u.role}</Badge>
                      </TableCell>
                      <TableCell>
                        {u.disabled ? <Badge variant="destructive">Disabled</Badge> : <Badge variant="success">Active</Badge>}
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
