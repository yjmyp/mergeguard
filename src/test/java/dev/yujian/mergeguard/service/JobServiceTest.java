package dev.yujian.mergeguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.yujian.mergeguard.domain.JobStatus;
import dev.yujian.mergeguard.domain.ReviewJob;
import dev.yujian.mergeguard.repo.ReviewJobRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private ReviewJobRepository repository;

    @Test
    void enqueuesNewJob() {
        when(repository.findByDedupeKey(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        JobService service = new JobService(repository);
        ReviewJob job = service.enqueue("o", "r", 7, "sha", true);
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.isPublish()).isTrue();
        assertThat(job.getDedupeKey()).isEqualTo("o/r#7@sha");
    }

    @Test
    void keepsFinishedJobIdempotent() {
        ReviewJob done = new ReviewJob();
        done.setStatus(JobStatus.DONE);
        done.setAttempts(1);
        when(repository.findByDedupeKey(any())).thenReturn(Optional.of(done));
        JobService service = new JobService(repository);
        ReviewJob job = service.enqueue("o", "r", 7, "sha", true);
        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void requeuesFailedJobWithBudgetLeft() {
        ReviewJob failed = new ReviewJob();
        failed.setStatus(JobStatus.FAILED);
        failed.setAttempts(1);
        failed.setMaxAttempts(3);
        when(repository.findByDedupeKey(any())).thenReturn(Optional.of(failed));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        JobService service = new JobService(repository);
        ReviewJob job = service.enqueue("o", "r", 7, "sha", true);
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getNextAttemptAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void rejectsRetryWhileRunning() {
        ReviewJob running = new ReviewJob();
        running.setStatus(JobStatus.RUNNING);
        when(repository.findById(1L)).thenReturn(Optional.of(running));
        JobService service = new JobService(repository);
        assertThatThrownBy(() -> service.retry(1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }
}
