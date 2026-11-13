import { useAdminHealth } from "../lib/queries";
import { Badge } from "../components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { ErrorState, LoadingState, UnavailableState } from "../components/ui/states";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";

export default function RealtimePage() {
  const health = useAdminHealth();

  // Only report metrics the server actually exposes; never infer connection counts.
  const wsMetrics = health.data
    ? Object.entries(health.data).filter(([k]) => /ws|websocket|socket|connection|realtime|relay|pubsub/i.test(k))
    : [];

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Realtime infrastructure</h1>
        <p className="text-sm text-muted-foreground">
          WebSocket connection counts and relay metrics from GET /admin/health. Values are shown only when the server reports them.
        </p>
      </div>

      {health.isPending ? (
        <LoadingState />
      ) : health.isError ? (
        <ErrorState message={health.error.message} onRetry={() => health.refetch()} />
      ) : wsMetrics.length === 0 ? (
        <UnavailableState
          title="Realtime metrics unavailable"
          reason="GET /admin/health did not report any WebSocket / realtime metrics. Connection counts are not inferred and are never displayed as zero when unknown."
        />
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>Reported realtime metrics</CardTitle>
            <CardDescription>{wsMetrics.length} metric(s) reported by the server</CardDescription>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Metric</TableHead>
                  <TableHead>Value</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {wsMetrics.map(([key, value]) => (
                  <TableRow key={key}>
                    <TableCell className="font-mono text-xs">{key}</TableCell>
                    <TableCell>
                      {typeof value === "number" ? (
                        <span className="text-lg font-semibold tabular-nums">{value}</span>
                      ) : typeof value === "boolean" ? (
                        <Badge variant={value ? "success" : "secondary"}>{String(value)}</Badge>
                      ) : (
                        String(value)
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
