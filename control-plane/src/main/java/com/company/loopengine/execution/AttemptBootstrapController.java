package com.company.loopengine.execution;

import com.company.loopengine.execution.AttemptService.BootstrapException;
import com.company.loopengine.execution.AttemptService.BootstrapRequest;
import com.company.loopengine.execution.AttemptService.BootstrapResult;
import com.company.loopengine.node.security.DeviceCertificateFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/node/v1")
class AttemptBootstrapController {
    private final AttemptService attempts;

    AttemptBootstrapController(AttemptService attempts) {
        this.attempts = attempts;
    }

    @PostMapping("/attempts/bootstrap")
    ResponseEntity<BootstrapResponse> bootstrap(
            @RequestAttribute(DeviceCertificateFilter.ATTR_NODE_ID) UUID authenticatedNodeId,
            @RequestBody BootstrapRequest body,
            HttpServletRequest request) {
        String callbackBaseUrl = ServletUriComponentsBuilder.fromRequest(request)
            .replacePath("")
            .build()
            .toUriString();
        BootstrapResult result = attempts.bootstrap(authenticatedNodeId, body, callbackBaseUrl);
        return ResponseEntity.ok(new BootstrapResponse(
            result.attemptId(), result.taskToken(), result.taskPackage()));
    }

    @ExceptionHandler(BootstrapException.class)
    ResponseEntity<ProblemDetail> bootstrapFailed(BootstrapException ex) {
        HttpStatus status = ex.getMessage().contains("certificate does not match reserved node")
            ? HttpStatus.FORBIDDEN
            : HttpStatus.CONFLICT;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle("Bootstrap rejected");
        return ResponseEntity.status(status).body(problem);
    }

    record BootstrapResponse(UUID attemptId, String taskToken, JsonNode taskPackage) {}
}
