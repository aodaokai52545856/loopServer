package com.company.loopengine.project;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/projects")
class ProjectProfileController {
    private final ProjectProfileService service;

    ProjectProfileController(ProjectProfileService service) {
        this.service = service;
    }

    @PostMapping("/{projectKey}/revisions")
    ResponseEntity<?> publish(
            @PathVariable String projectKey,
            @RequestBody JsonNode body,
            @RequestHeader(value = "X-Published-By", defaultValue = "system") String publishedBy) {
        try {
            long revision = service.publish(projectKey, body, publishedBy);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("revision", revision));
        } catch (ProjectProfileService.ValidationException ex) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
            problem.setTitle("Invalid request");
            problem.setProperty("violations", ex.violations());
            return ResponseEntity.badRequest().body(problem);
        }
    }
}
