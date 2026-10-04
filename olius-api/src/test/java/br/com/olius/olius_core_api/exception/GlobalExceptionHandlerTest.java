package br.com.olius.olius_core_api.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler(new ApiProblemFactory(), new PersistenceExceptionTranslator()))
                .build();
    }

    @Test
    void validationDoesNotExposeRejectedValues() throws Exception {
        mvc.perform(post("/test").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"email\":\"private-secret\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.errors.length()").value(2))
            .andExpect(jsonPath("$.errors[0].field").value("email"))
            .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("private-secret"));
    }

    @Test
    void malformedJsonIsSafe() throws Exception {
        mvc.perform(post("/test").contentType(MediaType.APPLICATION_JSON).content("{\"secret\":"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("secret"));
    }

    @Test
    void invalidUuidIsBadRequest() throws Exception {
        mvc.perform(get("/test/id/not-a-uuid"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void missingParameterIsBadRequest() throws Exception {
        mvc.perform(get("/test/parameter"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void preservesAllowHeader() throws Exception {
        mvc.perform(put("/test"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedContentIs415() throws Exception {
        mvc.perform(post("/test").contentType(MediaType.TEXT_PLAIN).content("abc"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unacceptableResponseIs406() throws Exception {
        mvc.perform(get("/test/json").accept(MediaType.APPLICATION_XML))
            .andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    void missingRouteIs404() throws Exception {
        mvc.perform(get("/missing")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @ParameterizedTest
    @CsvSource({"missing,404,RESOURCE_NOT_FOUND", "conflict,409,RESOURCE_CONFLICT",
                "state,409,BUSINESS_STATE_CONFLICT", "semantic,422,BUSINESS_RULE_VIOLATION",
                "unexpected,500,INTERNAL_ERROR", "sql,500,INTERNAL_ERROR",
                "offline,503,SERVICE_UNAVAILABLE", "http,418,HTTP_REQUEST_ERROR"})
    void exceptionsUseStableCodes(String kind, int statusCode, String code) throws Exception {
        mvc.perform(get("/test/error/" + kind).queryParam("token", "secret"))
            .andExpect(status().is(statusCode))
            .andExpect(jsonPath("$.status").value(statusCode))
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(result -> {
                var body = result.getResponse().getContentAsString();
                assertThat(body).doesNotContain("secret", "SELECT", "password", "java.lang");
                assertThat(body).contains(result.getResponse().getHeader("X-Request-Id"));
            });
    }

    @RestController
    static class TestController {
        record Input(@NotBlank String name, @Email String email) {}

        @PostMapping(value = "/test", consumes = "application/json")
        void validate(@Valid @RequestBody Input input) {}

        @GetMapping("/test/id/{id}")
        void id(@PathVariable UUID id) {}

        @GetMapping("/test/parameter")
        void parameter(@RequestParam int page) {}

        @GetMapping(value = "/test/json", produces = "application/json")
        String json() { return "{}"; }

        @GetMapping("/test/error/{kind}")
        void error(@PathVariable String kind) throws SQLException {
            switch (kind) {
                case "missing" -> throw new ResourceNotFoundException();
                case "conflict" -> throw new ResourceConflictException();
                case "state" -> throw new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT);
                case "semantic" -> throw new BusinessRuleException(BusinessRuleException.Reason.INCONSISTENT_DATA);
                case "sql" -> throw new SQLException("SELECT password secret", "23514");
                case "offline" -> throw new SQLException("password secret", "08006");
                case "http" -> throw new ResponseStatusException(HttpStatus.I_AM_A_TEAPOT, "secret");
                default -> throw new IllegalStateException("secret");
            }
        }
    }
}
