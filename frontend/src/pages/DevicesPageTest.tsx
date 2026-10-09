import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { signIn, signOut } from "../api/auth";
import { ApiError } from "../api/client";
import { createDevice, listDevices, updateDevice } from "../api/devices";
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
import { withCatalogue } from "../components/devices/catalogue";
import { deviceFromApi, deviceToRequest } from "../components/devices/deviceApi";
import type { DeviceResponse } from "../components/devices/deviceApi";
import { DEMO_DEVICES, DEMO_USER } from "../components/devices/demoData";
import type { Device } from "../components/devices/types";
import { useSmartphoneCatalogue } from "../components/devices/useSmartphoneCatalogue";

/**
 * My Devices — remake of the prototype's "My devices" page.
 *
 * Signed out: local demo devices. Signed in: devices load from
 * `GET /api/devices`, new devices are saved with `POST /api/devices` and
 * edits with `PUT /api/devices/{id}`. Removing and upgrade preferences are
 * still local-only. Signed in,
 * the device form suggests smartphones from `GET /api/catalogue/smartphones`
 * and prefills their specs.
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
  const catalogue = useSmartphoneCatalogue(account !== null);
  // Linked devices get their specs from the catalogue list.
  const shownDevices = useMemo(() => {
    const byId = new Map(catalogue.items.map((item) => [item.id, item]));
    return devices.map((d) =>
      d.productId ? withCatalogue(d, byId.get(d.productId)) : d
    );
  }, [devices, catalogue.items]);

  const name = (d: Device) => `${d.brand} ${d.model}`.trim();
  const replace = (next: Device) =>
    setDevices((all) => all.map((d) => (d.id === next.id ? next : d)));
  const prefsDevice = prefs && shownDevices.find((d) => d.id === prefs.id);
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
          void navigate("/login", { replace: true });
          return;
        }
        pushToast("Couldn't load your devices", err.message, "!");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [account, navigate, pushToast]);

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
    void navigate("/login", { replace: true });
  };

  /** Throws on API failure so `DeviceFormModal` stays open and shows it. */
  const saveDevice = async (draft: Device, isNew: boolean) => {
    // The saved row, plus what the backend doesn't store yet.
    const fromSaved = (saved: DeviceResponse): Device => ({
      ...deviceFromApi(saved, {
        preferences: draft.upgradePreferences,
        primary: draft.primary,
        image: draft.image,
      }),
      type: draft.type, // not stored by the backend; keep it for this session
    });

    if (!isNew) {
      const device = account
        ? fromSaved(await updateDevice(draft.id, deviceToRequest(draft)))
        : draft;
      replace(device);
      setEditing(null);
      pushToast(
        "Device updated",
        `${name(device)} was updated${account ? " in your account" : ""}.`
      );
      return;
    }

    let device = draft;
    if (account) {
      device = fromSaved(await createDevice(deviceToRequest(draft)));
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
      onSignOut={account ? handleSignOut : undefined}
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
              Signed in as <b>{account.email}</b>. Devices you add or edit are saved to
              your account.
            </>
          ) : (
            "Demo mode: these devices aren't saved. Sign in to add devices to your account."
          )}
        </span>
        {!account && (
          <Button size="sm" variant="primary" onClick={() => setSigningIn(true)}>
            Sign in
          </Button>
        )}
      </Callout>

      {loading ? (
        <LoadingBlock label="Loading your devices…" />
      ) : (
        <DeviceGrid
          devices={shownDevices}
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
          catalogue={account ? catalogue : undefined}
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
