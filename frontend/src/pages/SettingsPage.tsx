import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  getProfile,
  signOut,
  updateRecommendationEmailPreference,
} from "../api/auth";
import { ApiError } from "../api/client";
import { getSession } from "../api/session";
import { AppShell } from "../components/layout/AppShell";
import type { NavKey } from "../components/layout/Sidebar";
import { NotificationCenter } from "../components/notifications/NotificationCenter";
import { Card } from "../components/ui/Card";
import { LoadingBlock } from "../components/ui/LoadingBlock";
import { PageHeader } from "../components/ui/PageHeader";
import {
  DASHBOARD_PATH,
  DEVICES_PATH,
  LOGIN_PATH,
  SETTINGS_PATH,
} from "../routing/paths";

type SettingsView = "overview" | "notifications";

type SettingsRowProps = {
  icon: string;
  title: string;
  description: string;
  onClick?: () => void;
};

function SettingsRow({
  icon,
  title,
  description,
  onClick,
}: SettingsRowProps) {
  const content = (
    <>
      <span
        aria-hidden="true"
        className="grid h-12 w-12 shrink-0 place-items-center rounded-[14px] bg-white/[0.06] text-[20px] text-[#a8b5c9]"
      >
        {icon}
      </span>

      <div className="min-w-0 flex-1 text-left">
        <h2 className="m-0 text-[16px] font-semibold text-[#eef5ff]">
          {title}
        </h2>

        <p className="mt-1 mb-0 text-[13px] leading-[1.5] text-[#8fa0b8]">
          {description}
        </p>
      </div>

      {onClick ? (
        <span
          aria-hidden="true"
          className="text-[28px] leading-none text-[#a995ff]"
        >
          ›
        </span>
      ) : (
        <span className="shrink-0 rounded-full border border-white/[0.1] bg-white/[0.05] px-3 py-1.5 text-[10px] font-bold tracking-[0.08em] text-[#8fa0b8] uppercase">
          Coming soon
        </span>
      )}
    </>
  );

  if (onClick) {
    return (
      <button
        type="button"
        onClick={onClick}
        className="flex w-full items-center gap-4 border-b border-white/[0.08] px-6 py-6 transition-colors last:border-b-0 hover:bg-[#7c5cff]/10 focus-visible:outline-2 focus-visible:outline-offset-[-3px] focus-visible:outline-[#7c5cff]"
      >
        {content}
      </button>
    );
  }

  return (
    <div
      aria-disabled="true"
      className="flex items-center gap-4 border-b border-white/[0.08] px-6 py-6 opacity-65 last:border-b-0"
    >
      {content}
    </div>
  );
}

export default function SettingsPage() {
  const navigate = useNavigate();
  const session = getSession();
  const [view, setView] = useState<SettingsView>("overview");
  const [emailEnabled, setEmailEnabled] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [savedMessage, setSavedMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    getProfile()
      .then((profile) => {
        if (!cancelled) {
          setEmailEnabled(profile.recommendationEmailsEnabled);
          setError(null);
        }
      })
      .catch((err: Error) => {
        if (cancelled) return;

        if (err instanceof ApiError && err.status === 401) {
          void navigate(LOGIN_PATH, { replace: true });
          return;
        }

        setError(err.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [navigate]);

  if (!session) return null;

  const handleNavigate = (key: NavKey) => {
    if (key === "dashboard") void navigate(DASHBOARD_PATH);
    if (key === "devices") void navigate(DEVICES_PATH);
    if (key === "settings") void navigate(SETTINGS_PATH);
  };

  const handleSignOut = () => {
    signOut();
    void navigate(LOGIN_PATH, { replace: true });
  };

  const openNotifications = () => {
    setSavedMessage(null);
    setError(null);
    setView("notifications");
  };

  const closeNotifications = () => {
    setSavedMessage(null);
    setError(null);
    setView("overview");
  };

  const handleEmailToggle = async () => {
    const nextValue = !emailEnabled;
    setSaving(true);
    setError(null);
    setSavedMessage(null);

    try {
      const profile =
        await updateRecommendationEmailPreference(nextValue);

      setEmailEnabled(profile.recommendationEmailsEnabled);
      setSavedMessage(
        profile.recommendationEmailsEnabled
          ? "Recommendation emails enabled."
          : "Recommendation emails disabled."
      );
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        void navigate(LOGIN_PATH, { replace: true });
        return;
      }

      setError(
        err instanceof Error
          ? err.message
          : "Your notification preference could not be saved."
      );
    } finally {
      setSaving(false);
    }
  };

  return (
    <AppShell
      active="settings"
      title="Settings"
      user={{
        name: session.email.split("@")[0],
        email: session.email,
      }}
      topbarActions={<NotificationCenter />}
      onNavigate={handleNavigate}
      onSignOut={handleSignOut}
    >
      <PageHeader
        eyebrow="Account preferences"
        title="Settings"
        description="Manage your account and choose how Tech Advisor communicates with you."
      />

      {loading ? (
        <LoadingBlock label="Loading your settings…" />
      ) : view === "overview" ? (
        <div className="w-full">
          <Card className="overflow-hidden shadow-[0_18px_50px_rgba(0,0,0,.22)]">
            <div className="border-b border-white/[0.08] px-6 py-5">
              <p className="m-0 text-[12px] font-bold tracking-[0.13em] text-[#8fa0b8] uppercase">
                Account settings
              </p>

              <h2 className="mt-2 mb-0 text-[21px] font-bold text-[#eef5ff]">
                Manage your account
              </h2>

              <p className="mt-2 mb-0 text-[13px] leading-[1.6] text-[#8fa0b8]">
                Choose a section to view or update its settings.
              </p>
            </div>

            <nav aria-label="Settings sections">
              <SettingsRow
                icon="♙"
                title="Profile"
                description={session.email}
              />

              <SettingsRow
                icon="♢"
                title="Notifications"
                description="Email alerts for strong upgrade recommendations"
                onClick={openNotifications}
              />

              <SettingsRow
                icon="◷"
                title="Activity"
                description="Account and recommendation history"
              />
            </nav>
          </Card>
        </div>
      ) : (
        <div className="w-full">
          <button
            type="button"
            aria-label="Back to account settings"
            onClick={closeNotifications}
            className="mb-4 inline-flex items-center gap-2 rounded-[10px] px-2 py-2 text-[13px] font-semibold text-[#a995ff] transition-colors hover:bg-[#7c5cff]/10 hover:text-[#c7bbff]"
          >
            <span aria-hidden="true">←</span>
            Account settings
          </button>

          <Card className="overflow-hidden shadow-[0_18px_50px_rgba(0,0,0,.22)]">
            <div className="border-b border-white/[0.08] px-6 py-5">
              <p className="m-0 text-[12px] font-bold tracking-[0.13em] text-[#8fa0b8] uppercase">
                Notifications
              </p>

              <h2 className="mt-2 mb-0 text-[21px] font-bold text-[#eef5ff]">
                Email notifications
              </h2>

              <p className="mt-2 mb-0 text-[13px] leading-[1.6] text-[#8fa0b8]">
                Control when Tech Advisor contacts you about upgrade
                recommendations.
              </p>
            </div>

            <div className="flex items-center justify-between gap-6 px-6 py-7 max-[620px]:items-start">
              <div>
                <h3 className="m-0 text-[16px] font-semibold text-[#eef5ff]">
                  Strong upgrade recommendations
                </h3>

                <p className="mt-2 mb-0 max-w-[680px] text-[14px] leading-[1.6] text-[#9cabc0]">
                  Receive an email when Tech Advisor finds a strong upgrade
                  candidate supported by high-quality owner evidence.
                </p>
              </div>

              <input
                type="checkbox"
                className="toggle toggle-primary mt-1 shrink-0"
                aria-label="Recommendation email notifications"
                checked={emailEnabled}
                disabled={saving}
                onChange={() => void handleEmailToggle()}
              />
            </div>

            <div className="min-h-[45px] border-t border-white/[0.08] px-6 py-3 text-[13px]">
              {saving && (
                <span className="text-[#8fa0b8]">
                  Saving preference…
                </span>
              )}

              {!saving && savedMessage && (
                <span
                  role="status"
                  className={
                    emailEnabled
                      ? "text-[#83e4c1]"
                      : "text-[#ff7f8c]"
                  }
                >
                  {savedMessage}
                </span>
              )}

              {!saving && error && (
                <span role="alert" className="text-[#ff9ca7]">
                  {error}
                </span>
              )}
            </div>
          </Card>
        </div>
      )}
    </AppShell>
  );
}