package br.com.olius.olius_core_api.config;
import br.com.olius.olius_core_api.exception.*;
import br.com.olius.olius_core_api.security.*;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.web.cors.*;
import tools.jackson.databind.ObjectMapper;

@Configuration @EnableMethodSecurity @ConditionalOnWebApplication
public class SecurityConfiguration {
    @Bean public AuthRateLimiter authRateLimiter(SecurityProperties properties) {
        return new AuthRateLimiter(properties.getAuthRequestsPerMinute(),10000,java.time.Clock.systemUTC());
    }
    @Bean public SecurityFilterChain security(HttpSecurity http,SecurityProperties properties,
        SessionJwtConverter converter,ProblemAuthenticationEntryPoint entry,ProblemAccessDeniedHandler denied,
        ApiProblemFactory factory,PersistenceExceptionTranslator translator,ObjectMapper mapper,AuthRateLimiter limiter,JwtDecoder decoder) throws Exception {
        var csrf=new CookieCsrfTokenRepository();
        csrf.setCookiePath("/api/v1/auth/web");
        csrf.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(properties.isCookieSecure())
            .sameSite(properties.getCookieSameSite()));
        if(!List.of("Strict","Lax","None").contains(properties.getCookieSameSite())
            || ("None".equals(properties.getCookieSameSite()) && !properties.isCookieSecure()))
            throw new IllegalStateException("Configuração de cookie inválida");
        var provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        var bearer = new BearerTokenAuthenticationFilter(new ProviderManager(provider));
        var resolver=new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver();
        var webLogout=PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST,"/api/v1/auth/web/logout");
        // This operation authenticates only by refresh cookie + CSRF, including expired access tokens.
        bearer.setBearerTokenResolver(request -> webLogout.matches(request) ? null : resolver.resolve(request));
        bearer.setAuthenticationEntryPoint(entry);
        bearer.setAuthenticationFailureHandler(new AuthenticationEntryPointFailureHandler(entry));
        // Register the standard JWT filter explicitly: resource-server DSL adds an
        // automatic CSRF exemption for all Bearer requests, including web logout.
        http.cors(cors -> cors.configurationSource(cors(properties)))
            .csrf(config -> browserCsrf(config, csrf))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable()).formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(entry).accessDeniedHandler(denied))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.POST,"/api/v1/webhooks/stripe").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/auth/registrations/citizens","/api/v1/auth/registrations/establishments",
                    "/api/v1/auth/web/login","/api/v1/auth/web/refresh","/api/v1/auth/web/logout","/api/v1/auth/mobile/login","/api/v1/auth/mobile/refresh").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/auth/web/csrf","/api/v1/establishment-types","/api/v1/subscription-plans",
                    "/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").permitAll()
                .requestMatchers("/api/v1/billing/**").hasRole("ESTABLISHMENT")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .addFilterAt(bearer, BearerTokenAuthenticationFilter.class)
            .addFilterBefore(new DatabaseSecurityFailureFilter(factory,translator,mapper),BearerTokenAuthenticationFilter.class)
            .addFilterBefore(new AuthRateLimitFilter(limiter,factory,mapper),BearerTokenAuthenticationFilter.class);
        return http.build();
    }
    /**
     * Cookie-authenticated web operations always require CSRF. Other operations do
     * not consume authentication cookies: they require an explicit Bearer token,
     * credentials or a refresh secret in JSON. HTTP sessions/basic auth are disabled.
     * S4502 flags any custom CSRF matcher; this narrowly reviewed policy is covered
     * by real filter-chain tests, including encoded paths and missing/invalid CSRF.
     */
    @SuppressWarnings("java:S4502")
    private void browserCsrf(CsrfConfigurer<HttpSecurity> config, CookieCsrfTokenRepository repository) {
        var web = PathPatternRequestMatcher.withDefaults().matcher("/api/v1/auth/web/**");
        config.csrfTokenRepository(repository)
            .requireCsrfProtectionMatcher(request -> web.matches(request)
                && !List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod()));
    }

    private CorsConfigurationSource cors(SecurityProperties properties) {
        var config=new CorsConfiguration(); config.setAllowedOrigins(properties.getAllowedOrigins());
        config.setAllowCredentials(true); config.setAllowedMethods(List.of("GET","POST","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization","Content-Type","X-XSRF-TOKEN","Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        var source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**",config); return source;
    }
}
