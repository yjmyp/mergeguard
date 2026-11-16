package dev.yujian.mergeguard.service;

import dev.yujian.mergeguard.domain.JobStatus;
import dev.yujian.mergeguard.domain.ReviewJob;
import dev.yujian.mergeguard.repo.ReviewJobRepository;
import dev.yujian.mergeguard.web.dto.Dtos;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final ReviewJobRepository jobRepository;

    public JobService(ReviewJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public ReviewJob enqueue(String owner, String name, int prNumber, String headSha, boolean publish) {
        String key = owner + "/" + name + "#" + prNumber + "@" + headSha;
        Optional<ReviewJob> existing = jobRepository.findByDedupeKey(key);
        if (existing.isPresent()) {
            ReviewJob job = existing.get();
            if (job.getStatus() == JobStatus.FAILED && job.getAttempts() < job.getMaxAttempts()) {
                job.setStatus(JobStatus.QUEUED);
                job.setLastError(null);
                job.setNextAttemptAt(Instant.now());
                job.setLeaseUntil(null);
                job.setUpdatedAt(Instant.now());
                return jobRepository.save(job);
            }
            return job;
        }
        ReviewJob job = new ReviewJob();
        job.setDedupeKey(key);
        job.setRepoOwner(owner);
        job.setRepoName(name);
        job.setPrNumber(prNumber);
        job.setHeadSha(headSha);
        job.setPublish(publish);
        job.setStatus(JobStatus.QUEUED);
        job.setAttempts(0);
        job.setMaxAttempts(3);
        job.setNextAttemptAt(Instant.now());
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        try {
            return jobRepository.save(job);
        } catch (DataIntegrityViolationException e) {
            return jobRepository.findByDedupeKey(key).orElseThrow(() -> e);
        }
    }

    public ReviewJob require(long id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found"));
    }

    public ReviewJob retry(long id) {
        ReviewJob job = require(id);
        if (job.getStatus() == JobStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "job is running");
        }
        job.setStatus(JobStatus.QUEUED);
        job.setAttempts(0);
        job.setLastError(null);
        job.setNextAttemptAt(Instant.now());
        job.setLeaseUntil(null);
        job.setFinishedAt(null);
        job.setUpdatedAt(Instant.now());
        return jobRepository.save(job);
    }

    public List<Dtos.JobDto> list(int limit) {
        int safe = Math.min(100, Math.max(1, limit));
        return jobRepository.findAll(org.springframework.data.domain.PageRequest.of(0, safe,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")))
                .stream().map(this::toDto).toList();
    }

    public Dtos.JobStatsDto stats() {
        long queued = jobRepository.countByStatus(JobStatus.QUEUED);
        long running = jobRepository.countByStatus(JobStatus.RUNNING);
        long done = jobRepository.countByStatus(JobStatus.DONE);
        long failed = jobRepository.countByStatus(JobStatus.FAILED);
        return new Dtos.JobStatsDto(queued, running, done, failed);
    }

    private Dtos.JobDto toDto(ReviewJob job) {
        return new Dtos.JobDto(job.getId(), job.getRepoOwner(), job.getRepoName(), job.getPrNumber(),
                job.getHeadSha(), job.isPublish(), job.getStatus().name(), job.getAttempts(), job.getMaxAttempts(),
                job.getNextAttemptAt(), job.getLastError(), job.getReviewId(), job.getCreatedAt(), job.getFinishedAt());
    }
}
