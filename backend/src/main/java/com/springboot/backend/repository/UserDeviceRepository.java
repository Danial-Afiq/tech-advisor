package com.springboot.backend.repository;

import com.springboot.backend.model.UserDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserDeviceRepository
        extends JpaRepository<UserDevice, Long> {

    List<UserDevice>
            findAllByUserIdAndIsCurrentTrueOrderByCreatedAtDesc(
                    Long userId
            );

    Optional<UserDevice>
            findByIdAndUserIdAndIsCurrentTrue(
                    Long id,
                    Long userId
            );

    Optional<UserDevice>
            findByIdAndIsCurrentTrue(
                    Long id
            );

    /**
     * Every owned device the recommendation pipeline can evaluate: current, linked
     * to a catalogue product, and with preferences recorded. Ordered by id so a
     * batch run visits devices in the same order every time.
     */
    @Query(value = """
            SELECT d.id
            FROM user_devices d
            JOIN device_preferences p ON p.user_device_id = d.id
            WHERE d.is_current
              AND d.product_id IS NOT NULL
            ORDER BY d.id
            """, nativeQuery = true)
    List<Long> findEvaluableDeviceIds();
}
