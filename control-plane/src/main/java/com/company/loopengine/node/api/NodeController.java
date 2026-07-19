package com.company.loopengine.node.api;

import com.company.loopengine.node.application.NodeHeartbeatService;
import com.company.loopengine.node.application.NodeHeartbeatService.ConfirmRequest;
import com.company.loopengine.node.application.NodeHeartbeatService.DeviceAuthenticationException;
import com.company.loopengine.node.application.NodeHeartbeatService.HeartbeatRequest;
import com.company.loopengine.node.application.NodeHeartbeatService.HeartbeatResponse;
import com.company.loopengine.node.security.DeviceCertificateFilter;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/node/v1")
class NodeController {
    private final NodeHeartbeatService service;

    NodeController(NodeHeartbeatService service) {
        this.service = service;
    }

    @PostMapping("/nodes/{nodeId}/confirm")
    ResponseEntity<Void> confirm(
            @PathVariable UUID nodeId,
            @RequestAttribute(DeviceCertificateFilter.ATTR_NODE_ID) UUID authenticatedNodeId,
            @RequestBody ConfirmRequest body) {
        requireSameNode(nodeId, authenticatedNodeId);
        service.confirm(nodeId, body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/nodes/{nodeId}/heartbeat")
    ResponseEntity<HeartbeatResponse> heartbeat(
            @PathVariable UUID nodeId,
            @RequestAttribute(DeviceCertificateFilter.ATTR_NODE_ID) UUID authenticatedNodeId,
            @RequestBody HeartbeatRequest body) {
        requireSameNode(nodeId, authenticatedNodeId);
        return ResponseEntity.ok(service.heartbeat(nodeId, body));
    }

    private static void requireSameNode(UUID pathNodeId, UUID authenticatedNodeId) {
        if (!pathNodeId.equals(authenticatedNodeId)) {
            throw new DeviceAuthenticationException("certificate does not match node path");
        }
    }

    @ExceptionHandler(DeviceAuthenticationException.class)
    ResponseEntity<ProblemDetail> unauthorized(DeviceAuthenticationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Device authentication failed");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid request");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> conflict(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Invalid node state");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
