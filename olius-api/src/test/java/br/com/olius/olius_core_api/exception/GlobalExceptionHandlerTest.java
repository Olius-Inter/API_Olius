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
                "authentication,401,AUTHENTICATION_REQUIRED", "access,403,ACCESS_DENIED",
                "concurrent,409,CONCURRENT_MODIFICATION", "optimistic,409,CONCURRENT_MODIFICATION",
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

    @Test
    void authenticationIncludesBearerChallenge() throws Exception {
        mvc.perform(get("/test/error/authentication"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void serverHttpErrorPreservesStatusAndHeaders() throws Exception {
        mvc.perform(get("/test/unavailable"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("secret"));
    }

    @Test
    void logsSafeSqlMetadataWithoutMessagesOrThrowable() throws Exception {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(get("/test/error/wrapped-sql"))
                .andExpect(status().isInternalServerError())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("23514", "SQLException", "secret"));
            assertThat(appender.list).hasSize(1);
            var event = appender.list.getFirst();
            assertThat(event.getFormattedMessage())
                .contains("exceptionType=java.lang.IllegalStateException",
                          "rootCauseType=java.sql.SQLException", "sqlState=23514")
                .doesNotContain("secret", "SELECT", "password");
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @RestController
    static class TestController {
        record Input(@NotBlank String name, @Email String email) {}

        @PostMapping(value = "/test", consumes = "application/json")
        void validate(@Valid @RequestBody Input input) {
            // Fixture: o teste avalia a validação MVC antes de executar o corpo.
        }

        @GetMapping("/test/id/{id}")
        void id(@PathVariable UUID id) {
            // Fixture: somente a conversão do parâmetro UUID está sob teste.
        }

        @GetMapping("/test/parameter")
        void parameter(@RequestParam int page) {
            // Fixture: somente a obrigatoriedade do parâmetro está sob teste.
        }

        @GetMapping("/test/unavailable")
        void unavailable() {
            var error = new org.springframework.web.ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE);
            error.getHeaders().set(HttpHeaders.RETRY_AFTER, "30");
            error.setDetail("secret");
            throw error;
        }

        @GetMapping(value = "/test/json", produces = "application/json")
        String json() { return "{}"; }

        @GetMapping("/test/error/{kind}")
        void error(@PathVariable String kind) throws SQLException {
            switch (kind) {
                case "authentication" -> throw new org.springframework.security.authentication.BadCredentialsException("secret");
                case "access" -> throw new org.springframework.security.access.AccessDeniedException("secret");
                case "concurrent" -> throw new org.springframework.dao.CannotAcquireLockException("secret");
                case "optimistic" -> throw new org.springframework.dao.OptimisticLockingFailureException("secret");
                case "wrapped-sql" -> throw new IllegalStateException("secret",
                        new SQLException("SELECT password secret", "23514"));
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
