package dev.yujian.mergeguard.service;

import dev.yujian.mergeguard.config.MergeGuardProperties;
import dev.yujian.mergeguard.domain.JobStatus;
import dev.yujian.mergeguard.domain.ReviewJob;
import dev.yujian.mergeguard.repo.ReviewJobRepository;
import dev.yujian.mergeguard.web.dto.Dtos;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final ReviewJobRepository jobRepository;
    private final ReviewService reviewService;
    private final MergeGuardProperties properties;
    private final ThreadPoolTaskExecutor jobExecutor;

    public JobWorker(ReviewJobRepository jobRepository, ReviewService reviewService,
                     MergeGuardProperties properties,
                     @Qualifier("jobExecutor") ThreadPoolTaskExecutor jobExecutor) {
        this.jobRepository = jobRepository;
        this.reviewService = reviewService;
        this.properties = properties;
        this.jobExecutor = jobExecutor;
    }

    @Scheduled(fixedDelayString = "${mergeguard.job.poll-ms:2000}", initialDelayString = "3000")
    public void tick() {
        if (!properties.getJob().isWorkerEnabled()) {
            return;
        }
        Instant now = Instant.now();
        recoverExpired(now);
        List<ReviewJob> candidates = jobRepository
                .findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(JobStatus.QUEUED, now);
        for (ReviewJob job : candidates) {
            Instant lease = now.plusSeconds(properties.getJob().getLeaseSeconds());
            if (jobRepository.claim(job.getId(), lease, now) != 1) {
                continue;
            }
            job.setAttempts(job.getAttempts() + 1);
            try {
                jobExecutor.execute(() -> run(job));
            } catch (Exception rejected) {
                job.setStatus(JobStatus.QUEUED);
                job.setNextAttemptAt(Instant.now().plusSeconds(10));
                job.setLeaseUntil(null);
                job.setLastError("worker pool saturated");
                jobRepository.save(job);
            }
        }
    }

    private void recoverExpired(Instant now) {
        for (ReviewJob job : jobRepository.findByStatusAndLeaseUntilBefore(JobStatus.RUNNING, now)) {
            int recovered = jobRepository.recoverExpired(job.getId(), now, "worker lease expired; recovered");
            if (recovered == 1) {
                log.warn("recovered expired job {} for {}/{}#{}", job.getId(), job.getRepoOwner(),
                        job.getRepoName(), job.getPrNumber());
            }
        }
    }

    private void run(ReviewJob job) {
        long started = System.currentTimeMillis();
        try {
            Dtos.ReviewDetailDto detail = reviewService.executeForJob(job);
            job.setStatus(JobStatus.DONE);
            job.setReviewId(detail.id());
            job.setLastError(null);
            job.setLeaseUntil(null);
            job.setFinishedAt(Instant.now());
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
            log.info("job {} done review={} in {} ms", job.getId(), detail.id(),
                    System.currentTimeMillis() - started);
        } catch (Exception e) {
            fail(job, e);
        }
    }

    private void fail(ReviewJob job, Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        boolean lockConflict = e instanceof ResponseStatusException status
                && status.getStatusCode() == HttpStatus.CONFLICT;
        if (job.getAttempts() >= job.getMaxAttempts()) {
            job.setStatus(JobStatus.FAILED);
            job.setLeaseUntil(null);
            job.setFinishedAt(Instant.now());
        } else {
            job.setStatus(JobStatus.QUEUED);
            long delay = lockConflict ? 30 : backoffSeconds(job.getAttempts());
            if (lockConflict) {
                job.setAttempts(Math.max(0, job.getAttempts() - 1));
            }
            job.setNextAttemptAt(Instant.now().plusSeconds(delay));
        }
        job.setLastError(message);
        job.setLeaseUntil(null);
        job.setUpdatedAt(Instant.now());
        jobRepository.save(job);
        log.warn("job {} attempt {}/{} failed ({}); retry in {}s", job.getId(), job.getAttempts(),
                job.getMaxAttempts(), message, lockConflict ? 30 : backoffSeconds(job.getAttempts()));
    }

    private long backoffSeconds(int attempts) {
        return Math.min(300, 5L * (1L << Math.min(6, Math.max(0, attempts - 1))));
    }
}
