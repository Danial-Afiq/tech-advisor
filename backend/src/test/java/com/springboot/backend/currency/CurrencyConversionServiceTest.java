package com.springboot.backend.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class CurrencyConversionServiceTest {
    private static final Instant START = Instant.parse("2026-10-20T00:00:00Z");
    private final FrankfurterClient client = mock(FrankfurterClient.class);
    private final Clock clock = mock(Clock.class);

    @Test
    void convertsNonUsdPairAndRoundsToTwoDecimals() {
        when(clock.instant()).thenReturn(START);
        when(client.latestRates()).thenReturn(rates("0.7400", "3.2000", "0.5700"));
        var service = new CurrencyConversionService(client, clock);

        var result = service.convert(new BigDecimal("100.00"), "EUR", "MYR");

        assertThat(result.amount()).isEqualByComparingTo("470.59");
        assertThat(result.currency()).isEqualTo("MYR");
        assertThat(result.status()).isEqualTo(CurrencyConversionService.Status.CONVERTED);
        assertThat(result.exchangeRateDate()).isEqualTo(LocalDate.parse("2026-10-19"));
    }

    @Test
    void comparisonCanUseAProviderCurrencyOutsideTheDisplayOptions() {
        when(clock.instant()).thenReturn(START);
        when(client.latestRates()).thenReturn(rates("0.7400", "3.2000", "0.5700"));
        var service = new CurrencyConversionService(client, clock);

        var result = service.convertAllForComparison(
                List.of(new CurrencyConversionService.Price(
                        new BigDecimal("100.00"), "USD")),
                "EUR"
        ).getFirst();

        assertThat(result.amount()).isEqualByComparingTo("91.89");
        assertThat(result.currency()).isEqualTo("EUR");
        assertThat(result.status()).isEqualTo(CurrencyConversionService.Status.CONVERTED);
    }

    @Test
    void sameCurrencyReturnsOriginalWithoutFetchingRates() {
        var service = new CurrencyConversionService(client, Clock.fixed(START, ZoneOffset.UTC));

        var result = service.convert(new BigDecimal("12.345"), "SGD", "SGD");

        assertThat(result.amount()).isEqualByComparingTo("12.345");
        assertThat(result.status()).isEqualTo(CurrencyConversionService.Status.ORIGINAL);
        verify(client, never()).latestRates();
    }

    @Test
    void reusesCacheForTwentyFourHoursAndRefreshesAfterExpiry() {
        when(clock.instant())
                .thenReturn(START)
                .thenReturn(START.plusSeconds(60))
                .thenReturn(START.plusSeconds(25 * 60 * 60));
        when(client.latestRates())
                .thenReturn(rates("0.7400", "3.2000", "0.5700"))
                .thenReturn(rates("0.7500", "3.3000", "0.5800"));
        var service = new CurrencyConversionService(client, clock);

        service.convert(BigDecimal.TEN, "USD", "GBP");
        service.convert(BigDecimal.TEN, "USD", "GBP");
        var refreshed = service.convert(BigDecimal.TEN, "USD", "GBP");

        verify(client, times(2)).latestRates();
        assertThat(refreshed.amount()).isEqualByComparingTo("7.73");
    }

    @Test
    void usesExpiredRatesAsStaleWhenRefreshFails() {
        when(clock.instant())
                .thenReturn(START)
                .thenReturn(START.plusSeconds(25 * 60 * 60));
        when(client.latestRates())
                .thenReturn(rates("0.7400", "3.2000", "0.5700"))
                .thenThrow(new IllegalStateException("provider unavailable"));
        var service = new CurrencyConversionService(client, clock);

        service.convert(BigDecimal.TEN, "USD", "GBP");
        var stale = service.convert(BigDecimal.TEN, "USD", "GBP");

        assertThat(stale.amount()).isEqualByComparingTo("7.70");
        assertThat(stale.currency()).isEqualTo("GBP");
        assertThat(stale.status()).isEqualTo(CurrencyConversionService.Status.STALE);
        assertThat(stale.exchangeRateDate()).isEqualTo(LocalDate.parse("2026-10-19"));
    }

    @Test
    void keepsOriginalWhenNoRatesAreAvailable() {
        when(client.latestRates()).thenThrow(new IllegalStateException("provider unavailable"));
        var service = new CurrencyConversionService(
                client,
                Clock.fixed(START, ZoneOffset.UTC)
        );

        var result = service.convert(new BigDecimal("99.00"), "USD", "SGD");

        assertThat(result.amount()).isEqualByComparingTo("99.00");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.status()).isEqualTo(CurrencyConversionService.Status.UNAVAILABLE);
    }

    @Test
    void missingAndUnsupportedCurrenciesDoNotFetchOrInventConversions() {
        var service = new CurrencyConversionService(
                client,
                Clock.fixed(START, ZoneOffset.UTC)
        );

        var missing = service.convert(new BigDecimal("99.00"), null, "SGD");
        var unsupportedSource = service.convert(new BigDecimal("99.00"), "$", "SGD");
        var unsupportedTarget = service.convert(new BigDecimal("99.00"), "USD", "XYZ");

        assertThat(missing.status()).isEqualTo(CurrencyConversionService.Status.UNAVAILABLE);
        assertThat(missing.currency()).isNull();
        assertThat(unsupportedSource.currency()).isEqualTo("$");
        assertThat(unsupportedTarget.currency()).isEqualTo("USD");
        verify(client, never()).latestRates();
    }

    @Test
    void unknownIsoSourceFallsBackAfterRefreshingTheRateSnapshot() {
        when(client.latestRates()).thenReturn(rates("0.7400", "3.2000", "0.5700"));
        var service = new CurrencyConversionService(
                client,
                Clock.fixed(START, ZoneOffset.UTC)
        );

        var result = service.convert(new BigDecimal("99.00"), "XYZ", "SGD");

        assertThat(result.amount()).isEqualByComparingTo("99.00");
        assertThat(result.currency()).isEqualTo("XYZ");
        assertThat(result.status()).isEqualTo(CurrencyConversionService.Status.UNAVAILABLE);
    }

    private static List<FrankfurterClient.Rate> rates(
            String usd, String myr, String gbp) {
        LocalDate date = LocalDate.parse("2026-10-19");
        return List.of(
                new FrankfurterClient.Rate(date, "SGD", "SGD", BigDecimal.ONE),
                new FrankfurterClient.Rate(date, "SGD", "USD", new BigDecimal(usd)),
                new FrankfurterClient.Rate(date, "SGD", "MYR", new BigDecimal(myr)),
                new FrankfurterClient.Rate(date, "SGD", "GBP", new BigDecimal(gbp)),
                new FrankfurterClient.Rate(date, "SGD", "EUR", new BigDecimal("0.6800"))
        );
    }
}
