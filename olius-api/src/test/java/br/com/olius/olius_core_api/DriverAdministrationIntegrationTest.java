package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.dto.DriverContracts.*;
import br.com.olius.olius_core_api.service.DriverService;
import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.import=","olius.security.ephemeral-keys=true",
    "olius.security.auth-requests-per-minute=10000","olius.admin-invitations.email.enabled=false",
    "olius.payments.stripe.enabled=false","springdoc.api-docs.enabled=true"})
class DriverAdministrationIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    private static final String ROOT="/api/v1/admin/drivers";
    private static final String PASSWORD="Password1!";
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",()->OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",()->OfficialPostgres.API_PASSWORD);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DriverService service;
    @Autowired PasswordEncoder passwords;
    private JdbcTemplate owner;
    private UUID adminId;
    private String access;

    @BeforeEach void fixture() throws Exception {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        adminId=UUID.randomUUID();String email=adminId+"@test.invalid";
        owner.update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,?,'ADMIN')",adminId,email,passwords.encode(PASSWORD));
        var login=mvc.perform(post("/api/v1/auth/mobile/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email",email,"password",PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        access=json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }
    @Test void createsWithRestrictedDatabaseRoleAndServerDefaultsWithoutCreatingUser() throws Exception {
        String cpf=document(),cnh=document();int users=owner.queryForObject("SELECT count(*) FROM users",Integer.class);
        var response=mvc.perform(body(post(ROOT),new Create("Motorista",cpf,cnh)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.registrationDate").isNotEmpty()).andExpect(jsonPath("$.updatedAt").isNotEmpty()).andReturn();
        var result=json.readTree(response.getResponse().getContentAsString());
        assertThat(response.getResponse().getHeader("Location")).isEqualTo(ROOT+"/"+result.get("id").asText());
        assertThat(owner.queryForObject("SELECT count(*) FROM users",Integer.class)).isEqualTo(users);
        assertThat(owner.queryForObject("SELECT count(*) FROM driver WHERE cpf=? AND cnh=?",Integer.class,cpf,cnh)).isEqualTo(1);
    }
    @Test void duplicateCpfOrCnhReturnsConflictWithoutLeakingDocuments() throws Exception {
        String cpf=document(),cnh=document();service.create(adminId,new Create("Original",cpf,cnh));
        for(var input:List.of(new Create("Duplicado",cpf,document()),new Create("Duplicado",document(),cnh))) {
            var response=mvc.perform(body(post(ROOT),input)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT")).andReturn();
            assertThat(response.getResponse().getContentAsString()).doesNotContain(cpf,cnh,"driver_cpf_key","driver_cnh_key");
        }
    }
    @Test void partialUpdatePreservesOtherFieldsAndDatabaseMaintainsDates() throws Exception {
        var original=service.create(adminId,new Create("Anterior",document(),document()));
        mvc.perform(body(patch(ROOT+"/"+original.id()),Map.of("name","Atualizado")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Atualizado"))
            .andExpect(jsonPath("$.cpf").value(original.cpf())).andExpect(jsonPath("$.cnh").value(original.cnh()));
        var current=service.detail(adminId,original.id());
        assertThat(current.registrationDate()).isEqualTo(original.registrationDate());
        assertThat(current.updatedAt()).isAfterOrEqualTo(original.updatedAt());
        mvc.perform(body(patch(ROOT+"/"+original.id()),Map.of("cpf",document(),"cnh",document())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Atualizado"));
    }
    @Test void statusChangesAreReversibleAndDoNotDeleteTheDriver() throws Exception {
        var driver=service.create(adminId,new Create("Status",document(),document()));
        mvc.perform(body(patch(ROOT+"/"+driver.id()+"/status"),Map.of("status","INACTIVE")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(get(ROOT).param("status","INACTIVE").header("Authorization","Bearer "+access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.id == '"+driver.id()+"')]").isNotEmpty());
        mvc.perform(get(ROOT+"/"+driver.id()).header("Authorization","Bearer "+access)).andExpect(status().isOk());
        mvc.perform(get(ROOT).param("size","1").header("Authorization","Bearer "+access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.page").value(0));
        mvc.perform(body(patch(ROOT+"/"+driver.id()+"/status"),Map.of("status","ACTIVE")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(delete(ROOT+"/"+driver.id()).header("Authorization","Bearer "+access)).andExpect(status().isMethodNotAllowed());
        assertThat(owner.queryForObject("SELECT count(*) FROM driver WHERE id=?",Integer.class,driver.id())).isEqualTo(1);
    }
    @Test void unknownAndServerControlledFieldsAreRejected() throws Exception {
        var driver=service.create(adminId,new Create("Protegido",document(),document()));
        for(var payload:List.of(Map.of("status","INACTIVE"),Map.of("registrationDate","2020-01-01T00:00:00Z"),Map.of("id",UUID.randomUUID().toString())))
            mvc.perform(body(patch(ROOT+"/"+driver.id()),payload)).andExpect(status().isBadRequest());
        mvc.perform(body(post(ROOT),Map.of("name","Novo","cpf",document(),"cnh",document(),"status","INACTIVE")))
            .andExpect(status().isBadRequest());
        assertThat(service.detail(adminId,driver.id()).status().name()).isEqualTo("ACTIVE");
    }
    @Test void structuralValidationRejectsBlankNamesDocumentsAndEmptyUpdates() throws Exception {
        for(var input:List.of(new Create(" ",document(),document()),new Create("Teste","123",document()),new Create("Teste",document(),"1234567890a")))
            mvc.perform(body(post(ROOT),input)).andExpect(status().isBadRequest());
        var driver=service.create(adminId,new Create("Válido",document(),document()));
        for(var payload:List.of(Map.of(),Map.of("name"," "),Map.of("cpf","")))
            mvc.perform(body(patch(ROOT+"/"+driver.id()),payload)).andExpect(status().isBadRequest());
        mvc.perform(body(patch(ROOT+"/"+driver.id()+"/status"),Map.of())).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT).param("size","101").header("Authorization","Bearer "+access)).andExpect(status().isBadRequest());
    }
    @Test void inactiveAdminIsRejectedByServiceEvenWithoutHttpFilter() {
        owner.update("INSERT INTO users(name,email,password_hash,user_type) VALUES ('Backup',?,'hash-teste','ADMIN')",UUID.randomUUID()+"@test.invalid");
        owner.update("UPDATE users SET status='INACTIVE' WHERE id=?",adminId);
        assertThatThrownBy(()->service.create(adminId,new Create("Negado",document(),document()))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void unauthenticatedAndNonAdminCannotReadPersonalDocuments() throws Exception {
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        UUID citizen=UUID.randomUUID();String email=citizen+"@test.invalid";
        owner.update("INSERT INTO users(id,name,email,password_hash,user_type) SELECT ?,'Cidadão',?,password_hash,'CITIZENS' FROM users WHERE id=?",citizen,email,adminId);
        var login=mvc.perform(post("/api/v1/auth/mobile/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email",email,"password",PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        String citizenAccess=json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(get(ROOT).header("Authorization","Bearer "+citizenAccess)).andExpect(status().isForbidden());
        assertThatThrownBy(()->service.detail(UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
    }
    @Test void missingDriverIsNotFoundAndUnknownFiltersAreInvalid() throws Exception {
        mvc.perform(get(ROOT+"/"+UUID.randomUUID()).header("Authorization","Bearer "+access)).andExpect(status().isNotFound());
        mvc.perform(get(ROOT).param("status","BUSY").header("Authorization","Bearer "+access)).andExpect(status().isBadRequest());
    }
    @Test void openApiRetainsAdministrativeContractAndBearerRequirement() throws Exception {
        var result=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var paths=json.readTree(result.getResponse().getContentAsString()).get("paths");
        assertThat(paths.get(ROOT).get("post").get("description").asText()).contains("Não cria usuário");
        assertThat(paths.get(ROOT).get("get").get("security").toString()).contains("bearer");
    }
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request,Object payload) throws Exception {
        return request.header("Authorization","Bearer "+access).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }
    private String document() { return Long.toString(ThreadLocalRandom.current().nextLong(10000000000L,99999999999L)); }
}
