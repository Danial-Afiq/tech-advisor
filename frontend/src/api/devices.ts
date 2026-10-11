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

/** `PUT /api/devices/{id}` — replaces every editable field of an owned device. */
export const updateDevice = (id: string, request: DeviceRequest) =>
  apiFetch<DeviceResponse>(`/api/devices/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: request,
  });

/** `DELETE /api/devices/{id}` — removes a device from the user's current devices. */
export const deleteDevice = (id: string) =>
  apiFetch<void>(`/api/devices/${encodeURIComponent(id)}`, { method: "DELETE" });
