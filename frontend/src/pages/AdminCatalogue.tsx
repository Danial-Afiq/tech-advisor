import { useNavigate } from "react-router-dom";
import { signOut } from "../api/auth";
import { getSession } from "../api/session";
import { ThemeRoot } from "../components/layout/ThemeRoot";
import { Button } from "../components/ui/Button";
import { EmptyState } from "../components/ui/EmptyState";
import { PageHeader } from "../components/ui/PageHeader";
import { ADMIN_HOME_PATH, LOGIN_PATH } from "../routing/paths";

export default function AdminCatalogue() {
  const navigate = useNavigate();
  const account = getSession();

  const handleSignOut = () => {
    signOut();
    navigate(LOGIN_PATH, { replace: true });
  };

  return (
    <ThemeRoot>
      <div className="mx-auto max-w-[1100px] p-7 max-[620px]:p-4">
        <PageHeader
          eyebrow="Tech Advisor · Admin"
          title="Smartphone catalogue"
          description={`Signed in as ${account?.email ?? "administrator"}. Catalogue editing controls will be added in a later ticket.`}
          action={
            <Button size="sm" variant="ghost" onClick={handleSignOut}>
              Sign out
            </Button>
          }
        />
        <EmptyState
          icon="▣"
          title="Catalogue management is ready"
          description="The protected catalogue route and admin CRUD API are available. The interactive form is intentionally out of scope for Ticket 1.7."
          action={
            <Button variant="secondary" onClick={() => navigate(ADMIN_HOME_PATH)}>
              Go to data ingestion
            </Button>
          }
        />
      </div>
    </ThemeRoot>
  );
}
