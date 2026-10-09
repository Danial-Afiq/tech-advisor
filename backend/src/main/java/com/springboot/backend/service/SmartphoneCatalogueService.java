package com.springboot.backend.service;

import com.springboot.backend.dto.SmartphoneCatalogueRequest;
import com.springboot.backend.dto.SmartphoneCatalogueResponse;
import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.Phone;
import com.springboot.backend.model.Product;
import com.springboot.backend.repository.PhoneRepository;
import com.springboot.backend.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SmartphoneCatalogueService {

    private final ProductRepository products;
    private final PhoneRepository phones;

    public SmartphoneCatalogueService(ProductRepository products, PhoneRepository phones) {
        this.products = products;
        this.phones = phones;
    }

    @Transactional
    public SmartphoneCatalogueResponse create(SmartphoneCatalogueRequest request) {
        String brand = request.brand().trim();
        String modelName = request.modelName().trim();
        ensureUnique(brand, modelName, null);

        Product product = new Product(
                brand,
                modelName,
                Product.CATEGORY_SMARTPHONE,
                request.status()
        );
        product.setReleaseDate(request.releaseDate());
        product = products.save(product);

        Phone phone = Phone.builder(product.getId()).build();
        applySpecs(phone, request);
        phone = phones.save(phone);

        return new SmartphoneCatalogueResponse(product, phone);
    }

    @Transactional(readOnly = true)
    public List<SmartphoneCatalogueResponse> list() {
        return products
                .findAllByCategoryOrderByBrandAscModelNameAsc(Product.CATEGORY_SMARTPHONE)
                .stream()
                .flatMap(product -> phones.findById(product.getId())
                        .map(phone -> new SmartphoneCatalogueResponse(product, phone))
                        .stream())
                .toList();
    }

    @Transactional(readOnly = true)
    public SmartphoneCatalogueResponse get(Long id) {
        Product product = findSmartphone(id);
        return new SmartphoneCatalogueResponse(product, findPhone(id));
    }

    @Transactional
    public SmartphoneCatalogueResponse update(Long id, SmartphoneCatalogueRequest request) {
        Product product = findSmartphone(id);
        Phone phone = findPhone(id);
        String brand = request.brand().trim();
        String modelName = request.modelName().trim();
        ensureUnique(brand, modelName, id);

        product.setBrand(brand);
        product.setModelName(modelName);
        product.setReleaseDate(request.releaseDate());
        product.setStatus(request.status());
        applySpecs(phone, request);

        return new SmartphoneCatalogueResponse(product, phone);
    }

    @Transactional
    public void delete(Long id) {
        Product product = findSmartphone(id);
        phones.delete(findPhone(id));
        products.delete(product);
    }

    private Product findSmartphone(Long id) {
        return products.findById(id)
                .filter(product -> Product.CATEGORY_SMARTPHONE.equals(product.getCategory()))
                .orElseThrow(() -> new ResourceNotFoundException("Smartphone not found"));
    }

    private Phone findPhone(Long productId) {
        return phones.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Smartphone specifications not found"));
    }

    private void ensureUnique(String brand, String modelName, Long currentId) {
        products.findByBrandIgnoreCaseAndModelNameIgnoreCase(brand, modelName)
                .filter(product -> !product.getId().equals(currentId))
                .ifPresent(product -> {
                    throw new IllegalArgumentException("A catalogue entry with this brand and model already exists");
                });
    }

    private void applySpecs(Phone phone, SmartphoneCatalogueRequest request) {
        phone.setChipset(clean(request.chipset()));
        phone.setRamGb(request.ramGb());
        phone.setCpuGhz(request.cpuGhz());
        phone.setStorageGb(request.storageGb());
        phone.setBatteryMah(request.batteryMah());
        phone.setWiredChargingWatts(request.wiredChargingWatts());
        phone.setWirelessChargingWatts(request.wirelessChargingWatts());
        phone.setDisplaySizeInches(request.displaySizeInches());
        phone.setRefreshRateHz(request.refreshRateHz());
        phone.setWeightG(request.weightG());
        phone.setCameraSpecs(clean(request.cameraSpecs()));
        phone.setPixelDensity(request.pixelDensity());
        phone.setIpRating(clean(request.ipRating()));
        phone.setOs(clean(request.os()));
        phone.setSoftwareSupportYears(request.softwareSupportYears());
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
