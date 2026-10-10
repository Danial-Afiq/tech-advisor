import { useId } from "react";
import { Button } from "../ui/Button";
import { Eyebrow } from "../ui/Eyebrow";
import { Field, FieldHint, FormGrid, Input } from "../ui/Form";
import { Tag } from "../ui/Tag";
import { isCleared } from "./catalogue";
import type { SpecFormValues } from "./catalogue";
import { PHONE_SPEC_GROUPS, TEXT_SPEC_KEYS } from "./phoneSpecs";
import type { PhoneSpecField, PhoneSpecKey } from "./phoneSpecs";

const inputUnit = (field: PhoneSpecField) =>
  field.inputUnit ?? field.unit?.trim();

/**
 * Editable phone specifications, grouped like `PhoneSpecsDropdown`. With a
 * `baseline` (the linked catalogue values) a field the user has changed is
 * tagged "Edited"; without one every field is plain manual entry.
 *
 * Emptying a prefilled field shows a warning offering to put the baseline
 * value back or keep the field empty on purpose (`onKeepEmpty`). Keys in
 * `keptEmpty` have been confirmed and show a short note instead.
 */
export function PhoneSpecFields({
  values,
  onChange,
  baseline,
  baselineIsCatalogue = true,
  keptEmpty,
  onKeepEmpty,
}: {
  values: SpecFormValues;
  onChange: (key: PhoneSpecKey, value: string) => void;
  baseline?: SpecFormValues;
  /** False when `baseline` is the device's saved specs, not the catalogue. */
  baselineIsCatalogue?: boolean;
  keptEmpty?: ReadonlySet<PhoneSpecKey>;
  onKeepEmpty?: (key: PhoneSpecKey) => void;
}) {
  const idPrefix = useId();
  const source = baselineIsCatalogue ? "The catalogue lists" : "It was";
  const restoreLabel = baselineIsCatalogue
    ? "Use catalogue value"
    : "Restore previous value";

  return (
    <section className="mb-[14px] border-t border-white/[0.09] pt-4">
      <h4 className="m-0 text-[16px] font-extrabold text-[#eef5ff]">
        Specifications
      </h4>
      <div className="mb-3 mt-1">
        <FieldHint>
          {baseline
            ? "Prefilled from the product catalogue. Change anything that's different on your device."
            : "Optional. Fill in what you know; leave the rest blank."}
        </FieldHint>
      </div>

      {PHONE_SPEC_GROUPS.map((group) => (
        <div key={group.title}>
          <div className="mb-2">
            <Eyebrow>{group.title}</Eyebrow>
          </div>
          <FormGrid>
            {group.fields.map((field) => {
              const unit = inputUnit(field);
              const text = TEXT_SPEC_KEYS.has(field.key);
              const edited =
                baseline !== undefined &&
                baseline[field.key].trim() !== values[field.key].trim();
              const cleared =
                baseline !== undefined && isCleared(values, baseline, field.key);
              const kept = cleared && Boolean(keptEmpty?.has(field.key));
              const id = `${idPrefix}-${field.key}`;
              const noteId = `${id}-note`;
              const previous =
                baseline && `${baseline[field.key].trim()}${unit && !text ? ` ${unit}` : ""}`;
              return (
                <Field
                  key={field.key}
                  htmlFor={id}
                  span2={field.key === "cameraSpecs"}
                  label={
                    <span className="flex items-center gap-2">
                      {unit ? `${field.label} (${unit})` : field.label}
                      {edited && (
                        <>
                          {" "}
                          <Tag>Edited</Tag>
                        </>
                      )}
                    </span>
                  }
                >
                  <Input
                    id={id}
                    type={text ? "text" : "number"}
                    inputMode={text ? undefined : "decimal"}
                    min={text ? undefined : 0}
                    step={text ? undefined : "any"}
                    value={values[field.key]}
                    aria-describedby={cleared ? noteId : undefined}
                    aria-invalid={cleared && !kept ? true : undefined}
                    onChange={(e) => onChange(field.key, e.target.value)}
                  />
                  {cleared && !kept && (
                    <div
                      id={noteId}
                      role="alert"
                      className="flex flex-col gap-2 rounded-[10px] border border-[#f5b942]/40 bg-[#f5b942]/[0.08] p-[10px] text-[12px] leading-[1.45] text-[#f2d79b]"
                    >
                      <span>
                        You left this empty. {source} <b>{previous}</b>. Empty
                        means "unknown", and it won't be used to compare upgrades.
                      </span>
                      <span className="flex flex-wrap gap-2">
                        <Button
                          size="sm"
                          variant="secondary"
                          onClick={() => onChange(field.key, baseline[field.key])}
                        >
                          {restoreLabel}
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => onKeepEmpty?.(field.key)}
                        >
                          Keep empty
                        </Button>
                      </span>
                    </div>
                  )}
                  {kept && (
                    <FieldHint id={noteId}>
                      Left empty on purpose, saved as unknown.{" "}
                      <button
                        type="button"
                        className="cursor-pointer border-0 bg-transparent p-0 text-[12px] font-semibold text-[#b9a6ff] underline"
                        onClick={() => onChange(field.key, baseline[field.key])}
                      >
                        {restoreLabel}
                      </button>
                    </FieldHint>
                  )}
                </Field>
              );
            })}
          </FormGrid>
        </div>
      ))}
    </section>
  );
}
