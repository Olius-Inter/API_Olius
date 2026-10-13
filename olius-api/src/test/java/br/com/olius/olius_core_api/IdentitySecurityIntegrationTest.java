package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.config.SecurityProperties;
import br.com.olius.olius_core_api.dto.IdentityRequests.AdminRegistration;
import br.com.olius.olius_core_api.service.FirstAdminService;
import br.com.olius.olius_core_api.testfixture.OfficialPostgres;

import java.time.Instant;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@Testcontainers @AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.import=", "olius.security.auth-requests-per-minute=10000", "olius.security.ephemeral-keys=true",
    "olius.security.allowed-origins=https://frontend.test", "springdoc.api-docs.enabled=true"})
class IdentitySecurityIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",() -> OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",() -> OfficialPostgres.API_PASSWORD);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired SecurityProperties properties;
    @Autowired FirstAdminService firstAdmin;
    private static final String PASSWORD="Password1!";

    @Test void citizenRegistrationLoginAndOwnerProfile() throws Exception {
        var account=citizen();
        var tokens=login(account.email());
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+tokens.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(account.id().toString()))
            .andExpect(jsonPath("$.userType").value("CITIZENS")).andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.cpf").exists()).andExpect(jsonPath("$.qrToken").exists())
            .andExpect(jsonPath("$.passwordHash").doesNotExist());
        var hash=admin().queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,account.id());
        assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);
        assertThat(admin().queryForObject("SELECT count(*) FROM user_qr_code WHERE user_id=?",Integer.class,account.id())).isEqualTo(1);
        assertThat(admin().queryForObject("SELECT after_snapshot FROM (SELECT to_jsonb(l)::text AS after_snapshot FROM users_log l WHERE id=?) s LIMIT 1",String.class,account.id()))
            .doesNotContain(hash,PASSWORD);
        var jwt=decoder.decode(tokens.access());
        assertThat(jwt.getExpiresAt().getEpochSecond()-jwt.getIssuedAt().getEpochSecond()).isEqualTo(900);
        mvc.perform(get("/api/v1/auth/session").header("Authorization","Bearer "+tokens.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(account.id().toString()))
            .andExpect(jsonPath("$.userType").value("CITIZENS")).andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test void establishmentRegistrationCreatesIndependentAddressAndLinks() throws Exception {
        UUID type=UUID.randomUUID();
        admin().update("INSERT INTO establishment_type(id,name) VALUES (?,?)",type,"Tipo-"+type);
        var request=new LinkedHashMap<String,Object>();
        String email=email(); request.put("name","Estabelecimento"); request.put("email",email);
        request.put("password",PASSWORD); request.put("cnpj",digits(14)); request.put("typeId",type);
        request.put("address",Map.of("state","SP","city","Cidade","neighborhood","Bairro","street","Rua",
            "number","1","cep","00000000")); request.put("telephones",List.of("11999999999"));
        var response=mvc.perform(post("/api/v1/auth/registrations/establishments").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request))).andExpect(status().isCreated()).andReturn();
        UUID id=UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("id").asText());
        String token=login(email).access();
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cnpj").value(request.get("cnpj")))
            .andExpect(jsonPath("$.address.street").value("Rua"))
            .andExpect(jsonPath("$.establishmentTypeId").value(type.toString()));
        assertThat(admin().queryForObject("SELECT count(*) FROM establishment e JOIN addresses a ON a.id=e.address_id JOIN user_qr_code q ON q.user_id=e.id WHERE e.id=? AND a.owner_kind='ESTABLISHMENT' AND q.qr_token=e.qr_token",Integer.class,id)).isEqualTo(1);
        mvc.perform(get("/api/v1/establishment-types")).andExpect(status().isOk());
    }

    @Test void duplicateCpfAndTelephoneRollBackWholeRegistrationIncludingAudit() throws Exception {
        var existing=citizen();
        String cpf=admin().queryForObject("SELECT cpf FROM citizens WHERE id=?",String.class,existing.id());
        String rejected=email(); long before=admin().queryForObject("SELECT count(*) FROM users_log",Long.class);
        mvc.perform(post("/api/v1/auth/registrations/citizens").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(citizenRequest(rejected,cpf))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REGISTRATION_CONFLICT"));
        assertThat(admin().queryForObject("SELECT count(*) FROM users WHERE email=?",Integer.class,rejected)).isZero();
        assertThat(admin().queryForObject("SELECT count(*) FROM users_log",Long.class)).isEqualTo(before);
        var request=citizenRequest(rejected,digits(11)); request.put("telephones",List.of("11999999999","11999999999"));
        mvc.perform(post("/api/v1/auth/registrations/citizens").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_TELEPHONE"));
        assertThat(admin().queryForObject("SELECT count(*) FROM users WHERE email=?",Integer.class,rejected)).isZero();
    }

    @Test void emailLocalPartRemainsCaseSensitiveAndDomainIsNormalized() throws Exception {
        String local="Case"+UUID.randomUUID();
        register(citizenRequest(local+"@EXAMPLE.TEST",digits(11)),201);
        register(citizenRequest(local.toLowerCase(Locale.ROOT)+"@example.test",digits(11)),201);
        register(citizenRequest(local+"@example.test",digits(11)),409);
        assertThat(login(local+"@EXAMPLE.TEST").access()).isNotBlank();
    }

    @Test void clientCannotChooseAdminStatusOrPointsAndPasswordPolicyIsEnforced() throws Exception {
        for(String field:List.of("userType","status","points")) {
            var request=citizenRequest(email(),digits(11)); request.put(field,"ADMIN"); register(request,400);
        }
        for(String password:List.of("Short1!","password12!","Password12 ","Password!!", "A1!"+"a".repeat(126))) {
            var request=citizenRequest(email(),digits(11)); request.put("password",password); register(request,400);
        }
        var valid=citizenRequest(email(),digits(11)); valid.put("password","ABCDEFGHI1!"); register(valid,201);
    }

    @Test void invalidCredentialsAndTokensReturnSafe401() throws Exception {
        var account=citizen();
        for(String email:List.of(account.email(),email())) {
            mvc.perform(post("/api/v1/auth/mobile/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email",email,"password","wrong"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer invalid"))
            .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        var tokens=login(account.email()); var original=decoder.decode(tokens.access());
        for(String bad:List.of(signed(original,"wrong",properties.getAudience(),Instant.now().plusSeconds(900)),
            signed(original,properties.getIssuer(),"wrong",Instant.now().plusSeconds(900)),
            signed(original,properties.getIssuer(),properties.getAudience(),Instant.now().minusSeconds(1)))) {
            mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+bad)).andExpect(status().isUnauthorized());
        }
    }

    @Test void userCannotReadAnotherProfileOrUseAdminOperations() throws Exception {
        var first=citizen(); var second=citizen(); String token=login(first.email()).access();
        mvc.perform(get("/api/v1/users/"+second.id()).header("Authorization","Bearer "+token))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/users").header("Authorization","Bearer "+token)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name","Admin","email",email(),"password",PASSWORD))))
            .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/users/"+second.id()+"/status").header("Authorization","Bearer "+token)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("status","INACTIVE"))))
            .andExpect(status().isForbidden());
    }

    @Test void refreshReuseThroughHttpCommitsRevocationAndInvalidatesBothAccessTokens() throws Exception {
        var account=citizen(); var original=login(account.email());
        var result=mvc.perform(post("/api/v1/auth/mobile/refresh").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("refreshToken",original.refresh())))).andExpect(status().isOk()).andReturn();
        var body=json.readTree(result.getResponse().getContentAsString()); String successor=body.get("accessToken").asText();
        assertThat(body.get("refreshToken").asText()).isNotEqualTo(original.refresh());
        mvc.perform(post("/api/v1/auth/mobile/refresh").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("refreshToken",original.refresh())))).andExpect(status().isUnauthorized());
        for(String token:List.of(original.access(),successor))
            mvc.perform(get("/api/v1/auth/session").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        assertThat(admin().queryForObject("SELECT revocation_reason::text FROM auth_session WHERE id=?",String.class,
            UUID.fromString(decoder.decode(original.access()).getClaimAsString("sid")))).isEqualTo("REFRESH_REUSE");
    }

    @Test void logoutAllRevokesIndependentLogins() throws Exception {
        var account=citizen(); var first=login(account.email()); var second=login(account.email());
        mvc.perform(delete("/api/v1/auth/sessions").header("Authorization","Bearer "+first.access()))
            .andExpect(status().isNoContent());
        for(String token:List.of(first.access(),second.access()))
            mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }

    @Test void adminInactivationRevokesSessionsAndReactivationRequiresNewLogin() throws Exception {
        var account=citizen(); var admin=administrator(); String adminToken=login(admin.email()).access();
        var userToken=login(account.email());
        mvc.perform(get("/api/v1/users/"+account.id()).header("Authorization","Bearer "+adminToken)).andExpect(status().isOk());
        for(String state:List.of("INACTIVE","ACTIVE")) {
            mvc.perform(patch("/api/v1/admin/users/"+account.id()+"/status").header("Authorization","Bearer "+adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("status",state))))
                .andExpect(status().isNoContent());
            mvc.perform(get("/api/v1/auth/session").header("Authorization","Bearer "+userToken.access()))
                .andExpect(status().isUnauthorized());
            if(state.equals("INACTIVE")) {
                mvc.perform(post("/api/v1/auth/mobile/login").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("email",account.email(),"password",PASSWORD))))
                    .andExpect(status().isUnauthorized());
            }
        }
        assertThat(login(account.email()).access()).isNotBlank();
    }

    @Test void webLoginRequiresRealCsrfAndRefreshNeverAppearsInJson() throws Exception {
        var account=citizen(); String credentials=json.writeValueAsString(Map.of("email",account.email(),"password",PASSWORD));
        mvc.perform(post("/api/v1/auth/web/login").contentType(MediaType.APPLICATION_JSON).content(credentials))
            .andExpect(status().isForbidden());
        mvc.perform(post(java.net.URI.create("/api/v1/auth/%77eb/login")).contentType(MediaType.APPLICATION_JSON).content(credentials))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(400,403));
        var csrf=mvc.perform(get("/api/v1/auth/web/csrf")).andExpect(status().isOk()).andReturn();
        Cookie csrfCookie=csrf.getResponse().getCookie("XSRF-TOKEN"); assertThat(csrfCookie).isNotNull();
        String csrfToken=json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
        var logged=mvc.perform(post("/api/v1/auth/web/login").cookie(csrfCookie).header("X-XSRF-TOKEN",csrfToken)
            .contentType(MediaType.APPLICATION_JSON).content(credentials)).andExpect(status().isOk())
            .andExpect(jsonPath("$.refreshToken").doesNotExist()).andReturn();
        String refreshHeader=logged.getResponse().getHeader("Set-Cookie");
        assertThat(refreshHeader).contains("HttpOnly","Secure","SameSite=Strict","Path=/api/v1/auth/web");
        Cookie refresh=logged.getResponse().getCookie("olius_refresh"); assertThat(refresh).isNotNull();
        mvc.perform(post("/api/v1/auth/web/refresh").cookie(refresh)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/web/refresh").cookie(refresh,csrfCookie).header("X-XSRF-TOKEN",csrfToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test void administrativeProvisioningRequiresActiveAdminAndLogoutClosesOnlyCurrentSession() throws Exception {
        var administrator=administrator(); String adminToken=login(administrator.email()).access();
        String createdEmail=email();
        mvc.perform(post("/api/v1/admin/users").header("Authorization","Bearer "+adminToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "name","Novo administrador","email",createdEmail,"password",PASSWORD))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.userType").value("ADMIN"));
        String createdToken=login(createdEmail).access();
        mvc.perform(get("/api/v1/auth/session").header("Authorization","Bearer "+createdToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.userType").value("ADMIN"));
        var account=citizen(); var first=login(account.email()); var second=login(account.email());
        mvc.perform(post("/api/v1/auth/mobile/logout").header("Authorization","Bearer "+first.access()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+first.access())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+second.access())).andExpect(status().isOk());
    }

    @Test void invalidRefreshCannotOpenASession() throws Exception {
        mvc.perform(post("/api/v1/auth/mobile/refresh").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("refreshToken","A".repeat(43)))))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/mobile/refresh").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("refreshToken","bad"))))
            .andExpect(status().isBadRequest());
    }

    @Test void corsAcceptsOnlyConfiguredOrigins() throws Exception {
        mvc.perform(options("/api/v1/auth/web/login").header("Origin","https://frontend.test")
            .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","Content-Type,X-XSRF-TOKEN"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","https://frontend.test"));
        mvc.perform(options("/api/v1/auth/web/login").header("Origin","https://evil.test")
            .header("Access-Control-Request-Method","POST")).andExpect(status().isForbidden());
    }

    @Test void openApiDocumentsSecurityAndRequests() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"))
            .andExpect(jsonPath("$.paths['/api/v1/auth/mobile/login'].post.requestBody").exists());
    }

    @Test @org.junit.jupiter.api.Order(1) void bootstrapProvisionsFirstAndRefusesAnother() throws Exception {
        String initialEmail=email();
        UUID initial=firstAdmin.provision(new AdminRegistration("Inicial",initialEmail,PASSWORD));
        assertThat(initial).isNotNull(); assertThat(login(initialEmail).access()).isNotBlank();
        assertThatThrownBy(() -> firstAdmin.provision(new AdminRegistration("Admin",email(),PASSWORD)))
            .isInstanceOf(IllegalStateException.class);
    }

    private Account citizen() throws Exception {
        String email=email(); var result=register(citizenRequest(email,digits(11)),201);
        return new Account(UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText()),email);
    }
    private Map<String,Object> citizenRequest(String email,String cpf) {
        var request=new LinkedHashMap<String,Object>(); request.put("name","Cidadão"); request.put("email",email);
        request.put("password",PASSWORD); request.put("cpf",cpf); return request;
    }
    private MvcResult register(Map<String,Object> request,int status) throws Exception {
        return mvc.perform(post("/api/v1/auth/registrations/citizens").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request))).andExpect(status().is(status)).andReturn();
    }
    private LoginTokens login(String email) throws Exception {
        var result=mvc.perform(post("/api/v1/auth/mobile/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email",email,"password",PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        var body=json.readTree(result.getResponse().getContentAsString());
        return new LoginTokens(body.get("accessToken").asText(),body.get("refreshToken").asText());
    }
    private Account administrator() {
        UUID id=UUID.randomUUID(); String email=email();
        var encoder=new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16,32,1,19456,2);
        admin().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,?,'ADMIN')",id,email,encoder.encode(PASSWORD));
        return new Account(id,email);
    }
    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
    }
    private String signed(Jwt original,String issuer,String audience,Instant expiry) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),
            JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(original.getSubject())
                .issuedAt(expiry.minusSeconds(900)).expiresAt(expiry).claim("sid",original.getClaimAsString("sid")).build())).getTokenValue();
    }
    private String email() { return UUID.randomUUID()+"@test.invalid"; }
    private String digits(int length) { return new java.math.BigInteger(UUID.randomUUID().toString().replace("-",""),16).toString().substring(0,length); }
    private record Account(UUID id,String email) { }
    private record LoginTokens(String access,String refresh) { }
}
