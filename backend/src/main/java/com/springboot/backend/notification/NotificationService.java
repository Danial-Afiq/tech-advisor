package com.springboot.backend.notification;

import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.User;
import com.springboot.backend.recommendation.RecommendationsPersisted;
import com.springboot.backend.recommendation.RecommendationsPersisted.NotableRecommendation;
import com.springboot.backend.recommendation.classification.TierMapper;
import com.springboot.backend.repository.UserRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-app notifications (ticket 4.4.2): creates one notification per evaluation
 * of a device that produced new or changed upgrade options, and serves the
 * signed-in user's list, unread count and read state.
 *
 * <p>Creation never affects recommendations. It runs after the recommendation
 * transaction has committed, in its own transaction, and any failure is caught
 * and written to {@code system_log} instead of propagating.
 */
@Service
public class NotificationService {
    public static final String TYPE_NEW_RECOMMENDATION = "NEW_RECOMMENDATION";
    public static final String LOG_COMPONENT = "NOTIFICATIONS";
    public static final int MAX_PAGE_SIZE = 50;

    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

    /** Best first, so a group links to its strongest recommendation. */
    private static final List<String> VERDICT_ORDER = List.of(
            TierMapper.STRONG_UPGRADE_CANDIDATE, TierMapper.WORTH_CONSIDERING, TierMapper.WORTH_WATCHING);

    private static final Map<String, String> VERDICT_PHRASES = Map.of(
            TierMapper.STRONG_UPGRADE_CANDIDATE, "a strong upgrade candidate",
            TierMapper.WORTH_CONSIDERING, "worth considering",
            TierMapper.WORTH_WATCHING, "worth watching");

    private final NotificationRepository notifications;
    private final UserRepository users;
    private final TransactionTemplate requiresNew;
    private final int maxPerUser;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public NotificationService(
            NotificationRepository notifications,
            UserRepository users,
            PlatformTransactionManager manager,
            @Value("${notifications.max-per-user:10}") int maxPerUser) {
        if (maxPerUser < 1) {
            throw new IllegalStateException("notifications.max-per-user must be at least 1");
        }
        this.notifications = notifications;
        this.users = users;
        this.maxPerUser = maxPerUser;
        this.requiresNew = new TransactionTemplate(manager);
        // AFTER_COMMIT runs while the committed transaction is still bound; writes need their own.
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecommendationsPersisted(RecommendationsPersisted event) {
        if (event.recommendations().isEmpty()) return;

        try {
            requiresNew.executeWithoutResult(status -> create(event));
        } catch (RuntimeException failure) {
            recordFailure(event, failure);
        }
    }

    private void create(RecommendationsPersisted event) {
        List<NotableRecommendation> best = event.recommendations().stream()
                .sorted(Comparator.comparingInt(notable -> verdictRank(notable.verdict())))
                .toList();
        NotableRecommendation top = best.getFirst();

        String device = event.currentDeviceId() == null
                ? "your device"
                : notifications.deviceName(event.currentDeviceId()).map(name -> "your " + name).orElse("your device");

        String title;
        String message;
        if (best.size() == 1) {
            String product = notifications.productName(top.candidateProductId()).orElse("A new phone");
            title = "New upgrade recommendation!";
            message = product + " is " + VERDICT_PHRASES.getOrDefault(top.verdict(), "worth a look")
                    + " for " + device + ".";
        } else {
            title = "New upgrade recommendations!";
            message = "We found " + best.size() + " new or updated upgrade options for " + device
                    + ". See what's worth upgrading to.";
        }

        notifications.insert(event.userId(), TYPE_NEW_RECOMMENDATION, title, message, top.recommendationId());
        // Only the newest few are kept, so the bell never piles up.
        notifications.trimToNewest(event.userId(), maxPerUser);
    }

    private void recordFailure(RecommendationsPersisted event, RuntimeException failure) {
        LOG.warn("Could not create a notification for user {} device {}; recommendations are unaffected",
                event.userId(), event.currentDeviceId(), failure);
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("user_id", event.userId());
            metadata.put("current_device_id", event.currentDeviceId());
            metadata.put("recommendation_ids",
                    event.recommendations().stream().map(NotableRecommendation::recommendationId).toList());
            metadata.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            notifications.logFailure("Notification creation failed", json.writeValueAsString(metadata));
        } catch (RuntimeException logFailure) {
            LOG.error("Could not write the notification failure to system_log either", logFailure);
        }
    }

    private static int verdictRank(String verdict) {
        int rank = VERDICT_ORDER.indexOf(verdict);
        return rank < 0 ? VERDICT_ORDER.size() : rank;
    }

    /** Page and size are clamped (page >= 0, size 1-50) rather than rejected. */
    @Transactional(readOnly = true)
    public NotificationPageResponse list(String email, int page, int size) {
        long userId = findUser(email).getId();
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        long total = notifications.count(userId);

        return new NotificationPageResponse(
                notifications.findPage(userId, safePage * safeSize, safeSize),
                safePage,
                safeSize,
                total,
                (int) ((total + safeSize - 1) / safeSize));
    }

    @Transactional(readOnly = true)
    public long unreadCount(String email) {
        return notifications.countUnread(findUser(email).getId());
    }

    /** Marking an already read notification is a no-op, not an error. */
    @Transactional
    public void markRead(String email, long notificationId) {
        if (!notifications.markRead(findUser(email).getId(), notificationId)) {
            throw new ResourceNotFoundException("Notification not found");
        }
    }

    @Transactional
    public void markAllRead(String email) {
        notifications.markAllRead(findUser(email).getId());
    }

    private User findUser(String email) {
        return users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
