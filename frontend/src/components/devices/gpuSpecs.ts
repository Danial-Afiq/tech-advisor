import type { SpecField } from "./phoneSpecs";

/**
 * GPU specifications, named after the backend spec names (`GpuSpecMapping`)
 * in camelCase, so `spec_overrides` keys (`vram`, `bus_width`, …) are the
 * ones the recommendation scorer reads.
 */
export type GpuSpecs = {
  architecture?: string | null;
  baseClockMhz?: number | null;
  boostClockMhz?: number | null;
  vram?: number | null;
  memoryType?: string | null;
  busWidth?: number | null;
  tdpWatts?: number | null;
};

export type GpuSpecKey = keyof GpuSpecs;

export const GPU_SPEC_GROUPS: { title: string; fields: SpecField<GpuSpecKey>[] }[] = [
  {
    title: "Performance",
    fields: [
      { key: "architecture", label: "Architecture" },
      { key: "baseClockMhz", label: "Base clock", unit: " MHz" },
      { key: "boostClockMhz", label: "Boost clock", unit: " MHz" },
    ],
  },
  {
    title: "Memory",
    fields: [
      { key: "vram", label: "VRAM", unit: " GB" },
      { key: "memoryType", label: "Memory type" },
      { key: "busWidth", label: "Memory bus", unit: "-bit", inputUnit: "bits" },
    ],
  },
  {
    title: "Power",
    fields: [{ key: "tdpWatts", label: "TDP", unit: " W" }],
  },
];

export const GPU_SPEC_KEYS: GpuSpecKey[] = GPU_SPEC_GROUPS.flatMap((g) =>
  g.fields.map((f) => f.key)
);

export const GPU_TEXT_SPEC_KEYS = new Set<GpuSpecKey>(["architecture", "memoryType"]);
