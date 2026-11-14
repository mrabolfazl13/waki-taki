import { useAdminConfig } from "../lib/queries";
import { ApiError } from "../lib/api";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { ErrorState, LoadingState, UnavailableState } from "../components/ui/states";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";

/**
 * Configuration is displayed only from the backend's GET /admin/config
 * endpoint. Secrets are never displayed — the backend is responsible for
 * redaction (it returns non-sensitive operational values only).
 */
export default function ConfigurationPage() {
  const config = useAdminConfig();

  const entries = config.data ? Object.entries(config.data) : [];

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Configuration</h1>
        <p className="text-sm text-muted-foreground">
          Server-reported configuration (GET /admin/config). Secrets are never displayed — the backend is responsible for redaction.
        </p>
      </div>

      {config.isPending ? (
        <LoadingState />
      ) : config.isError ? (
        config.error instanceof ApiError && config.error.status === 404 ? (
          <UnavailableState
            title="Configuration endpoint unavailable"
            reason="The backend does not currently expose GET /admin/config. No configuration values are shown rather than guessing."
          />
        ) : (
          <ErrorState message={config.error.message} onRetry={() => config.refetch()} />
        )
      ) : entries.length === 0 ? (
        <UnavailableState title="No configuration reported" reason="GET /admin/config returned an empty object." />
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>Configuration</CardTitle>
            <CardDescription>{entries.length} entries reported by the server</CardDescription>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Key</TableHead>
                  <TableHead>Value</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {entries.map(([k, v]) => (
                  <TableRow key={k}>
                    <TableCell className="font-mono text-xs">{k}</TableCell>
                    <TableCell>{v === null ? <span className="text-xs text-warning">null</span> : String(v)}</TableCell>
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
