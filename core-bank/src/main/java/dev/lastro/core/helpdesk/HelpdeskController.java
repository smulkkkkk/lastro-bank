package dev.lastro.core.helpdesk;

import dev.lastro.core.helpdesk.HelpdeskService.Conversation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/help")
class HelpdeskController {

    record MessageRequest(@NotBlank @Size(max = 1000) String text) {
    }

    /** DDD e número, só dígitos: 11987654321. */
    record HandoffRequest(@NotBlank @Pattern(regexp = "[1-9][0-9]{9,10}") String phone) {
    }

    private final HelpdeskService helpdesk;

    HelpdeskController(HelpdeskService helpdesk) {
        this.helpdesk = helpdesk;
    }

    @GetMapping("/conversation")
    Conversation conversation(@AuthenticationPrincipal Jwt user) {
        return helpdesk.current(UUID.fromString(user.getSubject()));
    }

    @PostMapping("/messages")
    Conversation send(@Valid @RequestBody MessageRequest request, @AuthenticationPrincipal Jwt user) {
        return helpdesk.send(UUID.fromString(user.getSubject()), request.text());
    }

    @PostMapping("/handoff")
    Conversation handoff(@Valid @RequestBody HandoffRequest request, @AuthenticationPrincipal Jwt user) {
        return helpdesk.handoff(UUID.fromString(user.getSubject()), request.phone());
    }
}
