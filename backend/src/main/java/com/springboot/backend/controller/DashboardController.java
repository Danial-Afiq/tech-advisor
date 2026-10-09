package com.springboot.backend.controller;

import com.springboot.backend.config.OpenApiConfig;
import com.springboot.backend.dto.DashboardRecommendationResponse;
import com.springboot.backend.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
            )
    })
    public ResponseEntity<
            List<DashboardRecommendationResponse>>
            getRecommendations(
                    Authentication authentication) {

        List<DashboardRecommendationResponse>
                recommendations =
                dashboardService.getRecommendations(
                        authentication.getName()
                );

        return ResponseEntity.ok(recommendations);
    }
}