package com.springboot.backend.dto;

import com.springboot.backend.model.Product;
import com.springboot.backend.model.UserDevice;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DeviceResponseTest {

    @Test
    void linkedDeviceCarriesItsProductCategory() {
        Product phone = new Product("Samsung", "Galaxy S22", "SMARTPHONE", "VERIFIED");
        DeviceResponse response = new DeviceResponse(new UserDevice(1L, phone, null), null);

        assertEquals("SMARTPHONE", response.getProductCategory());
        assertEquals("Samsung", response.getProductBrand());
    }

    @Test
    void unlinkedDeviceHasNoProductCategory() {
        DeviceResponse response = new DeviceResponse(new UserDevice(1L, null, "My laptop"), null);

        assertNull(response.getProductCategory());
        assertEquals("My laptop", response.getCustomName());
    }
}
