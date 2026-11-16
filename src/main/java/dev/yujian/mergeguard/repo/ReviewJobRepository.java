package dev.yujian.mergeguard.repo;

import dev.yujian.mergeguard.domain.JobStatus;
import dev.yujian.mergeguard.domain.ReviewJob;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ReviewJobRepository extends JpaRepository<ReviewJob, Long> {

    Optional<ReviewJob> findByDedupeKey(String dedupeKey);

    List<ReviewJob> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(JobStatus status, Instant now);

    List<ReviewJob> findByStatusAndLeaseUntilBefore(JobStatus status, Instant now);

    long countByStatus(JobStatus status);

    @Transactional
    @Modifying
    @Query(value = "update mg_job set status='RUNNING', attempts=attempts+1, lease_until=:lease, "
            + "updated_at=:now where id=:id and status='QUEUED' and next_attempt_at<=:now", nativeQuery = true)
    int claim(@Param("id") long id, @Param("lease") Instant lease, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query(value = "update mg_job set status='QUEUED', lease_until=null, updated_at=:now, "
            + "last_error=:error where id=:id and status='RUNNING' and lease_until<:now", nativeQuery = true)
    int recoverExpired(@Param("id") long id, @Param("now") Instant now, @Param("error") String error);
}
