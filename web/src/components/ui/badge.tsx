import type { HTMLAttributes } from "react";
import { cn } from "../../lib/utils";

type Variant = "default" | "secondary" | "destructive" | "warning" | "success" | "outline";

const variants: Record<Variant, string> = {
  default: "bg-primary/15 text-primary",
  secondary: "bg-secondary text-secondary-foreground",
  destructive: "bg-destructive/15 text-destructive",
  warning: "bg-warning/15 text-warning",
  success: "bg-success/15 text-success",
  outline: "border border-border text-muted-foreground",
};

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  variant?: Variant;
}

export function Badge({ className, variant = "default", ...props }: BadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium whitespace-nowrap",
        variants[variant],
        className,
      )}
      {...props}
    />
  );
}

const severityVariant: Record<string, Variant> = {
  critical: "destructive",
  high: "destructive",
  medium: "warning",
  low: "secondary",
  info: "outline",
};

export function SeverityBadge({ severity }: { severity: string }) {
  const s = severity.toLowerCase();
  return <Badge variant={severityVariant[s] ?? "outline"}>{severity}</Badge>;
}
