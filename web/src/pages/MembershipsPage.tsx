import GenericListPage, { TruncatedId, UnknownBadge } from "./GenericListPage";
import { membershipSchema, type Membership } from "../lib/schemas";
import { Badge } from "../components/ui/badge";

export default function MembershipsPage() {
  return (
    <GenericListPage<Membership>
      title="Membership management"
      description="Channel memberships reported by the server."
      queryKey="memberships"
      path="/admin/memberships"
      itemSchema={membershipSchema}
      columns={[
        { header: "Channel ID", cell: (m) => <TruncatedId id={m.channel_id} /> },
        { header: "User ID", cell: (m) => <TruncatedId id={m.user_id} /> },
        { header: "Role", cell: (m) => (m.role ? <Badge variant="secondary">{m.role}</Badge> : <UnknownBadge />) },
        { header: "Joined", cell: (m) => (m.joined_at ? new Date(m.joined_at).toLocaleDateString() : "—") },
      ]}
    />
  );
}
