import { useId, useMemo, useState } from "react";
import type { SmartphoneCatalogueItem } from "../../api/catalogue";
import { Button } from "../ui/Button";
import { Callout } from "../ui/Callout";
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
import { CatalogueSearchInput } from "./CatalogueSearchInput";
import {
  catalogueSpecs,
  diffSpecs,
  formToSpecs,
  matchCatalogue,
  specsToForm,
} from "./catalogue";
import { CONDITIONS, DEVICE_TYPES, defaultPrefs } from "./deviceOptions";
import { PhoneSpecFields } from "./PhoneSpecFields";
import type { PhoneSpecs } from "./phoneSpecs";
import type { Condition, Device } from "./types";
import type { SmartphoneCatalogue } from "./useSmartphoneCatalogue";

/**
 * Add / edit device details. Pass `device={null}` to add a new one; the saved
 * device then gets default upgrade preferences and `primary = isFirst`.
 *
 * With a `catalogue`, typing a model name suggests matching catalogue
 * devices. Picking one links its `productId` and prefills the specs; any spec
 * the user then changes is saved in `specOverrides`. A name that isn't in the
 * catalogue is plain manual entry.
 */
export function DeviceFormModal({
  device,
  isFirst = false,
  catalogue,
  onClose,
  onSave,
}: {
  device: Device | null;
  isFirst?: boolean;
  /** Smartphone catalogue for suggestions; omit for manual entry only. */
  catalogue?: SmartphoneCatalogue;
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
  const [linkedId, setLinkedId] = useState(device?.productId ?? null);
  const [specValues, setSpecValues] = useState(() =>
    specsToForm(device?.specs)
  );
  const modelId = useId();

  // The catalogue row the spec fields were filled from. Fixed when the form
  // opens or a suggestion is picked: if the catalogue finishes loading while
  // the form is open, the fields still hold the saved specs, and comparing
  // them to the late row would read every untouched value as cleared.
  const [linkedItem, setLinkedItem] = useState(() =>
    catalogue?.items.find((item) => item.id === device?.productId)
  );

  const items = useMemo(() => catalogue?.items ?? [], [catalogue]);
  const isPhone = type === "Phone";
  const suggestions = useMemo(
    () => (linkedId === null ? matchCatalogue(items, model, brand) : []),
    [items, linkedId, model, brand]
  );

  /** What the spec fields are compared against: the catalogue row, else the saved specs. */
  const baselineSpecs: PhoneSpecs | undefined = useMemo(() => {
    if (linkedId === null) return undefined;
    return linkedItem ? catalogueSpecs(linkedItem) : (device?.specs ?? {});
  }, [linkedId, linkedItem, device]);
  const baselineForm = useMemo(
    () => (baselineSpecs ? specsToForm(baselineSpecs) : undefined),
    [baselineSpecs]
  );

  // Changing the name or type of a linked device turns it into a manual
  // entry. The prefilled specs stay so nothing the user reviewed is lost.
  const unlink = () => setLinkedId(null);

  const pick = (item: SmartphoneCatalogueItem) => {
    setLinkedId(item.id);
    setLinkedItem(item);
    setBrand(item.brand);
    setModel(item.modelName);
    setType("Phone");
    setSpecValues(specsToForm(catalogueSpecs(item)));
  };

  const modelHint = (() => {
    if (!catalogue || linkedId !== null || model.trim().length < 2) return null;
    if (catalogue.status === "loading") return "Loading the product catalogue…";
    if (catalogue.status === "unavailable") {
      return "Catalogue suggestions aren't available right now. Enter the details yourself.";
    }
    if (suggestions.length === 0) {
      return "Not in the product catalogue. Enter the details yourself.";
    }
    return null;
  })();

  /** The specs to show, and the part of them to save as overrides. */
  const savedSpecs = (): Pick<Device, "specs" | "specOverrides"> => {
    if (!isPhone) {
      return device?.type === "Phone"
        ? { specs: undefined, specOverrides: undefined }
        : { specs: device?.specs, specOverrides: device?.specOverrides };
    }
    const entered = formToSpecs(specValues);
    if (!baselineSpecs) return { specs: entered, specOverrides: entered };
    // Without the catalogue row to compare against, keep the earlier overrides.
    const specOverrides = {
      ...(linkedItem ? {} : device?.specOverrides),
      ...diffSpecs(entered, baselineSpecs),
    };
    return { specs: { ...baselineSpecs, ...specOverrides }, specOverrides };
  };

  const submit = async (event: { preventDefault: () => void }) => {
    event.preventDefault();
    setError(null);
    setSaving(true);
    try {
      await onSave(
        {
          // Keep fields this form doesn't edit (image, preferences, …).
          ...device,
          id: device?.id ?? crypto.randomUUID(),
          productId: linkedId,
          type,
          condition,
          brand: brand.trim(),
          model: model.trim(),
          purchaseDate,
          satisfaction,
          use,
          ...savedSpecs(),
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
              onChange={(e) => {
                setType(e.target.value);
                if (e.target.value !== "Phone") unlink();
              }}
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
              // A saved device without a catalogue link comes back with its
              // whole name in the model field, so editing it needs no brand.
              required={isNew}
              value={brand}
              onChange={(e) => {
                setBrand(e.target.value);
                unlink();
              }}
              placeholder="e.g. Apple, ASUS, Dell"
            />
          </Field>
          <Field label="Model / configuration" htmlFor={modelId}>
            <CatalogueSearchInput
              id={modelId}
              required
              value={model}
              onChange={(value) => {
                setModel(value);
                unlink();
              }}
              suggestions={suggestions}
              onPick={pick}
              hint={modelHint}
              placeholder={
                catalogue
                  ? "Start typing to search the catalogue"
                  : "e.g. iPhone 13 Pro Max, Zephyrus G14"
              }
            />
          </Field>
          {linkedId !== null && (
            <div className="col-span-2 mb-[14px] max-[620px]:col-auto">
              <Callout className="flex flex-wrap items-center justify-between gap-3">
                <span>
                  ✦ Matched to <b>{`${brand} ${model}`.trim()}</b>{" "}
                  in the product catalogue.
                  {isPhone && " Review the prefilled specifications below."}
                </span>
                <Button size="sm" variant="ghost" onClick={unlink}>
                  Enter manually instead
                </Button>
              </Callout>
            </div>
          )}
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

        {isPhone && (
          <PhoneSpecFields
            values={specValues}
            onChange={(key, value) =>
              setSpecValues((all) => ({ ...all, [key]: value }))
            }
            baseline={baselineForm}
          />
        )}

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
