package dev.yujian.mergeguard.repo;

import dev.yujian.mergeguard.domain.RepoConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepoConfigRepository extends JpaRepository<RepoConfig, Long> {
    List<RepoConfig> findAllByOrderByFullNameAsc();
    List<RepoConfig> findByEnabledTrue();
    Optional<RepoConfig> findByOwnerAndName(String owner, String name);
    boolean existsByOwnerAndName(String owner, String name);
}
