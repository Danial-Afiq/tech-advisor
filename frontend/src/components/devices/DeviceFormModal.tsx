import { useState } from "react";
import { Button } from "../ui/Button";
import {
  Field,
  FormError,
  FormGrid,
  Input,
  Range,
  Select,
  Textarea,
} from "../ui/Form";
import { Modal, ModalActions } from "../ui/Modal";
import { CONDITIONS, DEVICE_TYPES, defaultPrefs } from "./deviceOptions";
import type { Condition, Device } from "./types";

/**
 * Add / edit device details. Pass `device={null}` to add a new one; the saved
 * device then gets default upgrade preferences and `primary = isFirst`.
 */
export function DeviceFormModal({
  device,
  isFirst = false,
  onClose,
  onSave,
}: {
  device: Device | null;
  isFirst?: boolean;
  onClose: () => void;
  /** May return a promise (e.g. an API call); a rejection is shown in the form. */
  onSave: (device: Device, isNew: boolean) => void | Promise<void>;
}) {
  const isNew = device === null;
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [type, setType] = useState(device?.type ?? "Laptop");
  const [condition, setCondition] = useState<Condition>(
    device?.condition ?? "Good"
  );
  const [brand, setBrand] = useState(device?.brand ?? "");
  const [model, setModel] = useState(device?.model ?? "");
  const [purchaseDate, setPurchaseDate] = useState(device?.purchaseDate ?? "");
  const [satisfaction, setSatisfaction] = useState(device?.satisfaction ?? 75);
  const [use, setUse] = useState(device?.use ?? "");

  const submit = async (event: { preventDefault: () => void }) => {
    event.preventDefault();
    setError(null);
    setSaving(true);
    try {
      await onSave(
        {
          // Keep fields this form doesn't edit (productId, specs, …).
          ...device,
          id: device?.id ?? Math.random().toString(36).slice(2, 9),
          type,
          condition,
          brand: brand.trim(),
          model: model.trim(),
          purchaseDate,
          satisfaction,
          use,
          // A changed model invalidates the stored product image.
          image:
            device && device.model === model && device.type === type
              ? device.image
              : undefined,
          primary: device?.primary ?? isFirst,
          upgradePreferences: device?.upgradePreferences ?? defaultPrefs(),
        },
        isNew
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not save the device.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      eyebrow={isNew ? "Add device" : "Edit device"}
      title={isNew ? "What are you using today?" : "Update device details"}
      onClose={onClose}
    >
      <form onSubmit={submit}>
        <FormGrid>
          <Field label="Device type">
            <Select
              options={DEVICE_TYPES}
              value={type}
              onChange={(e) => setType(e.target.value)}
            />
          </Field>
          <Field label="Condition">
            <Select
              options={CONDITIONS}
              value={condition}
              onChange={(e) => setCondition(e.target.value as Condition)}
            />
          </Field>
          <Field label="Brand">
            <Input
              required
              value={brand}
              onChange={(e) => setBrand(e.target.value)}
              placeholder="e.g. Apple, ASUS, Dell"
            />
          </Field>
          <Field label="Model / configuration">
            <Input
              required
              value={model}
              onChange={(e) => setModel(e.target.value)}
              placeholder="e.g. iPhone 13 Pro Max, Zephyrus G14"
            />
          </Field>
          <Field label="Purchase date">
            <Input
              type="date"
              value={purchaseDate}
              onChange={(e) => setPurchaseDate(e.target.value)}
            />
          </Field>
          <Field label={`Current satisfaction (${satisfaction}%)`}>
            <Range
              min={0}
              max={100}
              value={satisfaction}
              onChange={(e) => setSatisfaction(Number(e.target.value))}
              className="mt-3"
            />
          </Field>
          <Field label="Main usage" span2>
            <Textarea
              value={use}
              onChange={(e) => setUse(e.target.value)}
              placeholder="What do you mainly use this device for?"
            />
          </Field>
        </FormGrid>

        <FormError message={error} />
        <ModalActions>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={saving}>
            {isNew ? "Continue to upgrade preferences →" : "Save device"}
          </Button>
        </ModalActions>
      </form>
    </Modal>
  );
}
