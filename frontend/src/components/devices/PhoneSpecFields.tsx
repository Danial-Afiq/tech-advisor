import { Eyebrow } from "../ui/Eyebrow";
import { Field, FieldHint, FormGrid, Input } from "../ui/Form";
import { Tag } from "../ui/Tag";
import type { SpecFormValues } from "./catalogue";
import { PHONE_SPEC_GROUPS, TEXT_SPEC_KEYS } from "./phoneSpecs";
import type { PhoneSpecField, PhoneSpecKey } from "./phoneSpecs";

const inputUnit = (field: PhoneSpecField) =>
  field.inputUnit ?? field.unit?.trim();

/**
 * Editable phone specifications, grouped like `PhoneSpecsDropdown`. With a
 * `baseline` (the linked catalogue values) a field the user has changed is
 * tagged "Edited"; without one every field is plain manual entry.
 */
export function PhoneSpecFields({
  values,
  onChange,
  baseline,
}: {
  values: SpecFormValues;
  onChange: (key: PhoneSpecKey, value: string) => void;
  baseline?: SpecFormValues;
}) {
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
              return (
                <Field
                  key={field.key}
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
                    type={text ? "text" : "number"}
                    inputMode={text ? undefined : "decimal"}
                    min={text ? undefined : 0}
                    step={text ? undefined : "any"}
                    value={values[field.key]}
                    onChange={(e) => onChange(field.key, e.target.value)}
                  />
                </Field>
              );
            })}
          </FormGrid>
        </div>
      ))}
    </section>
  );
}
