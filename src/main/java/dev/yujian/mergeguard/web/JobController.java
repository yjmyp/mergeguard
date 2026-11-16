package dev.yujian.mergeguard.web;

import dev.yujian.mergeguard.service.JobService;
import dev.yujian.mergeguard.web.dto.Dtos;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public List<Dtos.JobDto> list(@RequestParam(defaultValue = "50") int limit) {
        return jobService.list(limit);
    }

    @GetMapping("/stats")
    public Dtos.JobStatsDto stats() {
        return jobService.stats();
    }

    @PostMapping("/{id}/retry")
    public Dtos.JobDto retry(@PathVariable long id) {
        return toDto(jobService.retry(id));
    }

    private Dtos.JobDto toDto(dev.yujian.mergeguard.domain.ReviewJob job) {
        return new Dtos.JobDto(job.getId(), job.getRepoOwner(), job.getRepoName(), job.getPrNumber(),
                job.getHeadSha(), job.isPublish(), job.getStatus().name(), job.getAttempts(), job.getMaxAttempts(),
                job.getNextAttemptAt(), job.getLastError(), job.getReviewId(), job.getCreatedAt(),
                job.getFinishedAt());
    }
}
