import { Link } from "react-router-dom";
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { useAdminHealth, useChannels, useSecurityEvents, useUsers } from "../lib/queries";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { LoadingState, UnavailableState } from "../components/ui/states";
import { SeverityBadge } from "../components/ui/badge";

function StatCard({
  title,
  value,
  loading,
  unavailable,
  href,
}: {
  title: string;
  value: number | null;
  loading: boolean;
  unavailable: boolean;
  href: string;
}) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardDescription>
          <Link to={href} className="hover:underline">
            {title}
          </Link>
        </CardDescription>
      </CardHeader>
      <CardContent>
        {loading ? (
          <div className="h-8 w-16 animate-pulse rounded bg-muted" aria-hidden="true" />
        ) : unavailable ? (
          <span className="text-sm text-warning">Unavailable</span>
        ) : (
          <span className="text-3xl font-semibold tabular-nums">{value}</span>
        )}
      </CardContent>
    </Card>
  );
}

const SEVERITY_ORDER = ["critical", "high", "medium", "low", "info"] as const;

export default function DashboardPage() {
  const users = useUsers();
  const channels = useChannels();
  const events = useSecurityEvents();
  const health = useAdminHealth();

  const anyLoading = users.isPending || channels.isPending || events.isPending || health.isPending;

  if (anyLoading) return <LoadingState label="Loading dashboard…" />;

  const usersUnavailable = users.isError;
  const channelsUnavailable = channels.isError;
  const eventsUnavailable = events.isError;
  const healthUnavailable = health.isError;

  const disabledUsers = users.data?.filter((u) => u.disabled).length ?? null;
  const adminUsers = users.data?.filter((u) => u.role === "admin").length ?? null;

  const severityCounts = new Map<string, number>();
  for (const e of events.data ?? []) {
    const s = e.severity.toLowerCase();
    severityCounts.set(s, (severityCounts.get(s) ?? 0) + 1);
  }
  const chartData = SEVERITY_ORDER.map((sev) => ({ severity: sev, count: severityCounts.get(sev) ?? 0 }));
  const hasChartData = events.data != null;

  // Realtime metrics only if the backend actually exposes them in /admin/health.
  const healthEntries = health.data ? Object.entries(health.data) : [];
  const wsEntries = healthEntries.filter(([k]) => /ws|websocket|socket|connection/i.test(k));
  const wsValue = wsEntries.length === 1 && typeof wsEntries[0]?.[1] === "number" ? (wsEntries[0][1] as number) : null;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Dashboard</h1>
        <p className="text-sm text-muted-foreground">Live overview from the admin API.</p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard title="Total users" value={users.data?.length ?? null} loading={users.isPending} unavailable={usersUnavailable} href="/users" />
        <StatCard title="Channels" value={channels.data?.length ?? null} loading={channels.isPending} unavailable={channelsUnavailable} href="/channels" />
        <StatCard title="Disabled users" value={disabledUsers} loading={users.isPending} unavailable={usersUnavailable} href="/users" />
        <StatCard title="Admin users" value={adminUsers} loading={users.isPending} unavailable={usersUnavailable} href="/access-control" />
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Security events by severity</CardTitle>
            <CardDescription>
              {hasChartData ? `${events.data?.length ?? 0} events reported by the server` : "Source data unavailable"}
            </CardDescription>
          </CardHeader>
          <CardContent>
            {eventsUnavailable ? (
              <UnavailableState title="Event data unavailable" reason="GET /admin/security-events did not return usable data." />
            ) : hasChartData && (events.data?.length ?? 0) > 0 ? (
              <div className="h-64" role="img" aria-label="Bar chart of security event counts by severity">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={chartData}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
                    <XAxis dataKey="severity" stroke="var(--color-muted-foreground)" fontSize={12} />
                    <YAxis allowDecimals={false} stroke="var(--color-muted-foreground)" fontSize={12} />
                    <Tooltip
                      contentStyle={{ background: "var(--color-card)", border: "1px solid var(--color-border)", borderRadius: 8 }}
                      formatter={(value) => [value, "events"]}
                    />
                    <Bar dataKey="count" fill="var(--color-primary)" radius={4} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            ) : (
              <p className="py-8 text-center text-sm text-muted-foreground">
                No security events have been recorded.
              </p>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Realtime connections</CardTitle>
            <CardDescription>WebSocket metrics from GET /admin/health</CardDescription>
          </CardHeader>
          <CardContent>
            {healthUnavailable ? (
              <UnavailableState title="Health data unavailable" reason="GET /admin/health did not return usable data." />
            ) : wsValue !== null ? (
              <div className="flex items-baseline gap-2">
                <span className="text-3xl font-semibold tabular-nums">{wsValue}</span>
                <span className="text-sm text-muted-foreground">{wsEntries[0]?.[0]}</span>
              </div>
            ) : (
              <UnavailableState
                title="Realtime metrics unavailable"
                reason="The health endpoint did not report any WebSocket connection metrics. Counts are not inferred."
              />
            )}
            <div className="mt-3 text-sm">
              <Link to="/realtime" className="text-primary hover:underline">
                Realtime infrastructure →
              </Link>
            </div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Recent security events</CardTitle>
        </CardHeader>
        <CardContent>
          {eventsUnavailable ? (
            <UnavailableState />
          ) : (events.data?.length ?? 0) === 0 ? (
            <p className="py-6 text-center text-sm text-muted-foreground">No security events recorded.</p>
          ) : (
            <ul className="divide-y divide-border">
              {events.data?.slice(0, 5).map((e) => (
                <li key={e.id} className="flex items-center justify-between gap-3 py-2">
                  <span className="min-w-0 truncate text-sm">{e.type}</span>
                  <span className="flex items-center gap-3 whitespace-nowrap text-xs text-muted-foreground">
                    {e.created_at && <span>{new Date(e.created_at).toLocaleString()}</span>}
                    <SeverityBadge severity={e.severity} />
                  </span>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
