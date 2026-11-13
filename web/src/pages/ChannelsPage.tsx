import { useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useChannels, useDeleteChannel } from "../lib/queries";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { Input } from "../components/ui/input";
import { EmptyState, ErrorState, LoadingState } from "../components/ui/states";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";

export default function ChannelsPage() {
  const channels = useChannels();
  const del = useDeleteChannel();
  const queryClient = useQueryClient();
  const [search, setSearch] = useState("");
  const [actionError, setActionError] = useState<string | null>(null);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return channels.data ?? [];
    return channels.data?.filter((c) => c.name.toLowerCase().includes(q) || c.id.toLowerCase().includes(q)) ?? [];
  }, [channels.data, search]);

  const handleDelete = async (id: string, name: string) => {
    setActionError(null);
    try {
      await del.mutateAsync(id);
    } catch {
      setActionError(`Failed to delete channel “${name}”.`);
      queryClient.invalidateQueries({ queryKey: ["admin", "channels"] });
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">Channel management</h1>
          <p className="text-sm text-muted-foreground">Channels with server-reported member counts.</p>
        </div>
        {channels.isSuccess && (
          <Input
            type="search"
            aria-label="Search channels"
            placeholder="Search by name or ID…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="max-w-xs"
          />
        )}
      </div>

      {channels.isPending ? (
        <LoadingState />
      ) : channels.isError ? (
        <ErrorState message={channels.error.message} onRetry={() => channels.refetch()} />
      ) : filtered.length === 0 ? (
        <EmptyState
          title={search ? "No matching channels" : "No channels"}
          description={search ? `No channels match “${search}”.` : "The server reports zero channels."}
        />
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>Channels</CardTitle>
            <CardDescription>{filtered.length} channels returned by the server</CardDescription>
          </CardHeader>
          <CardContent>
            {actionError && (
              <p className="mb-3 text-sm text-destructive" role="alert">
                {actionError}
              </p>
            )}
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>ID</TableHead>
                  <TableHead>Members</TableHead>
                  <TableHead>Created</TableHead>
                  <TableHead>
                    <span className="sr-only">Actions</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((c) => (
                  <TableRow key={c.id}>
                    <TableCell className="font-medium">{c.name}</TableCell>
                    <TableCell className="max-w-[16ch] truncate font-mono text-xs text-muted-foreground" title={c.id}>
                      {c.id}
                    </TableCell>
                    <TableCell>
                      {c.member_count !== undefined ? (
                        <span className="tabular-nums">{c.member_count}</span>
                      ) : (
                        <span className="text-xs text-warning" title="The server did not report a member count for this channel">
                          Unavailable
                        </span>
                      )}
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {c.created_at ? new Date(c.created_at).toLocaleDateString() : "—"}
                    </TableCell>
                    <TableCell className="text-right">
                      <Button
                        size="sm"
                        variant="destructive"
                        disabled={del.isPending}
                        onClick={() => handleDelete(c.id, c.name)}
                        aria-label={`Delete channel ${c.name}`}
                      >
                        Delete
                      </Button>
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
