package br.com.olius.olius_core_api.controller;
import br.com.olius.olius_core_api.dto.IdentityRequests.*;
import br.com.olius.olius_core_api.dto.IdentityResponses.*;
import br.com.olius.olius_core_api.security.*;
import br.com.olius.olius_core_api.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/auth")
public class AuthenticationController {
    private final AuthenticationService auth; private final SessionTransactions sessions; private final RefreshCookies cookies;
    public AuthenticationController(AuthenticationService auth,SessionTransactions sessions,RefreshCookies cookies) {
        this.auth=auth; this.sessions=sessions; this.cookies=cookies;
    }
    @GetMapping("/web/csrf") public Csrf csrf(HttpServletRequest request) {
        CsrfToken token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
        return new Csrf(token.getHeaderName(),token.getToken());
    }
    @PostMapping("/web/login") public ResponseEntity<Access> webLogin(@Valid @RequestBody Login request) { return web(auth.login(request)); }
    @PostMapping("/web/refresh") public ResponseEntity<Access> webRefresh(
        @CookieValue(name=RefreshCookies.NAME,required=false) String refresh) { return web(auth.refresh(refresh)); }
    @PostMapping("/mobile/login") public MobileTokens mobileLogin(@Valid @RequestBody Login request) { return mobile(auth.login(request)); }
    @PostMapping("/mobile/refresh") public MobileTokens mobileRefresh(@Valid @RequestBody Refresh request) { return mobile(auth.refresh(request.refreshToken())); }
    @PostMapping("/web/logout") public ResponseEntity<Void> webLogout(Authentication authentication) {
        sessions.logout(Caller.id(authentication),Caller.session(authentication));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,cookies.clear()).build();
    }
    @PostMapping("/mobile/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mobileLogout(Authentication authentication) { sessions.logout(Caller.id(authentication),Caller.session(authentication)); }
    @DeleteMapping("/sessions") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(Authentication authentication) { sessions.logoutAll(Caller.id(authentication)); }
    private ResponseEntity<Access> web(AuthenticationService.Tokens tokens) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookies.issue(tokens.refresh(),tokens.refreshExpiresAt()))
            .body(tokens.access());
    }
    private MobileTokens mobile(AuthenticationService.Tokens tokens) {
        var a=tokens.access(); return new MobileTokens(a.accessToken(),a.tokenType(),a.expiresIn(),a.expiresAt(),
            tokens.refresh(),tokens.refreshExpiresAt());
    }
}
