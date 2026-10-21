import { useState } from "react";
import type { FormEvent } from "react";
import type { SmartphoneCatalogueItem } from "../../api/catalogue";
import { PhoneSpecFields } from "../devices/PhoneSpecFields";
import { Button } from "../ui/Button";
import { Field, FormError, FormGrid, Input, Select } from "../ui/Form";
import { Modal, ModalActions } from "../ui/Modal";
import { CatalogueHistorySection } from "./CatalogueHistorySection";
import { CATALOGUE_STATUSES, itemToForm } from "./catalogueAdmin";
import type { CatalogueFormValues } from "./catalogueAdmin";

/**
 * Add or edit one catalogue smartphone: the `products` fields plus every
 * `phone` spec (reusing the device form's `PhoneSpecFields`). An emptied
 * spec is saved as null, i.e. unknown. Editing also shows the entry's
 * read-only price and benchmark history.
 */
export function CatalogueEntryModal({
  item,
  onClose,
  onSave,
}: {
  /** The entry being edited; omit to add a new one. */
  item?: SmartphoneCatalogueItem;
  onClose: () => void;
  /** Throws to keep the modal open and show the message. */
  onSave: (values: CatalogueFormValues) => Promise<void>;
}) {
  const [values, setValues] = useState(() => itemToForm(item));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const set = (patch: Partial<CatalogueFormValues>) =>
    setValues((current) => ({ ...current, ...patch }));

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!values.brand.trim() || !values.modelName.trim()) {
      setError("Brand and model are required.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await onSave(values);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not save this entry.");
      setSaving(false);
    }
  };

  return (
    <Modal
      eyebrow={item ? `Catalogue entry #${item.id}` : "New catalogue entry"}
      title={item ? `Edit ${item.brand} ${item.modelName}` : "Add smartphone"}
      width="max-w-[760px]"
      onClose={onClose}
    >
      <form onSubmit={(e) => void handleSubmit(e)} noValidate>
        <FormGrid>
          <Field label="Brand">
            <Input
              value={values.brand}
              required
              maxLength={100}
              onChange={(e) => set({ brand: e.target.value })}
            />
          </Field>
          <Field label="Model">
            <Input
              value={values.modelName}
              required
              maxLength={200}
              onChange={(e) => set({ modelName: e.target.value })}
            />
          </Field>
          <Field label="Release date">
            <Input
              type="date"
              value={values.releaseDate}
              onChange={(e) => set({ releaseDate: e.target.value })}
            />
          </Field>
          <Field label="Status">
            <Select
              value={values.status}
              options={CATALOGUE_STATUSES}
              onChange={(e) => set({ status: e.target.value })}
            />
          </Field>
        </FormGrid>

        <PhoneSpecFields
          values={values.specs}
          onChange={(key, value) => set({ specs: { ...values.specs, [key]: value } })}
        />

        {item && <CatalogueHistorySection productId={item.id} />}

        <FormError message={error} />
        <ModalActions>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={saving}>
            {item ? "Save changes" : "Add smartphone"}
          </Button>
        </ModalActions>
      </form>
    </Modal>
  );
}
