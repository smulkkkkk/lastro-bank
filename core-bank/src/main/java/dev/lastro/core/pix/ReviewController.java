package dev.lastro.core.pix;

import dev.lastro.core.pix.ReviewService.Review;
import dev.lastro.core.pix.ReviewService.Verdict;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Só para quem tem o papel ANALYST; a regra fica na configuração de segurança. */
@RestController
@RequestMapping("/api/v1/backoffice/reviews")
class ReviewController {

    record DecisionRequest(@NotNull Verdict decision, @NotNull @Size(min = 5, max = 280) String reason) {
    }

    private final ReviewService reviews;

    ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    List<Review> open() {
        return reviews.open();
    }

    @PostMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request, @AuthenticationPrincipal Jwt user) {
        reviews.decide(id, UUID.fromString(user.getSubject()), request.decision(), request.reason());
    }
}
