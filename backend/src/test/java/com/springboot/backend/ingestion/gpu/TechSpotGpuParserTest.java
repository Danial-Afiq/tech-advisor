package com.springboot.backend.ingestion.gpu;

import static org.junit.jupiter.api.Assertions.*;

import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.gpu.TechSpotGpuParser.Listing;
import com.springboot.backend.ingestion.gpu.TechSpotGpuParser.ListPage;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TechSpotGpuParserTest {
    private static final URI RTX_4070 = URI.create("https://www.techspot.com/specs/gpu/267071-nvidia-geforce-rtx-4070.html");
    private static final URI GTX_1060 = URI.create("https://www.techspot.com/specs/gpu/128441-nvidia-geforce-gtx-1060-6gb.html");
    private static final URI LIST_1 = URI.create("https://www.techspot.com/specs/gpu/");

    @Test void parsesRtx4070DetailPage() throws IOException {
        Payload.GpuSpec g = TechSpotGpuParser.parseDetail(fixture("rtx-4070.html"), RTX_4070).orElseThrow();
        assertEquals("NVIDIA", g.brand());
        assertEquals("GeForce RTX 4070", g.modelName());
        assertEquals(LocalDate.of(2023, 4, 13), g.releaseDate());
        assertEquals(1920, g.baseClockMhz());
        assertEquals(2470, g.boostClockMhz());
        assertEquals(0, new BigDecimal("12").compareTo(g.vramGb()));
        assertEquals("GDDR6X", g.memoryType());
        assertEquals(192, g.memoryBusBits());
        assertEquals(200, g.tdpWatts());
        // The page also shows a current Amazon price; only "Price at Launch" may be used.
        assertEquals(0, new BigDecimal("599").compareTo(g.launchMsrpUsd()));
        assertEquals("Ada Lovelace", g.architecture());
        assertEquals(RTX_4070, g.sourceUrl());
    }

    @Test void parsesGtx1060DetailPage() throws IOException {
        Payload.GpuSpec g = TechSpotGpuParser.parseDetail(fixture("gtx-1060-6gb.html"), GTX_1060).orElseThrow();
        assertEquals("NVIDIA", g.brand());
        assertEquals("GeForce GTX 1060 6GB", g.modelName());
        assertEquals(LocalDate.of(2016, 7, 19), g.releaseDate());
        assertEquals(1500, g.baseClockMhz());
        assertEquals(1700, g.boostClockMhz());
        assertEquals(0, new BigDecimal("6").compareTo(g.vramGb()));
        assertEquals("GDDR5", g.memoryType());
        assertEquals(192, g.memoryBusBits());
        assertEquals(120, g.tdpWatts());
        assertEquals(0, new BigDecimal("299").compareTo(g.launchMsrpUsd()));
        assertEquals("Pascal", g.architecture());
    }

    @Test void nonDesktopCardIsSkipped() throws IOException {
        // Synthetic: TechSpot's GPU list has no laptop cards, so the real page is edited here.
        String html = fixture("rtx-4070.html");
        String laptop = html.replace("<td class=\"tleft\">Desktop</td>", "<td class=\"tleft\">Laptop</td>");
        assertNotEquals(html, laptop);
        assertTrue(TechSpotGpuParser.parseDetail(laptop, RTX_4070).isEmpty());
    }

    @Test void parsesListPageCardsAndPagination() throws IOException {
        ListPage page = TechSpotGpuParser.parseList(fixture("list-page-1.html"), LIST_1);
        assertEquals(4, page.lastPage());
        assertTrue(page.listings().contains(new Listing(
                URI.create("https://www.techspot.com/specs/gpu/223292-nvidia-geforce-rtx-3070.html"),
                "Nvidia GeForce RTX 3070")));
        for (Listing l : page.listings()) {
            assertEquals(TechSpotGpuParser.HOST, l.url().getHost());
            assertTrue(l.url().getPath().startsWith("/specs/gpu/"), l.url().toString());
            assertTrue(l.url().getPath().endsWith(".html"), l.url().toString());
        }
        assertEquals(page.listings().size(), new HashSet<>(page.listings().stream().map(Listing::url).toList()).size());
        assertEquals(40, page.listings().size());
    }

    @Test void listKeepsOnlyTechSpotGpuDetailUrls() {
        String html = """
                <ul><li class="pagenum active"><a href="#">1</a></li></ul>
                <div class="ProdListInfo"><h4><a href="/specs/compare/1-vs-2.html">Compare</a></h4></div>
                <div class="ProdListInfo"><h4><a href="/products/prices/x.html">Prices</a></h4></div>
                <div class="ProdListInfo"><h4><a href="https://evil.example/specs/gpu/1-x.html">Evil</a></h4></div>
                <div class="ProdListInfo"><h4><a href="/specs/gpu/2/">Page 2</a></h4></div>
                <div class="ProdListInfo"><h4><a href="/specs/gpu/5-x.html?ref=1">Query</a></h4></div>
                <div class="ProdListInfo"><h4><a href="/specs/gpu/7-amd-radeon-rx-7600.html"> AMD Radeon RX 7600 </a></h4></div>
                <div class="ProdListInfo"><h4><a href="/specs/gpu/7-amd-radeon-rx-7600.html">Duplicate</a></h4></div>
                """;
        ListPage page = TechSpotGpuParser.parseList(html, LIST_1);
        assertEquals(List.of(new Listing(URI.create("https://www.techspot.com/specs/gpu/7-amd-radeon-rx-7600.html"),
                "AMD Radeon RX 7600")), page.listings());
        assertEquals(1, page.lastPage());
    }

    @Test void parsesAlternateValueFormats() {
        Payload.GpuSpec g = detail("AMD Radeon HD 4870", row("Type", "Desktop") + row("Base Clock", "750 MHz")
                + row("VRAM Capacity", "512 MB") + row("Price at Launch", "$1,199")
                + row("TDP", "150 W") + row("Boost Clock", "fast")).orElseThrow();
        assertEquals("AMD", g.brand());
        assertEquals("Radeon HD 4870", g.modelName());
        assertEquals(750, g.baseClockMhz());
        assertNull(g.boostClockMhz());
        assertEquals(0, new BigDecimal("0.5").compareTo(g.vramGb()));
        assertEquals(0, new BigDecimal("1199").compareTo(g.launchMsrpUsd()));
        assertEquals(150, g.tdpWatts());
    }

    @Test void decimalPriceIsKeptAndNonDollarPriceIsNull() {
        assertEquals(0, new BigDecimal("449.99").compareTo(detail("Nvidia X", row("Type", "Desktop")
                + row("Price at Launch", "$449.99")).orElseThrow().launchMsrpUsd()));
        assertNull(detail("Nvidia X", row("Type", "Desktop") + row("Price at Launch", "N/A"))
                .orElseThrow().launchMsrpUsd());
    }

    @Test void missingLabelsBecomeNullsWithoutThrowing() {
        Payload.GpuSpec g = detail("Nvidia GeForce GT 710", row("Type", "desktop") + row("Release date", "sometime"))
                .orElseThrow();
        assertEquals("GeForce GT 710", g.modelName());
        assertNull(g.releaseDate());
        assertNull(g.baseClockMhz());
        assertNull(g.vramGb());
        assertNull(g.memoryType());
        assertNull(g.memoryBusBits());
        assertNull(g.tdpWatts());
        assertNull(g.launchMsrpUsd());
        assertNull(g.architecture());
    }

    @Test void unknownBrandKeepsRawFirstWordAndFullName() {
        Payload.GpuSpec g = detail("Matrox Millennium G200", row("Type", "Desktop")).orElseThrow();
        assertEquals("Matrox", g.brand());
        assertEquals("Matrox Millennium G200", g.modelName());
    }

    @Test void missingNameHeadingIsEmpty() {
        String html = "<table><tr>" + row("Type", "Desktop") + "</tr></table>";
        assertEquals(Optional.empty(), TechSpotGpuParser.parseDetail(html, RTX_4070));
    }

    private static Optional<Payload.GpuSpec> detail(String name, String rows) {
        String html = "<h1 itemprop=\"name\" class=\"large\">" + name + "</h1><table><tr>" + rows + "</tr></table>";
        return TechSpotGpuParser.parseDetail(html, RTX_4070);
    }

    private static String row(String label, String value) {
        return "<td class=\"tright\"><strong>" + label + ":</strong></td><td class=\"tleft\">" + value + "</td>";
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = TechSpotGpuParserTest.class.getResourceAsStream("/techspot/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
