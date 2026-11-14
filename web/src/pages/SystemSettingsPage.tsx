import { useAdminSettings, useUpdateAdminSettings } from "../lib/queries";
import { ApiError } from "../lib/api";
import type { AdminSettingsInput } from "../lib/schemas";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { ErrorState, LoadingState, UnavailableState } from "../components/ui/states";
import { useState } from "react";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";

const SETTING_LABELS: Record<string, string> = {
  registration_enabled: "Allow new account registration",
  maintenance_mode: "Maintenance mode",
  invite_creation_enabled: "Allow invite creation",
};

const SETTING_KEYS = ["registration_enabled", "maintenance_mode", "invite_creation_enabled"] as const;

/** Operational toggles edited via PUT /admin/settings (admin-gated, audited). */
export default function SystemSettingsPage() {
  const settings = useAdminSettings();
  const update = useUpdateAdminSettings();
  const [draft, setDraft] = useState<AdminSettingsInput | null>(null);

  if (settings.isPending) {
    return (
      <div className="space-y-4">
        <Header />
        <LoadingState />
      </div>
    );
  }

  if (settings.isError) {
    return (
      <div className="space-y-4">
        <Header />
        {settings.error instanceof ApiError && settings.error.status === 404 ? (
          <UnavailableState
            title="Settings endpoint unavailable"
            reason="The backend does not currently expose GET /admin/settings. No settings are displayed rather than showing defaults the server may not use."
          />
        ) : (
          <ErrorState message={settings.error.message} onRetry={() => settings.refetch()} />
        )}
      </div>
    );
  }

  const effective: AdminSettingsInput = draft ?? {
    registration_enabled: settings.data.registration_enabled,
    maintenance_mode: settings.data.maintenance_mode,
    invite_creation_enabled: settings.data.invite_creation_enabled,
  };

  const dirty = Object.entries(effective).some(
    ([k, v]) => v !== undefined && settings.data[k as keyof typeof settings.data] !== v,
  );

  const save = () => {
    const changed = Object.fromEntries(
      Object.entries(effective).filter(([k, v]) => v !== undefined && settings.data[k as keyof typeof settings.data] !== v),
    );
    update.mutate(changed, {
      onSuccess: () => setDraft(null),
    });
  };

  return (
    <div className="space-y-4">
      <Header />
      <Card>
        <CardHeader>
          <CardTitle>Settings</CardTitle>
          <CardDescription>
            Persisted server-side; changes are audit-logged. {update.isPending ? "Saving…" : ""}
            {update.isError ? ` Save failed: ${update.error.message}` : ""}
          </CardDescription>
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
              {SETTING_KEYS.map((key) => (
                <TableRow key={key}>
                  <TableCell>{SETTING_LABELS[key] ?? key}</TableCell>
                  <TableCell>
                    <input
                      type="checkbox"
                      aria-label={SETTING_LABELS[key] ?? key}
                      checked={effective[key] ?? false}
                      onChange={(e) => setDraft({ ...effective, [key]: e.target.checked })}
                    />
                  </TableCell>
                </TableRow>
              ))}
              <TableRow>
                <TableCell />
                <TableCell>
                  <Button size="sm" disabled={!dirty || update.isPending} onClick={save}>
                    Save changes
                  </Button>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}

function Header() {
  return (
    <div>
      <h1 className="text-2xl font-semibold">System settings</h1>
      <p className="text-sm text-muted-foreground">
        Server-side settings reported by GET /admin/settings and edited via PUT /admin/settings.
      </p>
    </div>
  );
}
