import { useState } from "react";
import type { FormEvent } from "react";
import { PhoneSpecFields } from "../devices/PhoneSpecFields";
import { Button } from "../ui/Button";
import { Field, FormError, FormGrid, Input, Select } from "../ui/Form";
import { Modal, ModalActions } from "../ui/Modal";
import type { SpecFormValues } from "../devices/catalogue";
import { CatalogueHistorySection } from "./CatalogueHistorySection";
import { CatalogueObservationFields } from "./CatalogueObservationFields";
import { CATALOGUE_STATUSES, observationError } from "./catalogueAdmin";
import type { CatalogueFormValues, CatalogueItemBase, CatalogueKind, GpuSpecFormValues } from "./catalogueAdmin";
import { GpuSpecFields } from "./GpuSpecFields";

/**
 * Add or edit one catalogue entry of the given kind: the `products` fields
 * plus every spec (`PhoneSpecFields` for smartphones, `GpuSpecFields` for
 * GPUs). An emptied spec is saved as null, i.e. unknown. Adding an entry can
 * also record its first prices (and, for a smartphone, benchmarks); editing a
 * smartphone shows its read-only price and benchmark history instead.
 */
export function CatalogueEntryModal({
  kind,
  item,
  onClose,
  onSave,
}: {
  kind: CatalogueKind<CatalogueItemBase>;
  /** The entry being edited; omit to add a new one. */
  item?: CatalogueItemBase & { source?: string | null; fetchedAt?: string | null };
  onClose: () => void;
  /** Throws to keep the modal open and show the message. */
  onSave: (values: CatalogueFormValues) => Promise<void>;
}) {
  const [values, setValues] = useState(() => kind.toForm(item));
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
    const observationProblem = item ? null : observationError(values);
    if (observationProblem) {
      setError(observationProblem);
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
      title={item ? `Edit ${item.brand} ${item.modelName}` : `Add ${kind.noun}`}
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

        {kind.key === "gpus" ? (
          <GpuSpecFields
            values={values.specs as GpuSpecFormValues}
            onChange={(key, value) => set({ specs: { ...values.specs, [key]: value } })}
            provenance={item?.source && `${item.source}${item.fetchedAt ? `, ${item.fetchedAt.slice(0, 10)}` : ""}`}
          />
        ) : (
          <PhoneSpecFields
            values={values.specs as SpecFormValues}
            onChange={(key, value) => set({ specs: { ...values.specs, [key]: value } })}
          />
        )}

        {item && kind.hasHistory && <CatalogueHistorySection productId={item.id} />}

        {!item && (
          <CatalogueObservationFields
            prices={values.prices}
            benchmarks={values.benchmarks}
            priceVariants={kind.priceVariants}
            benchmarkEntry={kind.benchmarkEntry}
            onChange={set}
          />
        )}

        <FormError message={error} />
        <ModalActions>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={saving}>
            {item ? "Save changes" : `Add ${kind.noun}`}
          </Button>
        </ModalActions>
      </form>
    </Modal>
  );
}
