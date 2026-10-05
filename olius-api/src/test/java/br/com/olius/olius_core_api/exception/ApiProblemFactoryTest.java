package br.com.olius.olius_core_api.exception;

import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;

class ApiProblemFactoryTest {
    @ParameterizedTest
    @CsvSource({
        "MALFORMED_REQUEST,404,Recurso não encontrado",
        "MALFORMED_REQUEST,422,Dados inconsistentes",
        "HTTP_REQUEST_ERROR,400,Erro na requisição",
        "HTTP_REQUEST_ERROR,418,I'm a teapot",
        "INTERNAL_ERROR,502,Bad Gateway",
        "HTTP_REQUEST_ERROR,499,Erro HTTP"
    })
    void titleReflectsActualHttpStatus(ApiErrorCode code, int status, String title) {
        var problem = new ApiProblemFactory().create(code, HttpStatusCode.valueOf(status),
                new MockHttpServletRequest(), List.of());
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getTitle()).isEqualTo(title);
        assertThat(problem.getProperties()).containsEntry("code", code.name());
    }
}
