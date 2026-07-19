package com.company.loopengine.shared.api;

import com.company.loopengine.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ProblemDetail> invalid(InvalidRequestException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid request");
        problem.setProperty("requestId", request.getAttribute(CorrelationIdFilter.HEADER));
        return ResponseEntity.badRequest().body(problem);
    }
}
