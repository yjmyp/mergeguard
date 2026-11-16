package dev.yujian.mergeguard.web;

import dev.yujian.mergeguard.service.ReviewService;
import dev.yujian.mergeguard.web.dto.Dtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class QualityController {

    private final ReviewService reviewService;

    public QualityController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/findings/{id}/feedback")
    public void feedback(@PathVariable long id, @Valid @RequestBody Dtos.FeedbackRequest request) {
        reviewService.feedback(id, request.status(), request.note());
    }

    @GetMapping("/quality")
    public Dtos.QualityDto quality() {
        return reviewService.quality();
    }
}
