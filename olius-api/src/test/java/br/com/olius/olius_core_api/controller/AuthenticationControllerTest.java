package br.com.olius.olius_core_api.controller;
import br.com.olius.olius_core_api.config.SecurityProperties;
import br.com.olius.olius_core_api.security.RefreshCookies;
import br.com.olius.olius_core_api.service.AuthenticationService;
import br.com.olius.olius_core_api.service.SessionTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class AuthenticationControllerTest {
    @Test void cookieIsClearedEvenWhenDatabaseRevocationFails() {
        var auth=mock(AuthenticationService.class);
        var failure=new DataAccessResourceFailureException("unavailable");
        doThrow(failure).when(auth).logoutWeb("refresh");
        var controller=new AuthenticationController(auth,mock(SessionTransactions.class),new RefreshCookies(new SecurityProperties()));
        var response=new MockHttpServletResponse();
        assertThatThrownBy(() -> controller.webLogout("refresh",response)).isSameAs(failure);
        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0","HttpOnly");
    }
}
