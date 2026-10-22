import { useState } from "react";
import type { ReactNode } from "react";
import { Button } from "../ui/Button";
import { Field, FormError, FormGrid, Select, Textarea } from "../ui/Form";
import { Modal, ModalActions } from "../ui/Modal";
import { BudgetSlider } from "./BudgetSlider";
import { PriorityPicker } from "./PriorityPicker";
import {
  BRAND_FLEX,
  SUGGESTED_BUDGET,
  URGENCIES,
  defaultPrefs,
} from "./deviceOptions";
import type { Device, UpgradePreferences } from "./types";

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="mb-5">
      <h4 className="mb-[10px] text-[16px] font-bold">{title}</h4>
      {children}
    </div>
  );
}

/**
 * Edit one device's upgrade profile. `isNew` is the "Step 2" variant shown
 * straight after adding a device (Cancel becomes "Skip for now").
 *
 * A device with no saved budget starts the slider at `SUGGESTED_BUDGET`;
 * saving always saves a budget. `onSave` may return a promise: a rejection
 * keeps the modal open and shows the error.
 */
export function UpgradePreferencesModal({
  device,
  isNew = false,
  onClose,
  onSave,
}: {
  device: Device;
  isNew?: boolean;
  onClose: () => void;
  onSave: (prefs: UpgradePreferences) => void | Promise<void>;
}) {
  const [prefs, setPrefs] = useState<UpgradePreferences>({
    ...defaultPrefs(),
    ...device.upgradePreferences,
    budget: device.upgradePreferences?.budget ?? SUGGESTED_BUDGET,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const save = async () => {
    if (!prefs.urgency || !prefs.brandFlex) {
      setError("Choose upgrade urgency and brand flexibility before saving.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await onSave(prefs);
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Could not save the upgrade profile."
      );
    } finally {
      setSaving(false);
    }
  };
  const set = <K extends keyof UpgradePreferences>(
    key: K,
    value: UpgradePreferences[K]
  ) => setPrefs((current) => ({ ...current, [key]: value }));

  return (
    <Modal
      eyebrow={`${isNew ? "Step 2 · " : ""}${device.type} upgrade profile`}
      title={`${device.brand} ${device.model}`}
      onClose={onClose}
    >
      <p className="mt-0 mb-4 text-[13px] leading-[1.6] text-[#8fa0b8]">
        These preferences apply only when Tech Advisor evaluates replacing{" "}
        <b className="text-[#eef5ff]">
          {device.brand} {device.model}
        </b>
        .
      </p>

      <Section title="Maximum budget for replacing this device">
        <BudgetSlider
          value={prefs.budget ?? SUGGESTED_BUDGET}
          currency={prefs.currency}
          onChange={(v) => set("budget", v)}
        />
      </Section>

      <Section title="What matters most for the replacement?">
        <PriorityPicker
          selected={prefs.priorities}
          onChange={(v) => set("priorities", v)}
        />
      </Section>

      <FormGrid>
        <Field label="Upgrade urgency">
          <Select
            options={URGENCIES}
            value={prefs.urgency}
            onChange={(e) => set("urgency", e.target.value)}
          />
        </Field>
        <Field label="Brand flexibility for this replacement">
          <Select
            options={BRAND_FLEX}
            value={prefs.brandFlex}
            onChange={(e) => set("brandFlex", e.target.value)}
          />
        </Field>
        <Field label="Current pain points" span2>
          <Textarea
            value={prefs.painPoints}
            onChange={(e) => set("painPoints", e.target.value)}
            placeholder="e.g. Battery drains too quickly; compile times are slow; display is too small."
          />
        </Field>
        <Field label="Anything else for this device?" span2>
          <Textarea
            value={prefs.notes}
            onChange={(e) => set("notes", e.target.value)}
            placeholder="e.g. Avoid launch-day pricing. I want at least four years of use."
          />
        </Field>
      </FormGrid>

      <FormError message={error} />

      <ModalActions>
        <Button variant="ghost" onClick={onClose}>
          {isNew ? "Skip for now" : "Cancel"}
        </Button>
        <Button variant="primary" loading={saving} onClick={() => void save()}>
          Save upgrade profile
        </Button>
      </ModalActions>
    </Modal>
  );
}
