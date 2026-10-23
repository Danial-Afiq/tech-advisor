package com.springboot.backend.service;

import com.springboot.backend.dto.DashboardRecommendationResponse;
import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.User;
import com.springboot.backend.recommendation.RecommendationDismissalReason;
import com.springboot.backend.recommendation.RecommendationDismissalRepository;
import com.springboot.backend.recommendation.RecommendationDismissalSettings;
import com.springboot.backend.recommendation.RecommendationRepository;
import com.springboot.backend.recommendation.RecommendationSort;
import com.springboot.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class DashboardService {

    private final UserRepository userRepository;
    private final RecommendationRepository
            recommendationRepository;
    private final RecommendationDismissalRepository
            dismissalRepository;
    private final RecommendationDismissalSettings
            dismissalSettings;

    public DashboardService(
            UserRepository userRepository,
            RecommendationRepository recommendationRepository,
            RecommendationDismissalRepository
                    dismissalRepository,
            RecommendationDismissalSettings
                    dismissalSettings) {

        this.userRepository = userRepository;
        this.recommendationRepository =
                recommendationRepository;
        this.dismissalRepository = dismissalRepository;
        this.dismissalSettings = dismissalSettings;
    }

    @Transactional(readOnly = true)
    public List<DashboardRecommendationResponse>
            getRecommendations(
                    String email,
                    RecommendationSort sort) {

        return recommendationRepository
                .findActiveDashboardRecommendationsByUserId(
                        findUser(email).getId(),
                        sort
                );
    }

    /**
     * Stars or unstars one of the user's dashboard recommendations.
     * Setting the current value again is a no-op, not an error.
     */
    @Transactional
    public void setStarred(
            String email,
            long recommendationId,
            boolean starred) {

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

    /**
     * Dismisses one active recommendation and stores the user's
     * structured reason and optional explanation.
     */
    @Transactional
    public void dismissRecommendation(
            String email,
            long recommendationId,
            RecommendationDismissalReason reason,
            String comment) {

        String normalizedComment =
                comment == null || comment.isBlank()
                        ? null
                        : comment.trim();

        OffsetDateTime cooldownUntil =
                OffsetDateTime
                        .now(ZoneOffset.UTC)
                        .plusDays(
                                dismissalSettings.cooldownDays()
                        );

        boolean dismissed = dismissalRepository.dismiss(
                findUser(email).getId(),
                recommendationId,
                reason,
                normalizedComment,
                cooldownUntil
        );

        if (!dismissed) {
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