package com.springboot.backend.repository;

import com.springboot.backend.model.DevicePreference;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Preferences are keyed by the owning device's id, so {@code findById} already
 * means "preferences for this device".
 */
public interface DevicePreferenceRepository extends JpaRepository<DevicePreference, Long> {
}
