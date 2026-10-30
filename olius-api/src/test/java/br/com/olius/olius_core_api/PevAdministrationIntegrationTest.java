package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.util.List;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.import=", "olius.security.ephemeral-keys=true",
    "olius.security.auth-requests-per-minute=10000"})
class PevAdministrationIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",() -> OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",() -> OfficialPostgres.API_PASSWORD);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private static final String PASSWORD="Password1!";

    @Test void adminListsAllStatusesWithStablePagesAndFilters() throws Exception {
        var adminAccount=administrator(); UUID adminId=adminAccount.id();
        String adminAccess=login(adminAccount.email());
        long existing=admin().queryForObject("SELECT count(*) FROM pev",Long.class);
        long existingCitizens=admin().queryForObject("""
            SELECT count(*) FROM pev p JOIN users u ON u.id=COALESCE(p.citizen_id,p.establishment_id)
            WHERE u.user_type='CITIZENS'
            """,Long.class);
        UUID pending=citizenPev();
        UUID approvedCitizen=citizenPev();
        UUID approvedEstablishment=establishmentPev();
        UUID rejected=establishmentPev();
        setState(pending,"PENDING",1,adminId);
        setState(approvedCitizen,"APPROVED",2,adminId);
        setState(approvedEstablishment,"APPROVED",3,adminId);
        setState(rejected,"REJECTED",4,adminId);

        mvc.perform(get("/api/v1/admin/pevs").header("Authorization","Bearer "+adminAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.totalElements").value(existing+4))
            .andExpect(jsonPath("$.items[0].id").value(pending.toString()))
            .andExpect(jsonPath("$.items[1].id").value(approvedCitizen.toString()))
            .andExpect(jsonPath("$.items[2].id").value(approvedEstablishment.toString()))
            .andExpect(jsonPath("$.items[3].id").value(rejected.toString()))
            .andExpect(jsonPath("$.items[0].ownerType").value("CITIZENS"))
            .andExpect(jsonPath("$.items[2].ownerType").value("ESTABLISHMENT"))
            .andExpect(jsonPath("$.items[0].ownerName").value("Cidadão PEV"))
            .andExpect(jsonPath("$.items[0].address.street").value("Rua do PEV"))
            .andExpect(jsonPath("$.items[0].email").doesNotExist())
            .andExpect(jsonPath("$.items[0].cpf").doesNotExist());

        var first=mvc.perform(get("/api/v1/admin/pevs").header("Authorization","Bearer "+adminAccess)
            .param("page","0").param("size","2")).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(existing+4)).andExpect(jsonPath("$.items.length()").value(2))
            .andReturn();
        var second=mvc.perform(get("/api/v1/admin/pevs").header("Authorization","Bearer "+adminAccess)
            .param("page","1").param("size","2")).andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.items.length()").value(2)).andReturn();
        var firstItems=json.readTree(first.getResponse().getContentAsString()).get("items");
        var secondItems=json.readTree(second.getResponse().getContentAsString()).get("items");
        assertThat(List.of(firstItems.get(0).get("id").asText(),firstItems.get(1).get("id").asText(),
            secondItems.get(0).get("id").asText(),secondItems.get(1).get("id").asText()))
            .containsExactly(pending.toString(),approvedCitizen.toString(),
                approvedEstablishment.toString(),rejected.toString()).doesNotHaveDuplicates();

        mvc.perform(get("/api/v1/admin/pevs").header("Authorization","Bearer "+adminAccess)
            .param("status","APPROVED").param("ownerType","ESTABLISHMENT"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].id").value(approvedEstablishment.toString()));
        mvc.perform(get("/api/v1/admin/pevs").header("Authorization","Bearer "+adminAccess)
            .param("status","REJECTED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].id").value(rejected.toString()));
        mvc.perform(get("/api/v1/admin/pevs").header("Authorization","Bearer "+adminAccess)
            .param("ownerType","CITIZENS"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(existingCitizens+2))
            .andExpect(jsonPath("$.items[0].id").value(pending.toString()))
            .andExpect(jsonPath("$.items[1].id").value(approvedCitizen.toString()));
        mvc.perform(get("/api/v1/admin/pevs").header("Authorization","Bearer "+adminAccess)
            .param("page","100").param("size","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(existing+4));
    }

    @Test void listRequiresActiveAdministratorAndValidParameters() throws Exception {
        mvc.perform(get("/api/v1/admin/pevs")).andExpect(status().isUnauthorized());
        var citizenAccount=citizen(); String citizenAccess=login(citizenAccount.email());
        mvc.perform(get("/api/v1/admin/pevs").header("Authorization","Bearer "+citizenAccess))
            .andExpect(status().isForbidden());
        var adminAccount=administrator(); UUID adminId=adminAccount.id();
        String adminAccess=login(adminAccount.email());
        for(String size:List.of("0","101"))
            mvc.perform(get("/api/v1/admin/pevs").header("Authorization","Bearer "+adminAccess)
                .param("size",size)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/pevs").header("Authorization","Bearer "+adminAccess)
            .param("page","")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/pevs").header("Authorization","Bearer "+adminAccess)
            .param("size","")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/pevs").header("Authorization","Bearer "+adminAccess)
            .param("page","-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/pevs").header("Authorization","Bearer "+adminAccess)
            .param("page","2147483647").param("size","20")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/pevs").header("Authorization","Bearer "+adminAccess)
            .param("ownerType","ADMIN")).andExpect(status().isBadRequest());
        admin().queryForObject("SELECT public.set_admin_status(?,?,'INACTIVE'::public.active_status_t)",
                Object.class,administrator().id(),adminId);
        mvc.perform(get("/api/v1/admin/pevs").header("Authorization","Bearer "+adminAccess))
            .andExpect(status().isUnauthorized());
    }

    @Test void detailShowsCitizenAndEstablishmentOwnersWithApprovalData() throws Exception {
        var adminAccount=administrator(); UUID adminId=adminAccount.id();
        String adminAccess=login(adminAccount.email());
        UUID citizenPev=citizenPev();
        UUID establishmentPev=establishmentPev();
        setState(citizenPev,"APPROVED",9,adminId);
        UUID citizenOwner=admin().queryForObject("SELECT citizen_id FROM pev WHERE id=?",UUID.class,citizenPev);
        UUID establishmentOwner=admin().queryForObject(
            "SELECT establishment_id FROM pev WHERE id=?",UUID.class,establishmentPev);

        mvc.perform(get("/api/v1/admin/pevs/{id}",citizenPev)
            .header("Authorization","Bearer "+adminAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(citizenPev.toString()))
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.approvedBy").value(adminId.toString()))
            .andExpect(jsonPath("$.approvedAt").isNotEmpty())
            .andExpect(jsonPath("$.ownerId").value(citizenOwner.toString()))
            .andExpect(jsonPath("$.ownerType").value("CITIZENS"))
            .andExpect(jsonPath("$.ownerName").value("Cidadão PEV"))
            .andExpect(jsonPath("$.address.street").value("Rua do PEV"))
            .andExpect(jsonPath("$.email").doesNotExist())
            .andExpect(jsonPath("$.cpf").doesNotExist());
        mvc.perform(get("/api/v1/admin/pevs/{id}",establishmentPev)
            .header("Authorization","Bearer "+adminAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.approvedBy").doesNotExist())
            .andExpect(jsonPath("$.approvedAt").doesNotExist())
            .andExpect(jsonPath("$.ownerId").value(establishmentOwner.toString()))
            .andExpect(jsonPath("$.ownerType").value("ESTABLISHMENT"))
            .andExpect(jsonPath("$.ownerName").value("Estabelecimento PEV"));
    }

    @Test void detailRequiresActiveAdminAndReturnsNotFoundForUnknownPev() throws Exception {
        UUID missing=UUID.randomUUID();
        mvc.perform(get("/api/v1/admin/pevs/{id}",missing)).andExpect(status().isUnauthorized());
        var citizenAccount=citizen(); String citizenAccess=login(citizenAccount.email());
        mvc.perform(get("/api/v1/admin/pevs/{id}",missing)
            .header("Authorization","Bearer "+citizenAccess)).andExpect(status().isForbidden());
        var adminAccount=administrator(); String adminAccess=login(adminAccount.email());
        mvc.perform(get("/api/v1/admin/pevs/{id}",missing)
            .header("Authorization","Bearer "+adminAccess))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        admin().queryForObject("SELECT public.set_admin_status(?,?,'INACTIVE'::public.active_status_t)",
                Object.class,administrator().id(),adminAccount.id());
        mvc.perform(get("/api/v1/admin/pevs/{id}",missing)
            .header("Authorization","Bearer "+adminAccess)).andExpect(status().isUnauthorized());
    }

    private UUID citizenPev() throws Exception {
        return requestPev(citizen().email());
    }

    private UUID establishmentPev() throws Exception {
        UUID type=UUID.randomUUID();
        admin().update("INSERT INTO establishment_type(id,name) VALUES (?,?)",type,"Tipo-"+type);
        String email=email();
        mvc.perform(post("/api/v1/auth/registrations/establishments")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "name","Estabelecimento PEV","email",email,"password",PASSWORD,
                "cnpj",digits(14),"typeId",type,"address",address()))))
            .andExpect(status().isCreated());
        return requestPev(email);
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
