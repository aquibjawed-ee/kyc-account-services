package com.example.kyc.verification.web;

import com.example.kyc.verification.review.ReviewService;
import com.example.kyc.verification.review.ReviewService.ReviewView;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only lookup; the planned outcome is deliberately never exposed. */
@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/{applicationId}")
    public ResponseEntity<?> get(@PathVariable UUID applicationId) {
        return reviews.find(applicationId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> {
                    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                            "Review for application " + applicationId + " not found");
                    pd.setProperty("code", "REVIEW_NOT_FOUND");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
                });
    }
}
