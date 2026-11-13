import GenericListPage, { TruncatedId, UnknownBadge } from "./GenericListPage";
import { deviceSchema, type Device } from "../lib/schemas";
import { Badge } from "../components/ui/badge";

export default function DevicesPage() {
  return (
    <GenericListPage<Device>
      title="Device management"
      description="Registered devices across all users."
      queryKey="devices"
      path="/admin/devices"
      itemSchema={deviceSchema}
      rowKey={(d) => d.id}
      columns={[
        { header: "Device ID", cell: (d) => <TruncatedId id={d.id} /> },
        { header: "User ID", cell: (d) => (d.user_id ? <TruncatedId id={d.user_id} /> : <UnknownBadge />) },
        { header: "Name", cell: (d) => d.name ?? <UnknownBadge /> },
        {
          header: "Status",
          cell: (d) =>
            d.revoked_at != null ? (
              <Badge variant="destructive">Revoked</Badge>
            ) : (
              <Badge variant="success">Active</Badge>
            ),
        },
        {
          header: "Registered",
          cell: (d) => (d.registered_at ? new Date(d.registered_at).toLocaleDateString() : "—"),
        },
      ]}
    />
  );
}
