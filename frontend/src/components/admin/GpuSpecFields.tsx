import { Field, FieldHint, FormGrid, Input } from "../ui/Form";
import { GPU_SPEC_FIELDS } from "./catalogueAdmin";
import type { GpuSpecFormValues, GpuSpecKey } from "./catalogueAdmin";

/**
 * Editable GPU specifications for the admin catalogue form. A blank field is
 * saved as unknown (null), never as 0.
 */
export function GpuSpecFields({
  values,
  onChange,
  provenance,
}: {
  values: GpuSpecFormValues;
  onChange: (key: GpuSpecKey, value: string) => void;
  /** Read-only "where these values came from", shown when editing. */
  provenance?: string | null;
}) {
  return (
    <section className="mb-[14px] border-t border-white/[0.09] pt-4">
      <h4 className="m-0 text-[16px] font-extrabold text-[#eef5ff]">Specifications</h4>
      <div className="mt-1 mb-3">
        <FieldHint>
          Leave a field blank if it is unknown.
          {provenance && ` Current values: ${provenance}.`}
        </FieldHint>
      </div>
      <FormGrid>
        {GPU_SPEC_FIELDS.map((field) => (
          <Field key={field.key} label={field.unit ? `${field.label} (${field.unit})` : field.label}>
            <Input
              type={field.numeric ? "number" : "text"}
              inputMode={field.numeric ? "decimal" : undefined}
              min={field.numeric ? 0 : undefined}
              step={field.numeric ? "any" : undefined}
              maxLength={field.numeric ? undefined : 100}
              value={values[field.key]}
              onChange={(e) => onChange(field.key, e.target.value)}
            />
          </Field>
        ))}
      </FormGrid>
    </section>
  );
}
