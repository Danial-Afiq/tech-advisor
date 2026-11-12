import { useId, useMemo, useState } from "react";
import { Button } from "../ui/Button";
import { Checkbox, Field, FieldHint, FormError, Input, Select } from "../ui/Form";
import { Modal, ModalActions } from "../ui/Modal";
import { activeSpecificationConstraints, recommendationDeviceOptions } from "./recommendationOptions";
import {
  candidateCategoryForDevice,
  categoricalSpecificationChoices,
  cloneSpecificationFilters,
  specificationFilterCount,
  specificationFilterFields,
} from "./specificationFilterOptions";
import type {
  DashboardRecommendation,
  RecommendationFilters,
  SpecificationConstraint,
} from "./types";

function cleaned(
  filters: Record<string, SpecificationConstraint[]>,
) {
  return Object.fromEntries(
    Object.entries(filters)
      .map(([deviceId, constraints]) => [
        deviceId,
        activeSpecificationConstraints(constraints),
      ] as const)
      .filter(([, constraints]) => constraints.length > 0),
  );
}

export function SpecificationFilterControl({
  recommendations,
  filters,
  onChange,
}: {
  recommendations: readonly DashboardRecommendation[];
  filters: RecommendationFilters;
  onChange: (filters: RecommendationFilters) => void;
}) {
  const id = useId();
  const devices = useMemo(
    () => recommendationDeviceOptions(recommendations),
    [recommendations],
  );
  const [open, setOpen] = useState(false);
  const [selectedDeviceId, setSelectedDeviceId] = useState<number | null>(null);
  const [draft, setDraft] = useState<Record<string, SpecificationConstraint[]>>({});
  const [newSpec, setNewSpec] = useState("");
  const [error, setError] = useState<string | null>(null);
  const count = specificationFilterCount(filters);

  const openModal = () => {
    const preferred = filters.deviceId !== null
      && devices.some((device) => Number(device.value) === filters.deviceId)
      ? filters.deviceId
      : Number(devices[0]?.value);
    setSelectedDeviceId(Number.isFinite(preferred) ? preferred : null);
    setDraft(cloneSpecificationFilters(filters));
    setNewSpec("");
    setError(null);
    setOpen(true);
  };

  const deviceKey = selectedDeviceId === null ? "" : String(selectedDeviceId);
  const constraints = deviceKey ? draft[deviceKey] ?? [] : [];
  const category = selectedDeviceId === null
    ? undefined
    : candidateCategoryForDevice(recommendations, selectedDeviceId);
  const fields = specificationFilterFields(category);
  const fieldsBySpec = new Map(fields.map((field) => [field.spec, field]));
  const availableFields = fields.filter(
    (field) => !constraints.some((constraint) => constraint.spec === field.spec),
  );

  const setConstraints = (next: SpecificationConstraint[]) => {
    if (!deviceKey) return;
    setDraft((current) => ({ ...current, [deviceKey]: next }));
    setError(null);
  };

  const updateConstraint = (index: number, next: SpecificationConstraint) => {
    setConstraints(constraints.map((constraint, current) =>
      current === index ? next : constraint));
  };

  const addConstraint = () => {
    const field = fieldsBySpec.get(newSpec);
    if (!field) return;
    setConstraints([
      ...constraints,
      field.kind === "numeric"
        ? { kind: "numeric", spec: field.spec, min: null, max: null }
        : { kind: "categorical", spec: field.spec, values: [] },
    ]);
    setNewSpec("");
  };

  const apply = () => {
    const invalid = Object.values(draft).flat().find((constraint) =>
      constraint.kind === "numeric"
      && constraint.min !== null
      && constraint.max !== null
      && constraint.min > constraint.max);
    if (invalid) {
      setError("A minimum specification value cannot be greater than its maximum.");
      return;
    }
    onChange({ ...filters, specificationsByDevice: cleaned(draft) });
    setOpen(false);
  };

  return (
    <>
      <Button onClick={openModal}>
        Specifications{count > 0 ? ` (${count})` : ""}
      </Button>

      {open && (
        <Modal
          eyebrow="Optional filters"
          title="Specification filters"
          width="max-w-[760px]"
          onClose={() => setOpen(false)}
        >
          <Field label="Owned device" htmlFor={`${id}-device`}>
            <Select
              id={`${id}-device`}
              value={selectedDeviceId === null ? "" : String(selectedDeviceId)}
              options={devices}
              onChange={(event) => {
                setSelectedDeviceId(Number(event.target.value));
                setNewSpec("");
                setError(null);
              }}
            />
          </Field>

          <FieldHint>
            Blank bounds are unrestricted. Every active rule for this device must match;
            a candidate with an unknown value is excluded.
          </FieldHint>

          <div className="mt-4 max-h-[52vh] space-y-3 overflow-y-auto pr-1">
            {constraints.length === 0 && (
              <div className="rounded-[13px] border border-white/[0.09] bg-[#0a1423] p-4 text-[13px] text-[#8fa0b8]">
                No specification filters for this device yet.
              </div>
            )}

            {constraints.map((constraint, index) => {
              const field = fieldsBySpec.get(constraint.spec);
              if (!field) return null;
              return (
                <div
                  key={constraint.spec}
                  className="rounded-[13px] border border-white/[0.09] bg-[#0a1423] p-4"
                >
                  <div className="mb-3 flex items-start justify-between gap-3">
                    <div>
                      <p className="m-0 text-[13px] font-semibold text-[#eef5ff]">{field.label}</p>
                      <p className="m-0 mt-1 text-[11px] text-[#8fa0b8]">{field.group}</p>
                    </div>
                    <Button
                      size="sm"
                      variant="ghost"
                      aria-label={`Remove ${field.label} filter`}
                      onClick={() => setConstraints(constraints.filter((_, current) => current !== index))}
                    >
                      Remove
                    </Button>
                  </div>

                  {constraint.kind === "numeric" ? (
                    <div className="grid grid-cols-2 gap-3 max-[620px]:grid-cols-1">
                      <Field label={`Minimum${field.unit ? ` (${field.unit})` : ""}`}>
                        <Input
                          type="number"
                          min="0"
                          step="any"
                          aria-label={`Minimum ${field.label}`}
                          value={constraint.min ?? ""}
                          onChange={(event) => updateConstraint(index, {
                            ...constraint,
                            min: event.target.value === "" ? null : Number(event.target.value),
                          })}
                        />
                      </Field>
                      <Field label={`Maximum${field.unit ? ` (${field.unit})` : ""}`}>
                        <Input
                          type="number"
                          min="0"
                          step="any"
                          aria-label={`Maximum ${field.label}`}
                          value={constraint.max ?? ""}
                          onChange={(event) => updateConstraint(index, {
                            ...constraint,
                            max: event.target.value === "" ? null : Number(event.target.value),
                          })}
                        />
                      </Field>
                    </div>
                  ) : (
                    <div className="grid grid-cols-2 gap-2 max-[620px]:grid-cols-1">
                      {categoricalSpecificationChoices(
                        recommendations,
                        selectedDeviceId ?? 0,
                        constraint.spec,
                        constraint.values,
                      ).map((choice) => (
                        <Checkbox
                          key={choice}
                          checked={constraint.values.includes(choice)}
                          onChange={() => updateConstraint(index, {
                            ...constraint,
                            values: constraint.values.includes(choice)
                              ? constraint.values.filter((value) => value !== choice)
                              : [...constraint.values, choice],
                          })}
                        >
                          {choice}
                        </Checkbox>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </div>

          {fields.length === 0 ? (
            <div className="mt-4 rounded-[13px] border border-white/[0.09] bg-[#0a1423] p-4 text-[13px] text-[#8fa0b8]">
              Specification filtering is not available for this device category.
            </div>
          ) : (
            <div className="mt-4 grid grid-cols-[1fr_auto] items-end gap-3 max-[620px]:grid-cols-1">
              <Field label="Add specification" htmlFor={`${id}-spec`}>
                <Select
                  id={`${id}-spec`}
                  value={newSpec}
                  options={[
                    { value: "", label: availableFields.length > 0 ? "Choose a specification" : "All specifications added" },
                    ...availableFields.map((field) => ({
                      value: field.spec,
                      label: `${field.group} · ${field.label}`,
                    })),
                  ]}
                  onChange={(event) => setNewSpec(event.target.value)}
                />
              </Field>
              <Button className="mb-[14px] max-[620px]:mb-0" disabled={!newSpec} onClick={addConstraint}>
                Add filter
              </Button>
            </div>
          )}

          <FormError message={error} />
          <ModalActions>
            <Button
              size="sm"
              variant="ghost"
              disabled={Object.keys(draft).length === 0}
              onClick={() => {
                setDraft({});
                setError(null);
              }}
            >
              Clear all
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button size="sm" variant="primary" onClick={apply}>
              Apply filters
            </Button>
          </ModalActions>
        </Modal>
      )}
    </>
  );
}

