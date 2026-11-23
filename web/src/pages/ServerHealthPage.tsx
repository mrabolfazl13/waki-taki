import { useQuery } from "@tanstack/react-query";
import { z } from "zod";
import { apiPublic } from "../lib/api";
import { useAdminHealth } from "../lib/queries";
import { Badge } from "../components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { EmptyState, ErrorState, LoadingState, UnavailableState } from "../components/ui/states";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";

const livenessSchema = z.object({
  status: z.string().optional(),
}).passthrough();

function renderValue(v: string | number | boolean | null): React.ReactNode {
  if (v === null) return <span className="text-xs text-warning">null</span>;
  if (typeof v === "boolean") return <Badge variant={v ? "success" : "secondary"}>{String(v)}</Badge>;
  return <span className="tabular-nums">{String(v)}</span>;
}

export default function ServerHealthPage() {
  const adminHealth = useAdminHealth();
  const liveness = useQuery({
    queryKey: ["public", "health"],
    queryFn: () => apiPublic("/health", livenessSchema),
    refetchInterval: 15000,
  });

  const entries = adminHealth.data ? Object.entries(adminHealth.data) : [];

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Server health</h1>
        <p className="text-sm text-muted-foreground">
          Liveness (GET /health) and admin-gated health detail (GET /admin/health), refreshed every 15&nbsp;s.
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Public liveness probe</CardTitle>
          <CardDescription>GET /health — intentionally carries no detail</CardDescription>
        </CardHeader>
        <CardContent>
          {liveness.isPending ? (
            <LoadingState />
          ) : liveness.isError ? (
            <ErrorState message={liveness.error.message} onRetry={() => liveness.refetch()} />
          ) : (
            <div className="flex items-center gap-3">
              <Badge variant="success">Reachable</Badge>
              {typeof liveness.data.status === "string" && <span className="text-sm text-muted-foreground">status: {liveness.data.status}</span>}
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Admin health detail</CardTitle>
          <CardDescription>GET /admin/health — all server-reported metrics</CardDescription>
        </CardHeader>
        <CardContent>
          {adminHealth.isPending ? (
            <LoadingState />
          ) : adminHealth.isError ? (
            <ErrorState message={adminHealth.error.message} onRetry={() => adminHealth.refetch()} />
          ) : entries.length === 0 ? (
            <EmptyState title="No metrics reported" description="The health endpoint returned an empty metric set." />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Metric</TableHead>
                  <TableHead>Value</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {entries.map(([key, value]) => (
                  <TableRow key={key}>
                    <TableCell className="font-mono text-xs">{key}</TableCell>
                    <TableCell>{renderValue(value)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {adminHealth.isSuccess && entries.length === 0 && (
        <UnavailableState title="No metrics available" reason="GET /admin/health returned an empty object." />
      )}
    </div>
  );
}
