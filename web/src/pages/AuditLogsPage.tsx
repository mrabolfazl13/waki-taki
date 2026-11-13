import { useAuditLogs } from "../lib/queries";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { EmptyState, ErrorState, LoadingState } from "../components/ui/states";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";
import { formatDateTime } from "../lib/utils";

function renderDetail(detail: unknown): string {
  if (detail === undefined || detail === null) return "—";
  if (typeof detail === "string") return detail;
  try {
    return JSON.stringify(detail);
  } catch {
    return String(detail);
  }
}

export default function AuditLogsPage() {
  const logs = useAuditLogs();

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Audit logs</h1>
        <p className="text-sm text-muted-foreground">Administrative actions recorded by the backend (GET /admin/audit).</p>
      </div>

      {logs.isPending ? (
        <LoadingState />
      ) : logs.isError ? (
        <ErrorState message={logs.error.message} onRetry={() => logs.refetch()} />
      ) : (logs.data?.length ?? 0) === 0 ? (
        <EmptyState title="No audit entries" description="The server reports zero audit log entries." />
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>Entries</CardTitle>
            <CardDescription>{logs.data?.length} entries returned by the server</CardDescription>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Action</TableHead>
                  <TableHead>Actor (admin)</TableHead>
                  <TableHead>Target</TableHead>
                  <TableHead>Detail</TableHead>
                  <TableHead>Recorded</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {logs.data?.map((l) => (
                  <TableRow key={l.id}>
                    <TableCell className="font-medium">{l.action}</TableCell>
                    <TableCell className="max-w-[16ch] truncate font-mono text-xs text-muted-foreground" title={l.actor_admin_id ?? undefined}>
                      {l.actor_admin_id ?? "—"}
                    </TableCell>
                    <TableCell className="text-muted-foreground">{l.target ?? "—"}</TableCell>
                    <TableCell className="max-w-[24ch] truncate text-muted-foreground" title={renderDetail(l.detail)}>
                      {renderDetail(l.detail)}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {l.created_at ? formatDateTime(l.created_at) : "—"}
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
