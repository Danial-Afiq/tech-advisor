package com.springboot.backend.repository;

import com.springboot.backend.model.Gpu;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Graphics card spec sheets, keyed by product id.
 *
 * <p>{@code findById} takes the product id because {@code gpu} shares the
 * products primary key.
 */
public interface GpuRepository extends JpaRepository<Gpu, Long> {}
