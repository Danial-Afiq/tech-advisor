import { useState } from "react";
import gpuPlaceholder from "../../assets/placeholders/gpu.svg";
import { deviceIcon } from "./deviceOptions";
import type { Device } from "./types";

/** Drawn stand-ins for device types whose catalogue has no images yet. */
const PLACEHOLDER_IMAGES: Record<string, string> = { GPU: gpuPlaceholder };

/**
 * Device picture tile, `lg` (92px) or `sm` (42px). Shows the product image
 * when there is one (phone thumbnails come from MobileAPI; GPUs use
 * `products.image_url` once a source fills it), else the type's placeholder
 * image, else the type emoji.
 */
export function DeviceThumb({
  device,
  size = "lg",
}: {
  device: Pick<Device, "type" | "brand" | "model" | "image">;
  size?: "lg" | "sm";
}) {
  // Remembers which URL failed, so a new image for the same tile is tried again.
  const [failed, setFailed] = useState<string | null>(null);
  const showImage = device.image && device.image !== failed;
  const placeholder = PLACEHOLDER_IMAGES[device.type];
  const box =
    size === "lg" ? "h-[92px] w-[92px] text-[24px]" : "h-[42px] w-[42px] text-[20px]";

  return (
    <div
      className={`grid shrink-0 place-items-center overflow-hidden rounded-[15px] border border-white/[0.09] bg-[linear-gradient(135deg,#213452,#111d31)] ${box}`}
    >
      {showImage ? (
        <img
          src={device.image}
          alt={`${device.brand} ${device.model} product image`}
          loading="lazy"
          referrerPolicy="no-referrer"
          onError={() => setFailed(device.image ?? null)}
          className="block h-full w-full object-contain p-1"
        />
      ) : placeholder ? (
        <img
          src={placeholder}
          alt=""
          aria-hidden="true"
          data-testid="device-placeholder"
          className="block h-full w-full object-contain p-[12%]"
        />
      ) : (
        deviceIcon(device.type)
      )}
    </div>
  );
}
