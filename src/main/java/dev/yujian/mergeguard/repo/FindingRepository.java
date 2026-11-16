package dev.yujian.mergeguard.repo;

import dev.yujian.mergeguard.domain.Finding;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingRepository extends JpaRepository<Finding, Long> {
    List<Finding> findByReviewId(Long reviewId);
    long countByReviewId(Long reviewId);
}
