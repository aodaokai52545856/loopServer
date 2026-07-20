package com.company.loopengine.execution;

import com.company.loopengine.execution.AttemptService.AttachmentChangedException;
import com.company.loopengine.execution.AttemptService.AttachmentContent;
import com.company.loopengine.execution.AttemptService.InvalidRequestException;
import com.company.loopengine.execution.AttemptService.TokenException;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/node/v1")
class AttachmentProxyController {
    private final AttemptService attempts;

    AttachmentProxyController(AttemptService attempts) {
        this.attempts = attempts;
    }

    @GetMapping("/attempts/{attemptId}/attachments/{attachmentId}")
    ResponseEntity<byte[]> download(
            @PathVariable UUID attemptId,
            @PathVariable UUID attachmentId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        String token = extractBearerToken(authorization);
        AttachmentContent content = attempts.openAttachment(token, attemptId, attachmentId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(content.filename())
                .build()
                .toString())
            .body(content.body());
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

    @ExceptionHandler(AttachmentChangedException.class)
    ResponseEntity<ProblemDetail> attachmentChanged(AttachmentChangedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Attachment changed");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
