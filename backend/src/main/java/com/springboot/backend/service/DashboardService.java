package com.springboot.backend.service;

import com.springboot.backend.dto.DashboardRecommendationResponse;
import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.User;
import com.springboot.backend.recommendation.RecommendationRepository;
import com.springboot.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DashboardService {

    private final UserRepository userRepository;
    private final RecommendationRepository
            recommendationRepository;

    public DashboardService(
            UserRepository userRepository,
            RecommendationRepository
                    recommendationRepository) {

        this.userRepository = userRepository;
        this.recommendationRepository =
                recommendationRepository;
    }

    @Transactional(readOnly = true)
    public List<DashboardRecommendationResponse>
            getRecommendations(String email) {

        User user = userRepository
                .findByEmailIgnoreCase(email)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "User not found"
                        )
                );

        return recommendationRepository
                .findActiveDashboardRecommendationsByUserId(
                        user.getId()
                );
    }
}