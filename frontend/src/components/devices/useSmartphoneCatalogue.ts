import { useEffect, useState } from "react";
import { listSmartphones } from "../../api/catalogue";
import type { SmartphoneCatalogueItem } from "../../api/catalogue";

export type SmartphoneCatalogue = {
  /** "unavailable" covers signed-out use and load failures alike. */
  status: "loading" | "ready" | "unavailable";
  items: SmartphoneCatalogueItem[];
};

const UNAVAILABLE: SmartphoneCatalogue = { status: "unavailable", items: [] };

/**
 * Loads the smartphone catalogue once while `enabled` (the endpoint needs a
 * signed-in user). A failure is not an error for the user: the device form
 * just falls back to manual entry.
 */
export function useSmartphoneCatalogue(enabled: boolean): SmartphoneCatalogue {
  const [loaded, setLoaded] = useState<SmartphoneCatalogue | null>(null);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    listSmartphones()
      .then((items) => {
        if (!cancelled) setLoaded({ status: "ready", items });
      })
      .catch(() => {
        if (!cancelled) setLoaded(UNAVAILABLE);
      });
    return () => {
      cancelled = true;
    };
  }, [enabled]);

  if (!enabled) return UNAVAILABLE;
  return loaded ?? { status: "loading", items: [] };
}
