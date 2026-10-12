import { useEffect, useId, useRef, useState } from "react";
import { Button } from "../ui/Button";
import { Card } from "../ui/Card";
import { Checkbox, Field, FieldHint, Select } from "../ui/Form";
import {
  EVIDENCE_GRADE_OPTIONS,
  VERDICT_OPTIONS,
  emptyRecommendationFilters,
  recommendationDeviceOptions,
  selectedFilterCount,
} from "./recommendationOptions";
import type {
  DashboardRecommendation,
  FilterEvidenceGrade,
  RecommendationFilters,
  RecommendationVerdict,
} from "./types";

export function RecommendationFilterDropdown({
  recommendations,
  filters,
  onChange,
}: {
  recommendations: readonly DashboardRecommendation[];
  filters: RecommendationFilters;
  onChange: (filters: RecommendationFilters) => void;
}) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const count = selectedFilterCount(filters);

  function close() {
    setOpen(false);
    triggerRef.current?.focus();
  }

  useEffect(() => {
    if (!open) return;

    panelRef.current?.querySelector("select")?.focus();

    function handlePointerDown(event: PointerEvent) {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) {
        setOpen(false);
      }
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        setOpen(false);
        triggerRef.current?.focus();
      }
    }

    document.addEventListener("pointerdown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("pointerdown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open]);

  function toggleVerdict(value: RecommendationVerdict) {
    const selected = filters.verdicts;
    onChange({
      ...filters,
      verdicts: selected.includes(value)
        ? selected.filter((item) => item !== value)
        : [...selected, value],
    });
  }

  function toggleEvidenceGrade(value: FilterEvidenceGrade) {
    const selected = filters.evidenceGrades;
    onChange({
      ...filters,
      evidenceGrades: selected.includes(value)
        ? selected.filter((item) => item !== value)
        : [...selected, value],
    });
  }

  return (
    <div
      ref={rootRef}
      className="relative shrink-0 max-[620px]:w-full"
      onBlur={(event) => {
        if (event.relatedTarget instanceof Node && !event.currentTarget.contains(event.relatedTarget)) {
          setOpen(false);
        }
      }}
    >
      <Button
        ref={triggerRef}
        aria-expanded={open}
        aria-haspopup="dialog"
        aria-controls={`${id}-panel`}
        onClick={() => setOpen(!open)}
      >
        Filters{count > 0 ? ` (${count})` : ""} <span aria-hidden="true">▾</span>
      </Button>

      {open && (
        <div
          ref={panelRef}
          id={`${id}-panel`}
          role="dialog"
          aria-labelledby={`${id}-title`}
          aria-describedby={`${id}-hint`}
          className="absolute right-0 z-20 mt-2 w-[340px] max-[860px]:right-auto max-[860px]:left-0 max-[620px]:w-full"
        >
          <Card className="bg-[#0d1727]">
            <div className="card-body max-h-[calc(100dvh-260px)] gap-[16px] overflow-y-auto p-[18px] max-[620px]:max-h-[calc(100dvh-320px)]">
              <div>
                <h3 id={`${id}-title`} className="m-0 text-[16px] font-extrabold text-[#eef5ff]">
                  Filter recommendations
                </h3>
                <FieldHint id={`${id}-hint`}>
                  Results update as you select. All filter groups must match.
                </FieldHint>
              </div>

              <Field label="Owned device" htmlFor={`${id}-device`}>
                <Select
                  id={`${id}-device`}
                  value={filters.deviceId === null ? "" : String(filters.deviceId)}
                  options={[
                    { value: "", label: "All devices" },
                    ...recommendationDeviceOptions(recommendations),
                  ]}
                  onChange={(event) => onChange({
                    ...filters,
                    deviceId: event.target.value === "" ? null : Number(event.target.value),
                  })}
                />
              </Field>

              <fieldset className="m-0 grid gap-[10px] border-0 p-0">
                <legend className="mb-2 text-[13px] font-semibold text-[#c6d1df]">Verdict</legend>
                {VERDICT_OPTIONS.map(({ value, label }) => (
                  <Checkbox
                    key={value}
                    checked={filters.verdicts.includes(value)}
                    onChange={() => toggleVerdict(value)}
                  >
                    {label}
                  </Checkbox>
                ))}
              </fieldset>

              <fieldset className="m-0 grid grid-cols-2 gap-[10px] border-0 p-0">
                <legend className="mb-2 text-[13px] font-semibold text-[#c6d1df]">Evidence grade</legend>
                {EVIDENCE_GRADE_OPTIONS.map(({ value, label }) => (
                  <Checkbox
                    key={value}
                    checked={filters.evidenceGrades.includes(value)}
                    onChange={() => toggleEvidenceGrade(value)}
                  >
                    {label}
                  </Checkbox>
                ))}
              </fieldset>

              <div className="flex flex-wrap justify-between gap-2 border-t border-white/[0.09] pt-[14px]">
                <Button size="sm" variant="ghost" disabled={count === 0} onClick={() => onChange(emptyRecommendationFilters())}>
                  Clear filters
                </Button>
                <Button size="sm" variant="primary" onClick={close}>Done</Button>
              </div>
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}
