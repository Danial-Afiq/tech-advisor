import type {
  DeviceRequest,
  DeviceResponse,
} from "../components/devices/deviceApi";
import { apiFetch } from "./client";

/** `GET /api/devices` — the signed-in user's current devices. */
export const listDevices = () => apiFetch<DeviceResponse[]>("/api/devices");

/** `POST /api/devices` — adds a device to the signed-in user's inventory. */
export const createDevice = (request: DeviceRequest) =>
  apiFetch<DeviceResponse>("/api/devices", { method: "POST", body: request });
