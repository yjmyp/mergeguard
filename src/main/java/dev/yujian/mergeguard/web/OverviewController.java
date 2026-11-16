package dev.yujian.mergeguard.web;

import dev.yujian.mergeguard.service.ReviewService;
import dev.yujian.mergeguard.web.dto.Dtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/overview")
public class OverviewController {

    private final ReviewService reviewService;

    public OverviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public Dtos.OverviewDto overview() {
        return reviewService.overview();
    }
}
