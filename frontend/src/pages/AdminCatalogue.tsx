import { getSession } from "../api/session";
import { AdminShell } from "../components/layout/AdminShell";
import { EmptyState } from "../components/ui/EmptyState";
import { PageHeader } from "../components/ui/PageHeader";

export default function AdminCatalogue() {
  const account = getSession();

  return (
    <AdminShell
      active="catalogue"
      title="Smartphone catalogue"
      email={account?.email ?? "administrator"}
    >
      <div className="mx-auto max-w-[1100px]">
        <PageHeader
          eyebrow="Tech Advisor · Admin"
          title="Smartphone catalogue"
          description={`Signed in as ${account?.email ?? "administrator"}. Catalogue editing controls will be added in a later ticket.`}
        />
        <EmptyState
          icon="▣"
          title="Catalogue management is ready"
          description="The protected catalogue route and admin management API are available. The interactive form is intentionally out of scope for Ticket 1.7."
        />
      </div>
    </AdminShell>
  );
}
