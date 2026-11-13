import type { ReactNode } from "react";
import { z } from "zod";
import { useAdminList } from "../lib/queries";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { Badge } from "../components/ui/badge";
import { EmptyState, ErrorState, LoadingState } from "../components/ui/states";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";

export interface ColumnDef<T> {
  header: string;
  cell: (row: T) => ReactNode;
}

interface Props<T> {
  title: string;
  description: string;
  queryKey: string;
  path: string;
  itemSchema: z.ZodType<T>;
  columns: ColumnDef<T>[];
  rowKey?: (row: T) => string;
}

/**
 * Page for an admin list endpoint that may not be implemented by the backend
 * yet. Renders loading / error / empty states; errors are surfaced honestly.
 */
export default function GenericListPage<T>({ title, description, queryKey, path, itemSchema, columns, rowKey }: Props<T>) {
  const query = useAdminList<T>(queryKey, path, itemSchema);

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{title}</h1>
        <p className="text-sm text-muted-foreground">{description}</p>
      </div>

      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState message={query.error.message} onRetry={() => query.refetch()} />
      ) : (query.data?.length ?? 0) === 0 ? (
        <EmptyState title="No records" description="The server reports zero records for this view." />
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>{title}</CardTitle>
            <CardDescription>{query.data?.length} records returned by the server</CardDescription>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  {columns.map((c) => (
                    <TableHead key={c.header}>{c.header}</TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {query.data?.map((row, i) => (
                  <TableRow key={rowKey ? rowKey(row) : String(i)}>
                    {columns.map((c) => (
                      <TableCell key={c.header}>{c.cell(row)}</TableCell>
                    ))}
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

export function TruncatedId({ id }: { id: string }) {
  return (
    <span className="block max-w-[16ch] truncate font-mono text-xs text-muted-foreground" title={id}>
      {id}
    </span>
  );
}

export function UnknownBadge() {
  return (
    <Badge variant="warning" title="The server did not provide this value">
      Unavailable
    </Badge>
  );
}
