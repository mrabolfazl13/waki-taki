import { Button } from "./button";

export function Skeleton({ className }: { className?: string }) {
  return <div className={`animate-pulse rounded-md bg-muted ${className ?? ""}`} aria-hidden="true" />;
}

export function LoadingState({ label = "Loading…" }: { label?: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-12" role="status" aria-live="polite">
      <Skeleton className="h-4 w-48" />
      <Skeleton className="h-4 w-32" />
      <span className="text-sm text-muted-foreground">{label}</span>
    </div>
  );
}

export function EmptyState({ title, description }: { title: string; description?: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-1 rounded-lg border border-dashed border-border py-12 text-center">
      <p className="font-medium">{title}</p>
      {description && <p className="text-sm text-muted-foreground">{description}</p>}
    </div>
  );
}

/** Server could not provide data (error / 404 / schema mismatch). Distinct from "0 rows". */
export function UnavailableState({ title = "Data unavailable", reason }: { title?: string; reason?: string }) {
  return (
    <div
      className="flex flex-col items-center justify-center gap-1 rounded-lg border border-warning/40 bg-warning/5 py-12 text-center"
      role="alert"
    >
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="text-warning" aria-hidden="true">
        <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
        <line x1="12" y1="9" x2="12" y2="13" />
        <line x1="12" y1="17" x2="12.01" y2="17" />
      </svg>
      <p className="font-medium text-warning">{title}</p>
      <p className="max-w-md text-sm text-muted-foreground">
        {reason ?? "The server did not return usable data for this view. Values are intentionally not shown rather than displaying misleading zeros."}
      </p>
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-destructive/40 bg-destructive/5 py-10 text-center" role="alert">
      <p className="font-medium text-destructive">Something went wrong</p>
      <p className="max-w-md text-sm text-muted-foreground">{message}</p>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry}>
          Retry
        </Button>
      )}
    </div>
  );
}
