package com.springboot.backend.ingestion.searchapi;

import static com.springboot.backend.ingestion.core.IngestionFailure.Code.*;
import static org.junit.jupiter.api.Assertions.*;

import com.springboot.backend.ingestion.core.IngestionFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Ticket 1.3: GPU listings are partner boards, so GPUs get their own listing vocabulary. */
class ProductMatcherGpuTests {
    final JsonMapper json = JsonMapper.builder().build();

    @ParameterizedTest @ValueSource(strings = {"NVIDIA GeForce RTX 4070",
            "MSI GeForce RTX 4070 Ventus 2X 12G OC",
            "ASUS TUF Gaming GeForce RTX 4070 OC Edition 12GB GDDR6X Graphics Card",
            "GIGABYTE GeForce RTX 4070 WINDFORCE OC 12G",
            "ZOTAC Gaming GeForce RTX 4070 Twin Edge 12GB GDDR6X 192-bit"})
    void acceptsPartnerBoardsOfTheSameChip(String title) {
        assertTrue(ProductMatcher.accepts("GPU", "NVIDIA", "GeForce RTX 4070", title), title);
    }

    @ParameterizedTest @ValueSource(strings = {"MSI GeForce RTX 4070 Ti Ventus 3X",
            "ASUS Dual GeForce RTX 4070 SUPER OC 12GB", "GeForce RTX 4060 Gaming OC 8G",
            "EK Waterblock for GeForce RTX 4070", "GeForce RTX 4070 Laptop GPU",
            "GeForce RTX 4070 Riser Cable", "GeForce RTX 4070 Used", "Gaming PC RTX 4070 Ryzen 7"})
    void rejectsOtherTiersAndAccessories(String title) {
        assertFalse(ProductMatcher.accepts("GPU", "NVIDIA", "GeForce RTX 4070", title), title);
    }

    @Test void aMemoryVariantInTheCatalogueNameMustAppearButMaySitAnywhere() {
        assertTrue(ProductMatcher.accepts("GPU", "NVIDIA", "GeForce RTX 3060 12GB",
                "MSI GeForce RTX 3060 Ventus 2X 12G OC"));
        assertTrue(ProductMatcher.accepts("GPU", "Intel", "Arc A770 16GB", "Intel Arc A770 Limited Edition 16 GB"));
        assertFalse(ProductMatcher.accepts("GPU", "NVIDIA", "GeForce RTX 3060 12GB", "GeForce RTX 3060 8GB Gaming"));
        assertFalse(ProductMatcher.accepts("GPU", "NVIDIA", "GeForce RTX 3060 12GB", "GeForce RTX 3060 Gaming"));
    }

    @Test void theChipVendorIsOptionalButAMDAndIntelCardsMatchToo() {
        assertTrue(ProductMatcher.accepts("GPU", "AMD", "Radeon RX 7800 XT",
                "Sapphire Pulse AMD Radeon RX 7800 XT Gaming OC 16GB GDDR6"));
        assertFalse(ProductMatcher.accepts("GPU", "AMD", "Radeon RX 7800 XT", "PowerColor Radeon RX 7900 XT Hellhound"));
    }

    @Test void phoneRulesStillRejectGpuSuffixes() {
        assertFalse(ProductMatcher.accepts("Apple", "iPhone 16 Pro", "Apple iPhone 16 Pro Gaming OC"));
        assertFalse(ProductMatcher.accepts("SMARTPHONE", "Apple", "iPhone 16 Pro", "iPhone 16 Pro"),
                "phones still need the brand in the title");
    }

    @Test void equallySpecificBoardsPickTheFirstListingInsteadOfFailingAsAmbiguous() {
        var boards = json.readTree("""
                [{"title":"MSI GeForce RTX 4070 Ventus 2X","product_id":"msi","product_token":"a"},
                 {"title":"ZOTAC GeForce RTX 4070 Twin Edge","product_id":"zotac","product_token":"b"},
                 {"title":"GeForce RTX 4070 Ti","product_id":"ti","product_token":"c"}]
                """);
        assertEquals("msi", ProductMatcher.choose("GPU", "NVIDIA", "GeForce RTX 4070", boards).externalId());
        assertEquals(SEARCHAPI_NO_MATCH, assertThrows(IngestionFailure.class,
                () -> ProductMatcher.choose("GPU", "NVIDIA", "GeForce RTX 4070", json.readTree("[]"))).code());
    }
}
