/**
 * Phone specifications — one field per column of the `phone` table
 * (backend V6 migration), in camelCase. Every field is optional/nullable
 * because catalogue ingestion may not know it.
 */
export type PhoneSpecs = {
  chipset?: string | null;
  ramGb?: number | null;
  cpuGhz?: number | null;
  storageGb?: number | null;
  batteryMah?: number | null;
  wiredChargingWatts?: number | null;
  wirelessChargingWatts?: number | null;
  displaySizeInches?: number | null;
  refreshRateHz?: number | null;
  weightG?: number | null;
  cameraSpecs?: string | null;
  pixelDensity?: number | null;
  ipRating?: string | null;
  os?: string | null;
  softwareSupportYears?: number | null;
};

export type PhoneSpecKey = keyof PhoneSpecs;

export type PhoneSpecField = {
  key: PhoneSpecKey;
  label: string;
  /** Appended to numeric values, e.g. " GB". */
  unit?: string;
  /** Custom formatting for numeric values; overrides `unit`. */
  format?: (value: number) => string;
};

const watts = (v: number) => (v === 0 ? "Not supported" : `${v} W`);

/**
 * What the specifications dropdown shows, in order. To show a new column,
 * add it to `PhoneSpecs` and to one of these groups — nothing else changes.
 */
export const PHONE_SPEC_GROUPS: { title: string; fields: PhoneSpecField[] }[] =
  [
    {
      title: "Performance",
      fields: [
        { key: "chipset", label: "Chipset" },
        { key: "cpuGhz", label: "CPU speed", unit: " GHz" },
        { key: "ramGb", label: "RAM", unit: " GB" },
        {
          key: "storageGb",
          label: "Storage",
          format: (v) => (v >= 1024 ? `${v / 1024} TB` : `${v} GB`),
        },
      ],
    },
    {
      title: "Display",
      fields: [
        { key: "displaySizeInches", label: "Screen size", unit: " in" },
        { key: "refreshRateHz", label: "Refresh rate", unit: " Hz" },
        { key: "pixelDensity", label: "Pixel density", unit: " ppi" },
      ],
    },
    {
      title: "Battery & charging",
      fields: [
        { key: "batteryMah", label: "Battery", unit: " mAh" },
        { key: "wiredChargingWatts", label: "Wired charging", format: watts },
        {
          key: "wirelessChargingWatts",
          label: "Wireless charging",
          format: watts,
        },
      ],
    },
    {
      title: "Camera & build",
      fields: [
        { key: "cameraSpecs", label: "Camera" },
        { key: "weightG", label: "Weight", unit: " g" },
        { key: "ipRating", label: "Water resistance" },
      ],
    },
    {
      title: "Software",
      fields: [
        { key: "os", label: "Operating system" },
        {
          key: "softwareSupportYears",
          label: "Software support",
          format: (v) => `${v} year${v === 1 ? "" : "s"}`,
        },
      ],
    },
  ];

export const PHONE_SPEC_KEYS: PhoneSpecKey[] = PHONE_SPEC_GROUPS.flatMap((g) =>
  g.fields.map((f) => f.key)
);

/** Display text for one spec, or `null` when the value is unknown. */
export function formatSpec(
  field: PhoneSpecField,
  value: PhoneSpecs[PhoneSpecKey]
): string | null {
  if (value === null || value === undefined || value === "") return null;
  if (typeof value === "number") {
    return field.format
      ? field.format(value)
      : `${value.toLocaleString()}${field.unit ?? ""}`;
  }
  return value;
}
