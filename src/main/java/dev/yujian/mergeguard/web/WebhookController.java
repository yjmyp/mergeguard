package dev.yujian.mergeguard.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.yujian.mergeguard.config.MergeGuardProperties;
import dev.yujian.mergeguard.domain.RepoConfig;
import dev.yujian.mergeguard.domain.WebhookDelivery;
import dev.yujian.mergeguard.repo.RepoConfigRepository;
import dev.yujian.mergeguard.repo.WebhookDeliveryRepository;
import dev.yujian.mergeguard.service.Hmac;
import dev.yujian.mergeguard.service.JobService;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final Set<String> ACTIONS = Set.of("opened", "synchronize", "reopened", "ready_for_review");

    private final MergeGuardProperties properties;
    private final WebhookDeliveryRepository deliveryRepository;
    private final RepoConfigRepository repoConfigRepository;
    private final JobService jobService;
    private final ObjectMapper objectMapper;

    public WebhookController(MergeGuardProperties properties, WebhookDeliveryRepository deliveryRepository,
                             RepoConfigRepository repoConfigRepository, JobService jobService,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.deliveryRepository = deliveryRepository;
        this.repoConfigRepository = repoConfigRepository;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/github")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestBody String payload) {
        String secret = properties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "webhook_secret_not_configured"));
        }
        if (!Hmac.verify(secret, payload, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_signature"));
        }
        if (deliveryId == null || deliveryId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_delivery_id"));
        }
        if (deliveryRepository.existsById(deliveryId)) {
            return ResponseEntity.ok(Map.of("status", "duplicate_ignored"));
        }
        try {
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setDeliveryId(deliveryId);
            delivery.setEvent(event);
            delivery.setReceivedAt(Instant.now());
            deliveryRepository.save(delivery);
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.ok(Map.of("status", "duplicate_ignored"));
        }

        if (!"pull_request".equals(event)) {
            return ResponseEntity.accepted().body(Map.of("status", "ignored_event"));
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            String action = root.path("action").asText("");
            if (!ACTIONS.contains(action)) {
                return ResponseEntity.accepted().body(Map.of("status", "ignored_action"));
            }
            JsonNode repo = root.path("repository");
            String fullName = repo.path("full_name").asText("");
            String[] parts = fullName.split("/", 2);
            if (parts.length != 2) {
                return ResponseEntity.badRequest().body(Map.of("error", "invalid_repository"));
            }
            RepoConfig config = repoConfigRepository.findByOwnerAndName(parts[0], parts[1])
                    .orElseGet(RepoConfig::new);
            config.setId(repo.path("id").asLong());
            config.setOwner(parts[0]);
            config.setName(parts[1]);
            config.setFullName(fullName);
            config.setEnabled(true);
            config.setAutoReview(true);
            repoConfigRepository.save(config);

            JsonNode pr = root.path("pull_request");
            int number = pr.path("number").asInt();
            String headSha = pr.path("head").path("sha").asText("");
            if (number > 0 && !headSha.isBlank()) {
                jobService.enqueue(parts[0], parts[1], number, headSha, true);
            }
            return ResponseEntity.accepted().body(Map.of("status", "queued", "pull_request", number));
        } catch (Exception e) {
            log.warn("cannot process webhook: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_payload"));
        }
    }
}
