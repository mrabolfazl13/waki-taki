import { useMemo, useState } from "react";
import { useSecurityEvents } from "../lib/queries";
import { SeverityBadge } from "../components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { Label, Select } from "../components/ui/input";
import { EmptyState, ErrorState, LoadingState } from "../components/ui/states";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";
import { formatDateTime } from "../lib/utils";

const SEVERITIES = ["all", "critical", "high", "medium", "low", "info"] as const;

function renderDetail(detail: unknown): string {
  if (detail === undefined || detail === null) return "—";
  if (typeof detail === "string") return detail;
  try {
    return JSON.stringify(detail);
  } catch {
    return String(detail);
  }
}

export default function SecurityEventsPage() {
  const events = useSecurityEvents();
  const [severity, setSeverity] = useState<(typeof SEVERITIES)[number]>("all");

  const filtered = useMemo(() => {
    if (!events.data) return null;
    return severity === "all" ? events.data : events.data.filter((e) => e.severity.toLowerCase() === severity);
  }, [events.data, severity]);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">Security events</h1>
          <p className="text-sm text-muted-foreground">Server-recorded events, filterable by severity.</p>
        </div>
        {events.isSuccess && (
          <div className="flex items-center gap-2">
            <Label htmlFor="severity-filter">Severity</Label>
            <Select
              id="severity-filter"
              value={severity}
              onChange={(e) => setSeverity(e.target.value as (typeof SEVERITIES)[number])}
              className="w-36"
            >
              {SEVERITIES.map((s) => (
                <option key={s} value={s}>
                  {s === "all" ? "All severities" : s}
                </option>
              ))}
            </Select>
          </div>
        )}
      </div>

      {events.isPending ? (
        <LoadingState />
      ) : events.isError ? (
        <ErrorState message={events.error.message} onRetry={() => events.refetch()} />
      ) : filtered === null || filtered.length === 0 ? (
        <EmptyState
          title={severity === "all" ? "No security events" : `No ${severity} events`}
          description={
            severity === "all"
              ? "The server reports zero recorded security events."
              : "No events at this severity have been recorded."
          }
        />
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>Events</CardTitle>
            <CardDescription>{filtered.length} events returned by the server</CardDescription>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Type</TableHead>
                  <TableHead>Severity</TableHead>
                  <TableHead>Channel</TableHead>
                  <TableHead>User</TableHead>
                  <TableHead>Detail</TableHead>
                  <TableHead>Recorded</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((e) => (
                  <TableRow key={e.id}>
                    <TableCell className="font-medium">{e.type}</TableCell>
                    <TableCell>
                      <SeverityBadge severity={e.severity} />
                    </TableCell>
                    <TableCell className="text-muted-foreground">{e.channel_id ?? "—"}</TableCell>
                    <TableCell className="text-muted-foreground">{e.user_id ?? "—"}</TableCell>
                    <TableCell className="max-w-[24ch] truncate text-muted-foreground" title={renderDetail(e.detail)}>
                      {renderDetail(e.detail)}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {e.created_at ? formatDateTime(e.created_at) : "—"}
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
