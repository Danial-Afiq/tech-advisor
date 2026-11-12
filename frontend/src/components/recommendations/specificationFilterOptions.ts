import { specSchemaForCategory } from "../devices/specSchemas";
import type {
  DashboardRecommendation,
  RecommendationFilters,
  SpecificationConstraint,
} from "./types";
import { activeSpecificationConstraints } from "./recommendationOptions";

export type SpecificationFilterField = {
  spec: string;
  label: string;
  group: string;
  kind: "numeric" | "categorical";
  unit?: string;
};

/** Recommendation snapshots use database-style names while the form schemas use camelCase. */
export const specificationApiKey = (key: string) =>
  key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);

export function specificationFilterFields(category: string | undefined) {
  const schema = specSchemaForCategory(category);
  if (!schema) return [];

  return schema.groups.flatMap(({ title, fields }) =>
    fields.flatMap((field) => {
      const isText = schema.text.has(field.key);
      if (isText && !schema.selectable.has(field.key)) return [];
      return [{
        spec: specificationApiKey(String(field.key)),
        label: field.label,
        group: title,
        kind: isText ? "categorical" as const : "numeric" as const,
        unit: field.inputUnit ?? field.unit?.trim(),
      }];
    }),
  );
}

export function candidateCategoryForDevice(
  recommendations: readonly DashboardRecommendation[],
  deviceId: number,
) {
  return recommendations.find((row) => row.currentDeviceId === deviceId)?.candidateCategory;
}

export function categoricalSpecificationChoices(
  recommendations: readonly DashboardRecommendation[],
  deviceId: number,
  spec: string,
  selected: readonly string[] = [],
) {
  const choices = new Map<string, string>();
  [...selected, ...recommendations
    .filter((row) => row.currentDeviceId === deviceId)
    .map((row) => row.candidateSpecifications?.[spec])
    .filter((value): value is string => typeof value === "string")]
    .forEach((value) => {
      const trimmed = value.trim();
      if (trimmed) choices.set(trimmed.toLowerCase(), trimmed);
    });
  return [...choices.values()].sort((first, second) => first.localeCompare(second));
}

export function specificationFilterCount(filters: RecommendationFilters) {
  return Object.values(filters.specificationsByDevice)
    .reduce((count, constraints) =>
      count + activeSpecificationConstraints(constraints).length, 0);
}

export function cloneSpecificationFilters(
  filters: RecommendationFilters,
): Record<string, SpecificationConstraint[]> {
  return Object.fromEntries(
    Object.entries(filters.specificationsByDevice).map(([deviceId, constraints]) => [
      deviceId,
      constraints.map((constraint) => constraint.kind === "categorical"
        ? { ...constraint, values: [...constraint.values] }
        : { ...constraint }),
    ]),
  );
}

