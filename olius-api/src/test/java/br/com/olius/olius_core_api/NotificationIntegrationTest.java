package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.import=", "olius.security.ephemeral-keys=true",
    "olius.security.auth-requests-per-minute=10000"})
class NotificationIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",() -> OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",() -> OfficialPostgres.API_PASSWORD);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private static final String PASSWORD="Password1!";

    @Test void pevNotificationIsPrivateAndPreservesFirstRead() throws Exception {
        var owner = citizen();
        String ownerBearer = "Bearer " + login(owner.email());
        var administrator = administrator();
        String adminBearer = "Bearer " + login(administrator.email());
        UUID pev = requestPev(owner.email());
        setState(pev, "APPROVED", 1, administrator.id());
        admin().update("UPDATE pev SET status='APPROVED' WHERE id=?", pev);

        var result = mvc.perform(get("/api/v1/notifications")
                .header("Authorization", ownerBearer).param("unreadOnly", "true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].type").value("PEV_APPROVED"))
            .andExpect(jsonPath("$.items[0].resource.id").value(pev.toString()))
            .andReturn();
        UUID notice = UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .get("items").get(0).get("id").asText());

        mvc.perform(get("/api/v1/notifications").header("Authorization", adminBearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(put("/api/v1/notifications/{id}/read", notice).header("Authorization", adminBearer))
            .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/notifications/{id}/read", notice).header("Authorization", ownerBearer))
            .andExpect(status().isNoContent());
        var readAt = admin().queryForObject("SELECT read_at FROM notification WHERE id=?",
                java.sql.Timestamp.class, notice);
        mvc.perform(put("/api/v1/notifications/{id}/read", notice).header("Authorization", ownerBearer))
            .andExpect(status().isNoContent());
        assertThat(readAt).isNotNull().isEqualTo(admin().queryForObject(
                "SELECT read_at FROM notification WHERE id=?", java.sql.Timestamp.class, notice));
        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", ownerBearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
    }

    private Account citizen() throws Exception {
        String email=email();
        var response=mvc.perform(post("/api/v1/auth/registrations/citizens")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "name","Cidadão PEV","email",email,"password",PASSWORD,"cpf",digits(11)))))
            .andExpect(status().isCreated()).andReturn();
        UUID id=UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("id").asText());
        return new Account(id,email);
    }

    private Account administrator() {
        UUID id=UUID.randomUUID();
        var encoder=new Argon2PasswordEncoder(16,32,1,19456,2);
        admin().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,?,'ADMIN')",
            id,emailFor(id),encoder.encode(PASSWORD));
        return new Account(id,emailFor(id));
    }

    private UUID requestPev(String email) throws Exception {
        String access=login(email);
        var response=mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("address",address()))))
            .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("id").asText());
    }

    private void setState(UUID id,String status,int day,UUID adminId) {
        String date="2010-01-0"+day+" 00:00:00+00";
        if(status.equals("APPROVED"))
            admin().update("UPDATE pev SET created_at=CAST(? AS timestamptz),status='APPROVED',approved_at=clock_timestamp(),approved_by=? WHERE id=?",date,adminId,id);
        else
            admin().update("UPDATE pev SET created_at=CAST(? AS timestamptz),status=CAST(? AS approval_status_t) WHERE id=?",date,status,id);
    }

    private String login(String email) throws Exception {
        var response=mvc.perform(post("/api/v1/auth/mobile/login")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "email",email,"password",PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private Map<String,String> address() {
        return Map.of("state","SP","city","Cidade","neighborhood","Bairro",
            "street","Rua do PEV","number","10","cep","01001000");
    }

    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
    }
    private String email() { return UUID.randomUUID()+"@test.invalid"; }
    private String emailFor(UUID id) { return id+"@test.invalid"; }
    private record Account(UUID id,String email) { }
    private String digits(int length) {
        return new java.math.BigInteger(UUID.randomUUID().toString().replace("-",""),16).toString().substring(0,length);
    }
}
