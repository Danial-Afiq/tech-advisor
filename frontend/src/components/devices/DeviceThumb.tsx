import { deviceIcon } from "./deviceOptions";
import type { Device } from "./types";

/**
 * Device picture tile. `lg` (92px) shows the product image when there is one;
 * `sm` (42px) always shows the type emoji, as the prototype's pickers do.
 */
export function DeviceThumb({
  device,
  size = "lg",
}: {
  device: Pick<Device, "type" | "brand" | "model" | "image">;
  size?: "lg" | "sm";
}) {
  const box =
    size === "lg" ? "h-[92px] w-[92px] text-[24px]" : "h-[42px] w-[42px] text-[20px]";

  return (
    <div
      className={`grid shrink-0 place-items-center overflow-hidden rounded-[15px] border border-white/[0.09] bg-[linear-gradient(135deg,#213452,#111d31)] ${box}`}
    >
      {size === "lg" && device.image ? (
        <img
          src={device.image}
          alt={`${device.brand} ${device.model} product image`}
          className="block h-full w-full object-contain p-1"
        />
      ) : (
        deviceIcon(device.type)
      )}
    </div>
  );
}
