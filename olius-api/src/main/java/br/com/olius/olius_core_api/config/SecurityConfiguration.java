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
import org.springframework.security.web.SecurityFilterChain;
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
        ApiProblemFactory factory,PersistenceExceptionTranslator translator,ObjectMapper mapper,AuthRateLimiter limiter) throws Exception {
        var csrf=new CookieCsrfTokenRepository();
        csrf.setCookiePath("/api/v1/auth/web");
        csrf.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(properties.isCookieSecure())
            .sameSite(properties.getCookieSameSite()));
        if(!List.of("Strict","Lax","None").contains(properties.getCookieSameSite())
            || ("None".equals(properties.getCookieSameSite()) && !properties.isCookieSecure()))
            throw new IllegalStateException("Configuração de cookie inválida");
        http.cors(cors -> cors.configurationSource(cors(properties)))
            .csrf(config -> config.csrfTokenRepository(csrf)
                .requireCsrfProtectionMatcher(request -> PathPatternRequestMatcher.withDefaults().matcher("/api/v1/auth/web/**").matches(request)
                    && !List.of("GET","HEAD","OPTIONS").contains(request.getMethod())))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable()).formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(entry).accessDeniedHandler(denied))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.POST,"/api/v1/auth/registrations/citizens","/api/v1/auth/registrations/establishments",
                    "/api/v1/auth/web/login","/api/v1/auth/web/refresh","/api/v1/auth/mobile/login","/api/v1/auth/mobile/refresh").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/auth/web/csrf","/api/v1/establishment-types",
                    "/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint(entry).accessDeniedHandler(denied))
            .addFilterBefore(new DatabaseSecurityFailureFilter(factory,translator,mapper),BearerTokenAuthenticationFilter.class)
            .addFilterBefore(new AuthRateLimitFilter(limiter,factory,mapper),BearerTokenAuthenticationFilter.class);
        return http.build();
    }
    private CorsConfigurationSource cors(SecurityProperties properties) {
        var config=new CorsConfiguration(); config.setAllowedOrigins(properties.getAllowedOrigins());
        config.setAllowCredentials(true); config.setAllowedMethods(List.of("GET","POST","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization","Content-Type","X-XSRF-TOKEN"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        var source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**",config); return source;
    }
}
