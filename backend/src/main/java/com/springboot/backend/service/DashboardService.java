package com.springboot.backend.service;

import com.springboot.backend.currency.CurrencyConversionService;
import com.springboot.backend.currency.CurrencyConversionService.Price;
import com.springboot.backend.dto.DashboardRecommendationResponse;
import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.User;
import com.springboot.backend.recommendation.RecommendationRepository;
import com.springboot.backend.recommendation.RecommendationSort;
import com.springboot.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class DashboardService {

    private final UserRepository userRepository;
    private final RecommendationRepository
            recommendationRepository;
    private final CurrencyConversionService currencyConversionService;

    public DashboardService(
            UserRepository userRepository,
            RecommendationRepository
                    recommendationRepository,
            CurrencyConversionService currencyConversionService) {

        this.userRepository = userRepository;
        this.recommendationRepository =
                recommendationRepository;
        this.currencyConversionService = currencyConversionService;
    }

    @Transactional(readOnly = true)
    public List<DashboardRecommendationResponse>
            getRecommendations(
                    String email,
                    RecommendationSort sort,
                    String currency) {

        List<DashboardRecommendationResponse> original = recommendationRepository
                .findActiveDashboardRecommendationsByUserId(
                        findUser(email).getId(),
                        sort
                );

        var conversions = currencyConversionService.convertAll(
                original.stream()
                        .map(row -> new Price(row.latestPrice(), row.currency()))
                        .toList(),
                currency
        );
        List<DashboardRecommendationResponse> converted =
                new ArrayList<>(original.size());
        for (int index = 0; index < original.size(); index++) {
            var conversion = conversions.get(index);
            converted.add(original.get(index).withDisplayedPrice(
                    conversion.amount(),
                    conversion.currency(),
                    conversion.status().name(),
                    conversion.exchangeRateDate()
            ));
        }

        Comparator<BigDecimal> amounts = sort == RecommendationSort.PRICE_DESC
                ? Comparator.nullsLast(Comparator.reverseOrder())
                : Comparator.nullsLast(Comparator.naturalOrder());
        if (sort == RecommendationSort.PRICE_ASC
                || sort == RecommendationSort.PRICE_DESC) {
            converted.sort(Comparator.comparing(
                    DashboardRecommendationResponse::latestPrice,
                    amounts
            ));
        }
        return converted;
    }

    /**
     * Stars or unstars one of the user's dashboard recommendations.
     * Setting the current value again is a no-op, not an error.
     */
    @Transactional
    public void setStarred(
            String email, long recommendationId, boolean starred) {

        boolean updated = recommendationRepository.setStarred(
                findUser(email).getId(),
                recommendationId,
                starred
        );

        if (!updated) {
            throw new ResourceNotFoundException(
                    "Recommendation not found"
            );
        }
    }

    private User findUser(String email) {
        return userRepository
                .findByEmailIgnoreCase(email)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "User not found"
                        )
                );
    }
}
