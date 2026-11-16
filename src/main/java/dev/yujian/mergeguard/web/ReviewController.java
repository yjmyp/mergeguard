package dev.yujian.mergeguard.web;

import dev.yujian.mergeguard.service.ReviewService;
import dev.yujian.mergeguard.service.SarifExporter;
import dev.yujian.mergeguard.domain.Review;
import dev.yujian.mergeguard.repo.FindingRepository;
import dev.yujian.mergeguard.repo.ReviewRepository;
import dev.yujian.mergeguard.web.dto.Dtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;
    private final ReviewRepository reviewRepository;
    private final FindingRepository findingRepository;
    private final SarifExporter sarifExporter;

    public ReviewController(ReviewService reviewService, ReviewRepository reviewRepository,
                            FindingRepository findingRepository, SarifExporter sarifExporter) {
        this.reviewService = reviewService;
        this.reviewRepository = reviewRepository;
        this.findingRepository = findingRepository;
        this.sarifExporter = sarifExporter;
    }

    @GetMapping
    public Dtos.PageDto<Dtos.ReviewSummaryDto> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String verdict,
            @RequestParam(required = false) String repo) {
        return reviewService.list(page, size, verdict, repo);
    }

    @GetMapping("/{id}")
    public Dtos.ReviewDetailDto detail(@PathVariable long id) {
        return reviewService.getDetail(id);
    }

    @GetMapping("/{id}/diff")
    public Dtos.DiffDto diff(@PathVariable long id) {
        return reviewService.diff(id);
    }

    @GetMapping(value = "/{id}/sarif", produces = "application/sarif+json")
    public ResponseEntity<java.util.Map<String, Object>> sarif(@PathVariable long id) {
        Review review = reviewRepository.findById(id).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "review not found"));
        return ResponseEntity.ok(sarifExporter.export(review, findingRepository.findByReviewId(id)));
    }

    @PostMapping
    public Dtos.ReviewDetailDto trigger(@Valid @RequestBody Dtos.TriggerRequest request) {
        return reviewService.trigger(request.owner(), request.name(), request.prNumber(), request.publish());
    }
}
