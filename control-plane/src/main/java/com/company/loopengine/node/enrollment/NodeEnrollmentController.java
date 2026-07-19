package com.company.loopengine.node.enrollment;

import com.company.loopengine.node.enrollment.NodeEnrollmentService.Enrollment;
import com.company.loopengine.node.enrollment.NodeEnrollmentService.EnrollmentRequest;
import com.company.loopengine.node.enrollment.NodeEnrollmentService.EnrollmentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/nodes")
class NodeEnrollmentController {
    private final NodeEnrollmentService service;

    NodeEnrollmentController(NodeEnrollmentService service) {
        this.service = service;
    }

    @PostMapping("/join")
    ResponseEntity<EnrollmentResponse> join(@RequestBody JoinRequest body) {
        Enrollment enrollment = service.enroll(
            body.code(), new EnrollmentRequest(body.name(), body.csrPem()));
        return ResponseEntity.status(HttpStatus.CREATED).body(EnrollmentResponse.from(enrollment));
    }

    @ExceptionHandler(InviteAlreadyUsedException.class)
    ResponseEntity<ProblemDetail> alreadyUsed(InviteAlreadyUsedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Invite already used");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(InviteExpiredException.class)
    ResponseEntity<ProblemDetail> expired(InviteExpiredException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.GONE, ex.getMessage());
        problem.setTitle("Invite expired");
        return ResponseEntity.status(HttpStatus.GONE).body(problem);
    }

    @ExceptionHandler(CertificateSigningException.class)
    ResponseEntity<ProblemDetail> signingFailed(CertificateSigningException ex) {
        ProblemDetail problem =
            ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        problem.setTitle("Certificate signing failed");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid request");
        return ResponseEntity.badRequest().body(problem);
    }

    record JoinRequest(String code, String name, String csrPem) {}
}
