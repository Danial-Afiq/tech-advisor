import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { signOut } from "../api/auth";
import { ApiError } from "../api/client";
import {
  createDevice,
  deleteDevice,
  listDevices,
  updateDevice,
} from "../api/devices";
import { getSession } from "../api/session";
import { AppShell } from "../components/layout/AppShell";
import { NotificationCenter } from "../components/notifications/NotificationCenter";
import { Button } from "../components/ui/Button";
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
import type { Device, UpgradePreferences } from "../components/devices/types";
import { useSmartphoneCatalogue } from "../components/devices/useSmartphoneCatalogue";
import {
  DASHBOARD_PATH,
  DEVICES_PATH,
  SETTINGS_PATH
} from "../routing/paths";

/**
 * My Devices — remake of the prototype's "My devices" page.
 *
 * The route requires a USER session. Devices load from `GET /api/devices`, new
 * devices are saved with `POST /api/devices`, edits with `PUT /api/devices/{id}`
 * and removals with `DELETE /api/devices/{id}`. Saving upgrade preferences
 * persists every field with `PUT /api/devices/{id}`. The device form suggests
 * smartphones from `GET /api/catalogue/smartphones` and prefills their specs.
 */
export default function DevicesPageTest() {
  const navigate = useNavigate();
  const session = getSession();
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<Device | "new" | null>(null);
  const [prefs, setPrefs] = useState<{ id: string; isNew: boolean } | null>(
    null
  );
  const { toasts, pushToast } = useToasts();
  const catalogue = useSmartphoneCatalogue(true);
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

  useEffect(() => {
    let cancelled = false;
    listDevices()
      .then((rows) => {
        if (!cancelled) setDevices(rows.map((row) => deviceFromApi(row)));
      })
      .catch((err: Error) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 401) {
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
  }, [navigate, pushToast]);

  const handleSignOut = () => {
    signOut();
    void navigate("/login", { replace: true });
  };

  // The saved row, plus what the backend doesn't store yet.
  const fromSaved = (draft: Device, saved: DeviceResponse): Device => ({
    ...deviceFromApi(saved, {
      primary: draft.primary,
      image: draft.image,
    }),
    type: draft.type, // not stored by the backend; keep it for this session
  });

  /** Throws on API failure so `DeviceFormModal` stays open and shows it. */
  const saveDevice = async (draft: Device, isNew: boolean) => {
    if (!isNew) {
      const device = fromSaved(draft, await updateDevice(draft.id, deviceToRequest(draft)));
      replace(device);
      setEditing(null);
      pushToast("Device updated", `${name(device)} was updated in your account.`);
      return;
    }

    const device = fromSaved(draft, await createDevice(deviceToRequest(draft)));
    pushToast("Device saved", `${name(device)} was added to your account.`);
    setEditing(null);
    setDevices((all) => [device, ...all]);
    setPrefs({ id: device.id, isNew: true }); // straight into step 2
  };

  /**
   * Signed in, all preferences are saved with the rest of the device
   * (`PUT /api/devices/{id}`), which also re-runs its recommendations. The
   * response supplies the saved preferences. Throws on API failure so
   * `UpgradePreferencesModal` stays open and shows it.
   */
  const savePreferences = async (
    device: Device,
    upgradePreferences: UpgradePreferences
  ) => {
    const draft = { ...device, upgradePreferences };
    const next = fromSaved(draft, await updateDevice(draft.id, deviceToRequest(draft)));
    replace(next);
    setPrefs(null);
    pushToast(
      "Upgrade profile saved",
      `The upgrade preferences for ${name(next)} were saved to your account.`
    );
  };

  const removeDevice = async (device: Device) => {
    if (!window.confirm(`Remove ${name(device)}?`)) return;
    try {
      await deleteDevice(device.id);
    } catch (err) {
      // 404: already gone from the account, so dropping it here is right.
      if (!(err instanceof ApiError && err.status === 404)) {
        const message = err instanceof Error ? err.message : "Please try again.";
        pushToast(`Couldn't remove ${name(device)}`, message, "!");
        return;
      }
    }
    setDevices((all) => all.filter((d) => d.id !== device.id));
    pushToast(
      "Device removed",
      "It will no longer be considered in recommendations.",
      "−"
    );
  };

  if (!session) return null;

  return (
    <AppShell
      active="devices"
      title="My devices"
      onNavigate={(key) => {
        if (key === "dashboard") {
          void navigate(DASHBOARD_PATH);
        }

        if (key === "devices") {
          void navigate(DEVICES_PATH);
        }

        if (key === "settings") {
          void navigate(SETTINGS_PATH);
        }
      }}
      user={{ name: session.email.split("@")[0], email: session.email }}
      topbarActions={<NotificationCenter />}
      onSignOut={handleSignOut}
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

      {loading ? (
        <LoadingBlock label="Loading your devices…" />
      ) : (
        <DeviceGrid
          devices={shownDevices}
          onAdd={() => setEditing("new")}
          onEdit={setEditing}
          onEditPreferences={(d) => setPrefs({ id: d.id, isNew: false })}
          onRemove={(d) => void removeDevice(d)}
        />
      )}

      {editing && (
        <DeviceFormModal
          device={editing === "new" ? null : editing}
          isFirst={devices.length === 0}
          catalogue={catalogue}
          onClose={() => setEditing(null)}
          onSave={saveDevice}
        />
      )}

      {prefs && prefsDevice && (
        <UpgradePreferencesModal
          device={prefsDevice}
          isNew={prefs.isNew}
          onClose={() => setPrefs(null)}
          onSave={(upgradePreferences) =>
            savePreferences(prefsDevice, upgradePreferences)
          }
        />
      )}

      <ToastStack toasts={toasts} />
    </AppShell>
  );
}
