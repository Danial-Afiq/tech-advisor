import { PHONE_SPEC_GROUPS, formatSpec } from "./phoneSpecs";
import type { PhoneSpecs } from "./phoneSpecs";

/**
 * Collapsible "Specifications" panel (daisyUI `collapse`) for a phone.
 * Unknown values are left out; groups with nothing known are hidden.
 */
export function PhoneSpecsDropdown({
  specs,
  defaultOpen = false,
}: {
  specs?: PhoneSpecs | null;
  defaultOpen?: boolean;
}) {
  const groups = PHONE_SPEC_GROUPS.map((group) => ({
    title: group.title,
    rows: group.fields
      .map((field) => ({
        label: field.label,
        value: formatSpec(field, specs?.[field.key]),
      }))
      .filter((row) => row.value !== null),
  })).filter((group) => group.rows.length > 0);

  const known = groups.reduce((sum, group) => sum + group.rows.length, 0);

  return (
    <details
      className="collapse collapse-arrow rounded-[13px] border border-white/[0.09] bg-[#0a1423]"
      open={defaultOpen}
    >
      <summary className="collapse-title min-h-0 py-[10px] pr-10 pl-3 text-[12px] font-bold">
        Specifications{" "}
        <span className="font-normal text-[#8fa0b8]">
          · {known > 0 ? `${known} listed` : "none on file"}
        </span>
      </summary>

      <div className="collapse-content px-3 text-[12px]">
        {groups.length === 0 ? (
          <p className="m-0 text-[#8fa0b8]">
            No specifications are on file for this phone yet.
          </p>
        ) : (
          <div className="grid gap-3">
            {groups.map((group) => (
              <section key={group.title}>
                <h4 className="mb-1 text-[10px] font-bold uppercase tracking-[.12em] text-[#9fb1c9]">
                  {group.title}
                </h4>
                <dl className="m-0">
                  {group.rows.map((row) => (
                    <div
                      key={row.label}
                      className="flex justify-between gap-3 border-b border-white/[0.09] py-[6px] last:border-b-0"
                    >
                      <dt className="text-[#8fa0b8]">{row.label}</dt>
                      <dd className="m-0 text-right font-semibold">
                        {row.value}
                      </dd>
                    </div>
                  ))}
                </dl>
              </section>
            ))}
          </div>
        )}
      </div>
    </details>
  );
}
