import { useQuery } from "@tanstack/react-query";
import { api, ApiError } from "../lib/api";
import { healthSchema, type HealthReport } from "../lib/schemas";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { ErrorState, LoadingState, UnavailableState } from "../components/ui/states";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";

/** System settings are shown only from a real admin settings endpoint. */
export default function SystemSettingsPage() {
  const settings = useQuery<HealthReport>({
    queryKey: ["admin", "settings"],
    queryFn: () => api("/admin/settings", healthSchema),
    retry: false,
  });

  const entries = settings.data ? Object.entries(settings.data) : [];

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">System settings</h1>
        <p className="text-sm text-muted-foreground">
          Server-side settings reported by GET /admin/settings.
        </p>
      </div>

      {settings.isPending ? (
        <LoadingState />
      ) : settings.isError ? (
        settings.error instanceof ApiError && settings.error.status === 404 ? (
          <UnavailableState
            title="Settings endpoint unavailable"
            reason="The backend does not currently expose GET /admin/settings. No settings are displayed rather than showing defaults the server may not use."
          />
        ) : (
          <ErrorState message={settings.error.message} onRetry={() => settings.refetch()} />
        )
      ) : entries.length === 0 ? (
        <UnavailableState title="No settings reported" reason="GET /admin/settings returned an empty object." />
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>Settings</CardTitle>
            <CardDescription>{entries.length} settings reported by the server</CardDescription>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Setting</TableHead>
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
