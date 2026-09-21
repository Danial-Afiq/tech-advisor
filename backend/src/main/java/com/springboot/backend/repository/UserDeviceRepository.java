package com.springboot.backend.repository;

import com.springboot.backend.model.UserDevice;
import org.springframework.data.jpa.repository.JpaRepository;

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
}