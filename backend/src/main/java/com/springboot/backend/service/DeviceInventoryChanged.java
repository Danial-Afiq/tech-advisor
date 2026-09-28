package com.springboot.backend.service;

/**
 * Published by {@link DeviceService} when a user adds a device to their
 * inventory or edits one. Listeners that need the change to be visible (the
 * recommendation trigger) must listen after commit, not on publish.
 *
 * @param userDeviceId the {@code user_devices.id} that was added or changed
 */
public record DeviceInventoryChanged(Long userDeviceId) {}
