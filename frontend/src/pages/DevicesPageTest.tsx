import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { signIn, signOut } from "../api/auth";
import { ApiError } from "../api/client";
import { createDevice, listDevices } from "../api/devices";
import { getSession } from "../api/session";
import type { Session } from "../api/session";
import { SignInModal } from "../components/auth/SignInModal";
import { AppShell } from "../components/layout/AppShell";
import { NotificationButton } from "../components/layout/NotificationButton";
import { Button } from "../components/ui/Button";
import { Callout } from "../components/ui/Callout";
import { LoadingBlock } from "../components/ui/LoadingBlock";
import { PageHeader } from "../components/ui/PageHeader";
import { ToastStack } from "../components/ui/ToastStack";
import { useToasts } from "../components/ui/useToasts";
import { DeviceFormModal } from "../components/devices/DeviceFormModal";
import { DeviceGrid } from "../components/devices/DeviceGrid";
import { UpgradePreferencesModal } from "../components/devices/UpgradePreferencesModal";
import { deviceFromApi, deviceToRequest } from "../components/devices/deviceApi";
import { DEMO_DEVICES, DEMO_USER } from "../components/devices/demoData";
import type { Device } from "../components/devices/types";

/**
 * My Devices — remake of the prototype's "My devices" page.
 *
 * Signed out: local demo devices. Signed in: devices load from
 * `GET /api/devices` and new devices are saved with `POST /api/devices`.
 * Editing, removing and upgrade preferences are still local-only.
 */
export default function DevicesPageTest() {
  const navigate = useNavigate();
  const [account, setAccount] = useState<Session | null>(getSession);
  const [devices, setDevices] = useState<Device[]>(account ? [] : DEMO_DEVICES);
  const [loading, setLoading] = useState(account !== null);
  const [signingIn, setSigningIn] = useState(false);
  const [editing, setEditing] = useState<Device | "new" | null>(null);
  const [prefs, setPrefs] = useState<{ id: string; isNew: boolean } | null>(
    null
  );
  const { toasts, pushToast } = useToasts();

  const name = (d: Device) => `${d.brand} ${d.model}`.trim();
  const replace = (next: Device) =>
    setDevices((all) => all.map((d) => (d.id === next.id ? next : d)));
  const prefsDevice = prefs && devices.find((d) => d.id === prefs.id);
  const localOnly = account ? " (this page only — not saved to your account yet)" : "";

  useEffect(() => {
    if (!account) return;
    let cancelled = false;
    listDevices()
      .then((rows) => {
        if (!cancelled) setDevices(rows.map((row) => deviceFromApi(row)));
      })
      .catch((err: Error) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 401) {
          setAccount(null);
          setDevices(DEMO_DEVICES);
        }
        pushToast("Couldn't load your devices", err.message, "!");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [account, pushToast]);

  const handleSignIn = async (email: string, password: string) => {
    const session = await signIn(email, password);
    setSigningIn(false);
    setDevices([]);
    setLoading(true);
    setAccount(session);
    pushToast("Signed in", `Devices you add are now saved to ${session.email}.`);
  };

  const handleSignOut = () => {
    signOut();
    navigate("/login");
  };

  /** Throws on API failure so `DeviceFormModal` stays open and shows it. */
  const saveDevice = async (draft: Device, isNew: boolean) => {
    if (!isNew) {
      replace(draft);
      setEditing(null);
      pushToast("Device updated", `${name(draft)} was updated${localOnly}.`);
      return;
    }

    let device = draft;
    if (account) {
      const saved = await createDevice(deviceToRequest(draft));
      device = {
        ...deviceFromApi(saved, {
          preferences: draft.upgradePreferences,
          primary: draft.primary,
        }),
        type: draft.type, // not stored by the backend; keep it for this session
      };
      pushToast("Device saved", `${name(device)} was added to your account.`);
    }
    setEditing(null);
    setDevices((all) => [device, ...all]);
    setPrefs({ id: device.id, isNew: true }); // straight into step 2
  };

  const removeDevice = (device: Device) => {
    if (!window.confirm(`Remove ${name(device)}?`)) return;
    setDevices((all) => all.filter((d) => d.id !== device.id));
    pushToast(
      "Device removed",
      `It will no longer be considered in recommendations${localOnly}.`,
      "−"
    );
  };

  return (
    <AppShell
      active="devices"
      title="My devices"
      user={
        account
          ? { name: account.email.split("@")[0], email: account.email }
          : DEMO_USER
      }
      topbarActions={<NotificationButton unread={1} />}
    >
      <PageHeader
        eyebrow="Device-specific context"
        title="My devices"
        description="Each owned device has its own upgrade budget, priorities, pain points and urgency."
        action={
          <Button variant="primary" onClick={() => setEditing("new")}>
            ＋ Add device
          </Button>
        }
      />

      <Callout className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <span>
          {account ? (
            <>
              Signed in as <b>{account.email}</b>. New devices are saved to
              your account.
            </>
          ) : (
            "Demo mode: these devices aren't saved. Sign in to add devices to your account."
          )}
        </span>
        {account ? (
          <Button size="sm" variant="ghost" onClick={handleSignOut}>
            Sign out
          </Button>
        ) : (
          <Button size="sm" variant="primary" onClick={() => setSigningIn(true)}>
            Sign in
          </Button>
        )}
      </Callout>

      {loading ? (
        <LoadingBlock label="Loading your devices…" />
      ) : (
        <DeviceGrid
          devices={devices}
          onAdd={() => setEditing("new")}
          onEdit={setEditing}
          onEditPreferences={(d) => setPrefs({ id: d.id, isNew: false })}
          onRemove={removeDevice}
        />
      )}

      {signingIn && (
        <SignInModal
          onClose={() => setSigningIn(false)}
          onSignIn={handleSignIn}
        />
      )}

      {editing && (
        <DeviceFormModal
          device={editing === "new" ? null : editing}
          isFirst={devices.length === 0}
          onClose={() => setEditing(null)}
          onSave={saveDevice}
        />
      )}

      {prefs && prefsDevice && (
        <UpgradePreferencesModal
          device={prefsDevice}
          isNew={prefs.isNew}
          onClose={() => setPrefs(null)}
          onSave={(upgradePreferences) => {
            replace({ ...prefsDevice, upgradePreferences });
            setPrefs(null);
            pushToast(
              "Upgrade profile saved",
              `These preferences now apply only to ${name(prefsDevice)}${localOnly}.`
            );
          }}
        />
      )}

      <ToastStack toasts={toasts} />
    </AppShell>
  );
}
