package com.springboot.backend.service;

import com.springboot.backend.dto.GpuCatalogueRequest;
import com.springboot.backend.dto.GpuCatalogueResponse;
import com.springboot.backend.exception.InvalidCatalogueRequestException;
import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.Gpu;
import com.springboot.backend.model.Product;
import com.springboot.backend.repository.GpuRepository;
import com.springboot.backend.repository.LatestPrice;
import com.springboot.backend.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin GPU catalogue CRUD over {@code products} + {@code gpu}, the GPU twin of
 * {@link SmartphoneCatalogueService}. Admin writes are stamped {@link #ADMIN_SOURCE}.
 */
@Service
public class GpuCatalogueService {

    /** {@code gpu.source} for values an admin entered. */
    public static final String ADMIN_SOURCE = "admin";

    private final ProductRepository products;
    private final GpuRepository gpus;
    private final CatalogueObservationService observations;

    public GpuCatalogueService(
            ProductRepository products, GpuRepository gpus, CatalogueObservationService observations) {
        this.products = products;
        this.gpus = gpus;
        this.observations = observations;
    }

    @Transactional
    public GpuCatalogueResponse create(GpuCatalogueRequest request) {
        String brand = request.brand().trim();
        String modelName = request.modelName().trim();
        ensureUnique(brand, modelName, null);

        Product product = new Product(brand, modelName, Product.CATEGORY_GPU, request.status());
        product.setReleaseDate(request.releaseDate());
        product = products.save(product);

        Gpu gpu = new Gpu(product.getId());
        applySpecs(gpu, request);
        gpu = gpus.save(gpu);

        observations.addPrices(product.getId(), request.prices());

        return withLatestPrice(new GpuCatalogueResponse(product, gpu));
    }

    @Transactional(readOnly = true)
    public List<GpuCatalogueResponse> list() {
        Map<Long, LatestPrice> prices = products.findLatestPrices(Product.CATEGORY_GPU)
                .stream()
                .collect(Collectors.toMap(LatestPrice::getProductId, Function.identity()));
        return products
                .findAllByCategoryOrderByBrandAscModelNameAsc(Product.CATEGORY_GPU)
                .stream()
                .flatMap(product -> gpus.findById(product.getId())
                        .map(gpu -> new GpuCatalogueResponse(product, gpu)
                                .withLatestPrice(prices.get(product.getId())))
                        .stream())
                .toList();
    }

    @Transactional(readOnly = true)
    public GpuCatalogueResponse get(Long id) {
        return withLatestPrice(new GpuCatalogueResponse(findGpuProduct(id), findGpu(id)));
    }

    @Transactional
    public GpuCatalogueResponse update(Long id, GpuCatalogueRequest request) {
        if (request.prices() != null && !request.prices().isEmpty()) {
            throw new InvalidCatalogueRequestException("Prices can only be added when the entry is created");
        }
        Product product = findGpuProduct(id);
        Gpu gpu = findGpu(id);
        String brand = request.brand().trim();
        String modelName = request.modelName().trim();
        ensureUnique(brand, modelName, id);

        product.setBrand(brand);
        product.setModelName(modelName);
        product.setReleaseDate(request.releaseDate());
        product.setStatus(request.status());
        applySpecs(gpu, request);

        return withLatestPrice(new GpuCatalogueResponse(product, gpu));
    }

    @Transactional
    public void delete(Long id) {
        Product product = findGpuProduct(id);
        gpus.delete(findGpu(id));
        products.delete(product);
    }

    private GpuCatalogueResponse withLatestPrice(GpuCatalogueResponse response) {
        return response.withLatestPrice(products.findLatestPrice(response.id()).orElse(null));
    }

    private Product findGpuProduct(Long id) {
        return products.findById(id)
                .filter(product -> Product.CATEGORY_GPU.equals(product.getCategory()))
                .orElseThrow(() -> new ResourceNotFoundException("GPU not found"));
    }

    private Gpu findGpu(Long productId) {
        return gpus.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("GPU specifications not found"));
    }

    private void ensureUnique(String brand, String modelName, Long currentId) {
        products.findByBrandIgnoreCaseAndModelNameIgnoreCase(brand, modelName)
                .filter(product -> !product.getId().equals(currentId))
                .ifPresent(product -> {
                    throw new IllegalArgumentException("A catalogue entry with this brand and model already exists");
                });
    }

    private void applySpecs(Gpu gpu, GpuCatalogueRequest request) {
        gpu.setBaseClockMhz(request.baseClockMhz());
        gpu.setBoostClockMhz(request.boostClockMhz());
        gpu.setVramGb(request.vramGb());
        gpu.setMemoryType(clean(request.memoryType()));
        gpu.setMemoryBusBits(request.memoryBusBits());
        gpu.setTdpWatts(request.tdpWatts());
        gpu.setArchitecture(clean(request.architecture()));
        gpu.setSource(ADMIN_SOURCE);
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
