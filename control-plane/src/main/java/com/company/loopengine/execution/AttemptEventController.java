package com.company.loopengine.execution;

import com.company.loopengine.execution.AttemptService.EventInput;
import com.company.loopengine.execution.AttemptService.InvalidRequestException;
import com.company.loopengine.execution.AttemptService.TokenException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/node/v1")
class AttemptEventController {
    private final AttemptService attempts;

    AttemptEventController(AttemptService attempts) {
        this.attempts = attempts;
    }

    @PostMapping("/attempts/{attemptId}/events:batch")
    ResponseEntity<AppendResponse> append(
            @PathVariable UUID attemptId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody EventBatchRequest body) {
        String token = extractBearerToken(authorization);
        List<EventInput> events = body.events().stream()
            .map(event -> new EventInput(
                UUID.fromString(event.taskId()),
                UUID.fromString(event.attemptId()),
                UUID.fromString(event.nodeId()),
                event.seq(),
                Instant.parse(event.time()),
                event.type(),
                event.payload()))
            .toList();
        long ackSeq = attempts.appendEvents(token, attemptId, events);
        return ResponseEntity.ok(new AppendResponse(ackSeq));
    }

    private static String extractBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new TokenException("task bearer token required");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            throw new TokenException("task bearer token required");
        }
        return token;
    }

    @ExceptionHandler(TokenException.class)
    ResponseEntity<ProblemDetail> unauthorized(TokenException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Task authentication failed");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ProblemDetail> badRequest(InvalidRequestException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid request");
        return ResponseEntity.badRequest().body(problem);
    }

    record EventBatchRequest(List<EventPayload> events) {}

    record EventPayload(
        String taskId,
        String attemptId,
        String nodeId,
        long seq,
        String time,
        String type,
        JsonNode payload) {}

    record AppendResponse(long ackSeq) {}
}
