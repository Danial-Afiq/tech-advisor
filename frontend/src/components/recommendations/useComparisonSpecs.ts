import { useEffect, useMemo, useState } from "react";
import { listDevices } from "../../api/devices";
import { catalogueSpecs, withCatalogue } from "../devices/catalogue";
import { deviceFromApi } from "../devices/deviceApi";
import type { DeviceResponse } from "../devices/deviceApi";
import type { PhoneSpecs } from "../devices/phoneSpecs";
import { useSmartphoneCatalogue } from "../devices/useSmartphoneCatalogue";

export type ComparisonSpecs = {
  loading: boolean;
  /** The owned device's specs: its catalogue row with the user's overrides on top. */
  currentSpecs: (deviceId: number) => PhoneSpecs | undefined;
  /** The suggested product's catalogue specs. */
  candidateSpecs: (productId: number) => PhoneSpecs | undefined;
  /** The suggested product's thumbnail (MobileAPI), if known. */
  candidateImage: (productId: number) => string | undefined;
};

/**
 * Specs for the dashboard's side-by-side comparison, from the existing
 * `GET /api/devices` and `GET /api/catalogue/smartphones`. Either request
 * failing only makes the comparison unavailable; it is not a page error.
 */
export function useComparisonSpecs(): ComparisonSpecs {
  const catalogue = useSmartphoneCatalogue(true);
  const [devices, setDevices] = useState<DeviceResponse[] | null>(null);
  const [devicesFailed, setDevicesFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listDevices()
      .then((rows) => {
        if (!cancelled) setDevices(rows);
      })
      .catch(() => {
        if (!cancelled) setDevicesFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return useMemo(() => {
    const products = new Map(catalogue.items.map((item) => [item.id, item]));
    const owned = new Map<number, PhoneSpecs | undefined>();
    (devices ?? []).forEach((row) => {
      const device = deviceFromApi(row);
      const item = row.productId === null ? undefined : products.get(row.productId);
      owned.set(row.id, withCatalogue(device, item).specs);
    });

    return {
      loading: catalogue.status === "loading" || (devices === null && !devicesFailed),
      currentSpecs: (deviceId) => owned.get(deviceId),
      candidateSpecs: (productId) => {
        const item = products.get(productId);
        return item && catalogueSpecs(item);
      },
      candidateImage: (productId) => products.get(productId)?.imageUrl ?? undefined,
    };
  }, [catalogue, devices, devicesFailed]);
}
