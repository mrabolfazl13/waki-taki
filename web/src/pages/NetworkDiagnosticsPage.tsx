import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { z } from "zod";
import { api } from "../lib/api";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { ErrorState, LoadingState } from "../components/ui/states";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";

const livenessSchema = z.object({}).passthrough();

interface ProbeResult {
  path: string;
  latencyMs: number;
  ok: boolean;
  httpStatus: number | null;
}

/** Measures real HTTP round-trip time from the browser to the backend. */
async function probe(path: string): Promise<ProbeResult> {
  const start = performance.now();
  try {
    await api(path, livenessSchema);
    return { path, latencyMs: Math.round(performance.now() - start), ok: true, httpStatus: 200 };
  } catch (err) {
    const status = err && typeof err === "object" && "status" in err ? (err as { status: number }).status : null;
    return { path, latencyMs: Math.round(performance.now() - start), ok: false, httpStatus: status };
  }
}

export default function NetworkDiagnosticsPage() {
  const [probes, setProbes] = useState<ProbeResult[] | null>(null);
  const [busy, setBusy] = useState(false);

  // Background reachability of the public liveness endpoint.
  const liveness = useQuery({
    queryKey: ["diagnostics", "health-probe"],
    queryFn: () => api("/health", livenessSchema),
    retry: false,
    refetchInterval: 30000,
  });

  const runProbes = async () => {
    setBusy(true);
    const results: ProbeResult[] = [];
    for (const path of ["/health", "/admin/health", "/admin/users"]) {
      results.push(await probe(path));
    }
    setProbes(results);
    setBusy(false);
  };

  const backendReachable = liveness.isSuccess;

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Network diagnostics</h1>
        <p className="text-sm text-muted-foreground">
          Real round-trip latency measurements from this browser to the backend API. No synthetic or cached values.
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Backend reachability</CardTitle>
          <CardDescription>Continuous probe of GET /health (30 s interval)</CardDescription>
        </CardHeader>
        <CardContent>
          {liveness.isPending ? (
            <LoadingState />
          ) : liveness.isError ? (
            <ErrorState message={`Backend not reachable: ${liveness.error.message}`} onRetry={() => liveness.refetch()} />
          ) : (
            <div className="flex items-center gap-3">
              <Badge variant="success">Reachable</Badge>
              <span className="text-sm text-muted-foreground">GET /health responded successfully.</span>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <div>
            <CardTitle>Latency probes</CardTitle>
            <CardDescription>Measured on demand, single round trip per endpoint</CardDescription>
          </div>
          <Button onClick={runProbes} disabled={busy}>
            {busy ? "Probing…" : "Run probes"}
          </Button>
        </CardHeader>
        <CardContent>
          {!probes ? (
            <p className="py-6 text-center text-sm text-muted-foreground">
              No measurements taken yet. Press “Run probes” to measure.
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Endpoint</TableHead>
                  <TableHead>Result</TableHead>
                  <TableHead>HTTP status</TableHead>
                  <TableHead>Round trip</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {probes.map((p) => (
                  <TableRow key={p.path}>
                    <TableCell className="font-mono text-xs">{p.path}</TableCell>
                    <TableCell>{p.ok ? <Badge variant="success">OK</Badge> : <Badge variant="destructive">Failed</Badge>}</TableCell>
                    <TableCell className="tabular-nums">{p.httpStatus ?? "—"}</TableCell>
                    <TableCell className="tabular-nums">{p.latencyMs} ms</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          {!backendReachable && !liveness.isPending && (
            <p className="mt-3 text-sm text-warning" role="status">
              Note: the backend is not reachable right now, so probe results may all fail.
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
