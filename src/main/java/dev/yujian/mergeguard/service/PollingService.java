package dev.yujian.mergeguard.service;

import dev.yujian.mergeguard.config.MergeGuardProperties;
import dev.yujian.mergeguard.domain.RepoConfig;
import dev.yujian.mergeguard.github.GhPullRequest;
import dev.yujian.mergeguard.repo.RepoConfigRepository;
import dev.yujian.mergeguard.repo.ReviewRepository;
import dev.yujian.mergeguard.web.dto.Dtos;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PollingService {

    private static final Logger log = LoggerFactory.getLogger(PollingService.class);

    private final RepoConfigRepository repoConfigRepository;
    private final ReviewRepository reviewRepository;
    private final dev.yujian.mergeguard.github.GitHubClient githubClient;
    private final JobService jobService;
    private final MergeGuardProperties properties;
    private final StringRedisTemplate redisTemplate;

    public PollingService(RepoConfigRepository repoConfigRepository, ReviewRepository reviewRepository,
                          dev.yujian.mergeguard.github.GitHubClient githubClient, JobService jobService,
                          MergeGuardProperties properties, StringRedisTemplate redisTemplate) {
        this.repoConfigRepository = repoConfigRepository;
        this.reviewRepository = reviewRepository;
        this.githubClient = githubClient;
        this.jobService = jobService;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    @Scheduled(fixedDelayString = "${mergeguard.poll.interval-ms:300000}")
    public void scheduledPoll() {
        if (!properties.getPoll().isEnabled() || !githubClient.hasToken()) {
            return;
        }
        for (RepoConfig repo : repoConfigRepository.findByEnabledTrue()) {
            try {
                scan(repo, true);
            } catch (Exception e) {
                log.warn("poll failed for {}: {}", repo.getFullName(), e.getMessage());
            }
        }
    }

    public Dtos.ScanResult scan(RepoConfig repo, boolean publish) {
        if (!githubClient.hasToken()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "github token not configured");
        }
        String lockKey = "mergeguard:poll:" + repo.getFullName();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofMinutes(3));
        if (Boolean.FALSE.equals(locked)) {
            return new Dtos.ScanResult(0, 0);
        }
        try {
            List<GhPullRequest> pulls = githubClient.listOpenPulls(repo.getOwner(), repo.getName());
            int open = pulls == null ? 0 : pulls.size();
            int queued = 0;
            if (pulls != null && repo.isAutoReview()) {
                for (GhPullRequest pr : pulls) {
                    if (pr.head() == null || pr.head().sha() == null) {
                        continue;
                    }
                    if (reviewRepository.existsByRepoOwnerAndRepoNameAndPrNumberAndHeadSha(
                            repo.getOwner(), repo.getName(), (int) pr.number(), pr.head().sha())) {
                        continue;
                    }
                    jobService.enqueue(repo.getOwner(), repo.getName(), (int) pr.number(), pr.head().sha(), publish);
                    queued++;
                }
            }
            return new Dtos.ScanResult(queued, open);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }
}
