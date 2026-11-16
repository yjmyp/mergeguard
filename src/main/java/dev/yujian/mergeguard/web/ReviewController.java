package dev.yujian.mergeguard.web;

import dev.yujian.mergeguard.service.ReviewService;
import dev.yujian.mergeguard.web.dto.Dtos;
import jakarta.validation.Valid;
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

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
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

    @PostMapping
    public Dtos.ReviewDetailDto trigger(@Valid @RequestBody Dtos.TriggerRequest request) {
        return reviewService.trigger(request.owner(), request.name(), request.prNumber(), request.publish());
    }
}
