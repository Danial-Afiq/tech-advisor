package com.springboot.backend.notification;

import com.springboot.backend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications", description = "In-app notifications for the authenticated user")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @Operation(
            summary = "List notifications",
            description = "Returns the authenticated user's notifications, newest first. One notification "
                    + "groups the new or changed upgrade recommendations from one evaluation of one device; "
                    + "unchanged re-assessments create none."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "One page of notifications",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = NotificationPageResponse.class))),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content)
    })
    public ResponseEntity<NotificationPageResponse> list(
            Authentication authentication,
            @Parameter(description = "Zero-based page; negative values are treated as 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1-" + NotificationService.MAX_PAGE_SIZE)
            @RequestParam(defaultValue = "10") int size) {

        return ResponseEntity.ok(notificationService.list(authentication.getName(), page, size));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Count unread notifications", description = "Drives the badge on the bell.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Unread count",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = UnreadCountResponse.class))),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content)
    })
    public ResponseEntity<UnreadCountResponse> unreadCount(Authentication authentication) {
        return ResponseEntity.ok(new UnreadCountResponse(notificationService.unreadCount(authentication.getName())));
    }

    @PutMapping("/{notificationId}/read")
    @Operation(
            summary = "Mark one notification as read",
            description = "Marking an already read notification succeeds without change."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Notification marked as read"),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "No notification with this ID belongs to the user",
                    content = @Content)
    })
    public ResponseEntity<Void> markRead(Authentication authentication, @PathVariable long notificationId) {
        notificationService.markRead(authentication.getName(), notificationId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/read-all")
    @Operation(summary = "Mark all notifications as read")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "All notifications marked as read"),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content)
    })
    public ResponseEntity<Void> markAllRead(Authentication authentication) {
        notificationService.markAllRead(authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{notificationId}")
    @Operation(
            summary = "Delete one notification",
            description = "Permanently deletes the notification. The recommendation it links to is not changed."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Notification deleted"),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "No notification with this ID belongs to the user",
                    content = @Content)
    })
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable long notificationId) {
        notificationService.delete(authentication.getName(), notificationId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @Operation(
            summary = "Clear all notifications",
            description = "Permanently deletes all of the user's notifications. Recommendations are not changed."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "All notifications deleted"),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content)
    })
    public ResponseEntity<Void> deleteAll(Authentication authentication) {
        notificationService.deleteAll(authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
