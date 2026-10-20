package com.springboot.backend.controller;

import com.springboot.backend.config.OpenApiConfig;
import com.springboot.backend.dto.DashboardRecommendationResponse;
import com.springboot.backend.service.DashboardService;
import com.springboot.backend.recommendation.RecommendationSort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@Tag(
        name = "Dashboard",
        description =
                "Recommendation overview for "
                        + "the authenticated user"
)
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(
            DashboardService dashboardService) {

        this.dashboardService = dashboardService;
    }

    @GetMapping("/recommendations")
    @Operation(
            summary =
                    "List active dashboard recommendations",
            description =
                    "Returns active recommendations "
                            + "for the authenticated user's "
                            + "current registered devices."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description =
                            "Dashboard recommendations",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(
                                    schema = @Schema(
                                            implementation =
                                                    DashboardRecommendationResponse.class
                                    )
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description =
                            "Bearer token is missing or invalid",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "400",
                    description =
                            "Unknown sort value",
                    content = @Content
            )
    })
    public ResponseEntity<
            List<DashboardRecommendationResponse>>
            getRecommendations(
                    Authentication authentication,
                    @Parameter(
                            description =
                                    "Sort order. Defaults to "
                                            + "relevance (verdict tier, "
                                            + "then upgrade score). "
                                            + "Items without a price "
                                            + "sort last in both "
                                            + "price orders.",
                            schema = @Schema(
                                    allowableValues = {
                                            "relevance",
                                            "price_asc",
                                            "price_desc",
                                            "recency"
                                    },
                                    defaultValue = "relevance"
                            )
                    )
                    @RequestParam(required = false)
                    String sort,
                    @Parameter(
                            description = "Currency used for displayed prices",
                            schema = @Schema(
                                    allowableValues = {"SGD", "USD", "MYR", "GBP"},
                                    defaultValue = "SGD"
                            )
                    )
                    @RequestParam(defaultValue = "SGD")
                    String currency) {

        List<DashboardRecommendationResponse>
                recommendations =
                dashboardService.getRecommendations(
                        authentication.getName(),
                        RecommendationSort.parse(sort),
                        currency
                );

        return ResponseEntity.ok(recommendations);
    }

    @PutMapping("/recommendations/{recommendationId}/star")
    @Operation(
            summary = "Star a dashboard recommendation",
            description =
                    "Saves one of the authenticated user's "
                            + "active recommendations. Starring an "
                            + "already starred recommendation "
                            + "succeeds without change."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "Recommendation starred"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description =
                            "Bearer token is missing or invalid",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "404",
                    description =
                            "No active recommendation with this "
                                    + "ID belongs to the user",
                    content = @Content
            )
    })
    public ResponseEntity<Void> starRecommendation(
            Authentication authentication,
            @PathVariable long recommendationId) {

        dashboardService.setStarred(
                authentication.getName(),
                recommendationId,
                true
        );

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/recommendations/{recommendationId}/star")
    @Operation(
            summary = "Unstar a dashboard recommendation",
            description =
                    "Removes one of the authenticated user's "
                            + "active recommendations from their "
                            + "saved recommendations. The "
                            + "recommendation itself is kept."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "Recommendation unstarred"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description =
                            "Bearer token is missing or invalid",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "404",
                    description =
                            "No active recommendation with this "
                                    + "ID belongs to the user",
                    content = @Content
            )
    })
    public ResponseEntity<Void> unstarRecommendation(
            Authentication authentication,
            @PathVariable long recommendationId) {

        dashboardService.setStarred(
                authentication.getName(),
                recommendationId,
                false
        );

        return ResponseEntity.noContent().build();
    }
}
