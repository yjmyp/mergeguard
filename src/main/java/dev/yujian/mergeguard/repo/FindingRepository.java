package dev.yujian.mergeguard.repo;

import dev.yujian.mergeguard.domain.Finding;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FindingRepository extends JpaRepository<Finding, Long> {
    List<Finding> findByReviewId(Long reviewId);
    long countByReviewId(Long reviewId);

    List<Finding> findByReviewIdOrderBySeverityAscLineNumberAsc(Long reviewId);

    @Query(value = "select rule_id, count(*), "
            + "sum(case when status='ACCEPTED' then 1 else 0 end), "
            + "sum(case when status='DISMISSED' then 1 else 0 end), "
            + "sum(case when status='FIXED' then 1 else 0 end), "
            + "sum(case when status='OPEN' then 1 else 0 end), avg(confidence) "
            + "from mg_finding where rule_id is not null group by rule_id", nativeQuery = true)
    List<Object[]> ruleQuality();
}
