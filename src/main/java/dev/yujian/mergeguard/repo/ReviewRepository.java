package dev.yujian.mergeguard.repo;

import dev.yujian.mergeguard.domain.Review;
import dev.yujian.mergeguard.domain.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByRepoOwnerAndRepoNameAndPrNumberAndHeadSha(
            String repoOwner, String repoName, int prNumber, String headSha);

    boolean existsByRepoOwnerAndRepoNameAndPrNumberAndHeadSha(
            String repoOwner, String repoName, int prNumber, String headSha);

    List<Review> findTop10ByOrderByCreatedAtDesc();

    Page<Review> findByVerdict(Verdict verdict, Pageable pageable);

    Page<Review> findByRepoOwnerAndRepoName(String repoOwner, String repoName, Pageable pageable);

    Page<Review> findByVerdictAndRepoOwnerAndRepoName(Verdict verdict, String repoOwner, String repoName,
                                                      Pageable pageable);

    long countByVerdict(Verdict verdict);

    long countByInputTokensGreaterThan(int value);

    @Query("select avg(r.durationMs) from Review r")
    Double averageDurationMs();

    @Query("select sum(r.inputTokens + r.outputTokens) from Review r")
    Long totalTokens();

    @Query("select r.verdict, count(r) from Review r group by r.verdict")
    List<Object[]> verdictCounts();

    List<Review> findByCreatedAtAfterOrderByCreatedAtAsc(Instant since);
}
