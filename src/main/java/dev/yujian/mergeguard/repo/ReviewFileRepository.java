package dev.yujian.mergeguard.repo;

import dev.yujian.mergeguard.domain.ReviewFile;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewFileRepository extends JpaRepository<ReviewFile, Long> {
    List<ReviewFile> findByReviewIdOrderByFilePathAsc(Long reviewId);
    long countByReviewId(Long reviewId);
}
