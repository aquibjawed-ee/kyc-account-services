package com.example.kyc.verification.web;

import com.example.kyc.verification.review.ReviewService;
import com.example.kyc.verification.review.ReviewService.ReviewView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Review records", description = "Read-only view of the verification side; all real work arrives over Kafka")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @Operation(summary = "Fetch the review record for an application",
            description = "Shows the review status (MANUAL_REVIEW_STARTED, MANUAL_REVIEW_PASSED or "
                    + "MANUAL_REVIEW_FAILED), the stored verdictDueAt that makes the simulated delay "
                    + "survive a restart, and verdictPublishedAt once released. The planned outcome is "
                    + "deliberately never exposed, so a caller cannot see a verdict before it is due. A "
                    + "record stuck at MANUAL_REVIEW_STARTED past its due time is one of the deliberate "
                    + "no-reply cases the creation service will flag as an SLA breach.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Review found"),
            @ApiResponse(responseCode = "404", description = "REVIEW_NOT_FOUND: no request consumed for "
                    + "this application, or it was dead-lettered before a review was created")})
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
