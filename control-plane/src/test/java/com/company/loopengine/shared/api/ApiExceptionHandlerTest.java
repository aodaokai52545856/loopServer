package com.company.loopengine.shared.api;

import com.company.loopengine.shared.web.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ApiExceptionHandlerTest.TestErrorController.class)
@Import({ApiExceptionHandler.class, CorrelationIdFilter.class, ApiExceptionHandlerTest.TestErrorController.class})
class ApiExceptionHandlerTest {
    @Autowired MockMvc mvc;

    @Test
    void returnsProblemDetailWithRequestId() throws Exception {
        mvc.perform(get("/test/error").header("X-Request-Id", "req-123"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Request-Id", "req-123"))
            .andExpect(jsonPath("$.title").value("Invalid request"))
            .andExpect(jsonPath("$.requestId").value("req-123"));
    }

    @RestController static class TestErrorController {
        @GetMapping("/test/error") void fail() { throw new InvalidRequestException("bad input"); }
    }
}
