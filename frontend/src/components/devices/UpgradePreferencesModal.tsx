import { useState } from "react";
import type { ReactNode } from "react";
import { Button } from "../ui/Button";
import { Field, FormGrid, Select, Textarea } from "../ui/Form";
import { Modal, ModalActions } from "../ui/Modal";
import { BudgetSlider } from "./BudgetSlider";
import { PriorityPicker } from "./PriorityPicker";
import { BRAND_FLEX, URGENCIES, defaultPrefs } from "./deviceOptions";
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
  onSave: (prefs: UpgradePreferences) => void;
}) {
  const [prefs, setPrefs] = useState<UpgradePreferences>({
    ...defaultPrefs(),
    ...device.upgradePreferences,
  });
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
        <BudgetSlider value={prefs.budget} onChange={(v) => set("budget", v)} />
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

      <ModalActions>
        <Button variant="ghost" onClick={onClose}>
          {isNew ? "Skip for now" : "Cancel"}
        </Button>
        <Button variant="primary" onClick={() => onSave(prefs)}>
          Save upgrade profile
        </Button>
      </ModalActions>
    </Modal>
  );
}
