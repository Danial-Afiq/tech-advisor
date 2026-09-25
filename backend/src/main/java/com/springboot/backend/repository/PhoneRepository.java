package com.springboot.backend.repository;

import com.springboot.backend.model.Phone;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Smartphone spec sheets, keyed by product id.
 *
 * <p>{@code findById} takes the product id because {@code phone} shares the
 * products primary key.
 */
public interface PhoneRepository extends JpaRepository<Phone, Long> {}
