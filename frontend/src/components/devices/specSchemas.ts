import { GPU_SPEC_GROUPS, GPU_SPEC_KEYS, GPU_TEXT_SPEC_KEYS } from "./gpuSpecs";
import type { GpuSpecs } from "./gpuSpecs";
import { PHONE_SPEC_GROUPS, PHONE_SPEC_KEYS, TEXT_SPEC_KEYS } from "./phoneSpecs";
import type { PhoneSpecs, SpecField } from "./phoneSpecs";

/**
 * Every spec a device can carry. Phone and GPU keys don't overlap, so one
 * bag holds either and the device type says which set applies.
 */
export type DeviceSpecs = PhoneSpecs & GpuSpecs;
export type SpecKey = keyof DeviceSpecs;

/** Which specs a device type has and how they are grouped for display. */
export type SpecSchema = {
  groups: { title: string; fields: SpecField<SpecKey>[] }[];
  keys: SpecKey[];
  text: ReadonlySet<SpecKey>;
  /** Text fields suitable for deterministic exact-choice filtering. */
  selectable: ReadonlySet<SpecKey>;
  /** Fields of a catalogue suggestion's one-line summary, in order. */
  summary: SpecKey[];
};

export const PHONE_SPEC_SCHEMA: SpecSchema = {
  groups: PHONE_SPEC_GROUPS,
  keys: PHONE_SPEC_KEYS,
  text: TEXT_SPEC_KEYS,
  selectable: new Set<SpecKey>(["ipRating", "os"]),
  summary: ["chipset", "storageGb", "ramGb"],
};

export const GPU_SPEC_SCHEMA: SpecSchema = {
  groups: GPU_SPEC_GROUPS,
  keys: GPU_SPEC_KEYS,
  text: GPU_TEXT_SPEC_KEYS,
  selectable: new Set<SpecKey>(["architecture", "memoryType"]),
  summary: ["vram", "memoryType", "architecture"],
};

/** Device types with editable specifications. */
const SCHEMAS: Record<string, SpecSchema> = {
  Phone: PHONE_SPEC_SCHEMA,
  GPU: GPU_SPEC_SCHEMA,
};

export const specSchemaFor = (type: string | undefined): SpecSchema | undefined =>
  type ? SCHEMAS[type] : undefined;

/** API product categories use different names from the device-form labels. */
export const specSchemaForCategory = (
  category: string | undefined
): SpecSchema | undefined =>
  category === "SMARTPHONE"
    ? PHONE_SPEC_SCHEMA
    : category === "GPU"
      ? GPU_SPEC_SCHEMA
      : undefined;

export const ALL_SPEC_KEYS: SpecKey[] = [...PHONE_SPEC_KEYS, ...GPU_SPEC_KEYS];
export const ALL_TEXT_SPEC_KEYS: ReadonlySet<SpecKey> = new Set<SpecKey>([
  ...TEXT_SPEC_KEYS,
  ...GPU_TEXT_SPEC_KEYS,
]);
