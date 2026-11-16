package dev.yujian.mergeguard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yujian.mergeguard.repo.ReviewJobRepository;
import dev.yujian.mergeguard.repo.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextTest {

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReviewJobRepository jobRepository;

    @Test
    void contextLoadsAndSchemaIsCreated() {
        assertThat(reviewRepository.count()).isGreaterThanOrEqualTo(0);
        assertThat(jobRepository.count()).isGreaterThanOrEqualTo(0);
    }
}
