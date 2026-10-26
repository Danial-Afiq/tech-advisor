package com.springboot.backend.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CurrencyConversionService {
    public static final String DEFAULT_CURRENCY = "SGD";
    public static final Set<String> SUPPORTED_CURRENCIES =
            Set.of("SGD", "USD", "MYR", "GBP");
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final Logger LOG =
            LoggerFactory.getLogger(CurrencyConversionService.class);

    private final FrankfurterClient client;
    private final Clock clock;
    private volatile RateSnapshot cachedRates;

    @Autowired
    public CurrencyConversionService(FrankfurterClient client) {
        this(client, Clock.systemUTC());
    }

    CurrencyConversionService(FrankfurterClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    public ConversionResult convert(
            BigDecimal amount, String sourceCurrency, String targetCurrency) {
        return convertAll(
                List.of(new Price(amount, sourceCurrency)),
                targetCurrency
        ).getFirst();
    }

    public List<ConversionResult> convertAll(
            List<Price> prices, String targetCurrency) {
        return convertAll(prices, targetCurrency, false);
    }

    public List<ConversionResult> convertAllForComparison(
            List<Price> prices, String targetCurrency) {
        return convertAll(prices, targetCurrency, true);
    }

    private List<ConversionResult> convertAll(
            List<Price> prices,
            String targetCurrency,
            boolean allowAnyProviderCurrency) {
        String target = normalise(targetCurrency);
        boolean targetSupported =
                target != null
                        && (allowAnyProviderCurrency
                        ? isIsoCode(target)
                        : SUPPORTED_CURRENCIES.contains(target));
        boolean needsRates = targetSupported && prices.stream().anyMatch(price -> {
            String source = normalise(price.currency());
            return price.amount() != null
                    && isIsoCode(source)
                    && !source.equals(target);
        });

        RatesForUse rates = needsRates ? ratesForUse() : null;
        List<ConversionResult> results = new ArrayList<>(prices.size());
        for (Price price : prices) {
            results.add(convert(price, target, targetSupported, rates));
        }
        return results;
    }

    private ConversionResult convert(
            Price price,
            String target,
            boolean targetSupported,
            RatesForUse rates) {
        String source = normalise(price.currency());
        if (price.amount() == null) {
            return new ConversionResult(
                    null, source, Status.ORIGINAL, null
            );
        }
        if (!targetSupported || !isIsoCode(source)) {
            return new ConversionResult(
                    price.amount(), source, Status.UNAVAILABLE, null
            );
        }
        if (source.equals(target)) {
            return new ConversionResult(
                    price.amount(), source, Status.ORIGINAL, null
            );
        }
        if (rates == null) {
            return new ConversionResult(
                    price.amount(), source, Status.UNAVAILABLE, null
            );
        }

        BigDecimal sourceRate = rates.snapshot().rates().get(source);
        BigDecimal targetRate = rates.snapshot().rates().get(target);
        if (sourceRate == null || targetRate == null) {
            return new ConversionResult(
                    price.amount(), source, Status.UNAVAILABLE, null
            );
        }

        BigDecimal converted = price.amount()
                .divide(sourceRate, 12, RoundingMode.HALF_UP)
                .multiply(targetRate)
                .setScale(2, RoundingMode.HALF_UP);
        return new ConversionResult(
                converted,
                target,
                rates.stale() ? Status.STALE : Status.CONVERTED,
                rates.snapshot().rateDate()
        );
    }

    private RatesForUse ratesForUse() {
        Instant now = clock.instant();
        RateSnapshot current = cachedRates;
        if (current != null
                && Duration.between(current.fetchedAt(), now).compareTo(CACHE_TTL) < 0) {
            return new RatesForUse(current, false);
        }

        synchronized (this) {
            current = cachedRates;
            if (current != null
                    && Duration.between(current.fetchedAt(), now).compareTo(CACHE_TTL) < 0) {
                return new RatesForUse(current, false);
            }

            try {
                RateSnapshot refreshed = snapshot(client.latestRates(), now);
                cachedRates = refreshed;
                return new RatesForUse(refreshed, false);
            } catch (RuntimeException exception) {
                LOG.warn(
                        "Could not refresh Frankfurter exchange rates; using fallback: {}",
                        exception.getMessage()
                );
                return current == null ? null : new RatesForUse(current, true);
            }
        }
    }

    private static RateSnapshot snapshot(
            List<FrankfurterClient.Rate> rows, Instant fetchedAt) {
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put(DEFAULT_CURRENCY, BigDecimal.ONE);
        LocalDate rateDate = null;

        for (FrankfurterClient.Rate row : rows) {
            if (row == null
                    || !DEFAULT_CURRENCY.equalsIgnoreCase(row.base())
                    || row.rate() == null
                    || row.rate().signum() <= 0) {
                continue;
            }
            String quote = normalise(row.quote());
            if (isIsoCode(quote)) {
                rates.put(quote, row.rate());
                if (row.date() != null
                        && (rateDate == null || row.date().isAfter(rateDate))) {
                    rateDate = row.date();
                }
            }
        }

        if (!rates.keySet().containsAll(SUPPORTED_CURRENCIES)) {
            throw new IllegalStateException("Frankfurter response is missing a supported currency");
        }
        return new RateSnapshot(Map.copyOf(rates), rateDate, fetchedAt);
    }

    private static String normalise(String currency) {
        return currency == null ? null : currency.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isIsoCode(String currency) {
        return currency != null && currency.matches("[A-Z]{3}");
    }

    public record Price(BigDecimal amount, String currency) {
    }

    public record ConversionResult(
            BigDecimal amount,
            String currency,
            Status status,
            LocalDate exchangeRateDate) {
    }

    public enum Status {
        ORIGINAL,
        CONVERTED,
        STALE,
        UNAVAILABLE
    }

    private record RateSnapshot(
            Map<String, BigDecimal> rates,
            LocalDate rateDate,
            Instant fetchedAt) {
    }

    private record RatesForUse(RateSnapshot snapshot, boolean stale) {
    }
}
