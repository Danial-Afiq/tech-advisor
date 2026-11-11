import { useEffect, useMemo, useState } from "react";
import { listCatalogueGpus, listSmartphones } from "../../api/catalogue";
import type { SmartphoneCatalogueItem } from "../../api/catalogue";
import type { CatalogueItem } from "./catalogue";

export type Catalogue<T> = {
  /** "unavailable" covers signed-out use and load failures alike. */
  status: "loading" | "ready" | "unavailable";
  items: T[];
};

export type SmartphoneCatalogue = Catalogue<SmartphoneCatalogueItem>;
/** Smartphones and GPUs together, as the device form searches them. */
export type DeviceCatalogue = Catalogue<CatalogueItem>;

// Shared constants, so a consumer's memos don't rerun on every render.
const UNAVAILABLE: Catalogue<never> = { status: "unavailable", items: [] };
const LOADING: Catalogue<never> = { status: "loading", items: [] };

/**
 * Loads one catalogue list once while `enabled` (the endpoints need a
 * signed-in user). A failure is not an error for the user: the device form
 * just falls back to manual entry.
 */
function useCatalogue<T>(enabled: boolean, load: () => Promise<T[]>): Catalogue<T> {
  const [loaded, setLoaded] = useState<Catalogue<T> | null>(null);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    Promise.resolve()
      .then(load)
      .then((items) => {
        if (!cancelled) setLoaded({ status: "ready", items });
      })
      .catch(() => {
        if (!cancelled) setLoaded(UNAVAILABLE);
      });
    return () => {
      cancelled = true;
    };
  }, [enabled, load]);

  if (!enabled) return UNAVAILABLE;
  return loaded ?? LOADING;
}

export const useSmartphoneCatalogue = (enabled: boolean): SmartphoneCatalogue =>
  useCatalogue(enabled, listSmartphones);

/**
 * Smartphones and GPUs for the device form and device cards. Items from a
 * list that has loaded are usable straight away; a failed list is skipped,
 * so a GPU outage never blocks phones (or the other way round).
 */
export function useDeviceCatalogue(enabled: boolean): DeviceCatalogue {
  const phones = useCatalogue<CatalogueItem>(enabled, listSmartphones);
  const gpus = useCatalogue<CatalogueItem>(enabled, listCatalogueGpus);
  return useMemo(() => {
    const parts = [phones, gpus];
    const status = parts.some((p) => p.status === "loading")
      ? "loading"
      : parts.some((p) => p.status === "ready")
        ? "ready"
        : "unavailable";
    return { status, items: [...phones.items, ...gpus.items] };
  }, [phones, gpus]);
}
