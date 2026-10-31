package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.service.QrIdentificationService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.import=", "springdoc.api-docs.enabled=true",
    "olius.security.ephemeral-keys=true",
    "olius.security.auth-requests-per-minute=10000"})
class PevIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",() -> OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",() -> OfficialPostgres.API_PASSWORD);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired QrIdentificationService qrIdentification;
    private static final String PASSWORD="Password1!";
    private final List<UUID> deliveryPevIds=new ArrayList<>();

    @AfterEach void retireDeliveryTestPevs() {
        for(UUID id:deliveryPevIds)
            admin().update("UPDATE pev SET status='INACTIVE' WHERE id=? AND status='APPROVED'",id);
    }

    @Test void recordsAcceptedVolumeAndPointsWithoutChangingCitizenQr() throws Exception {
        var validator=citizen();
        var beneficiary=citizen();
        String access=login(validator.email());
        String qr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id());
        var created=mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().isCreated()).andReturn();
        UUID pevId=UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("id").asText());
        deliveryPevIds.add(pevId);
        UUID adminId=UUID.randomUUID();
        admin().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,'fixture-only','ADMIN')",
            adminId,email());
        admin().update("UPDATE pev SET status='APPROVED',approved_by=?,approved_at=clock_timestamp() WHERE id=?",
            adminId,pevId);
        var delivery=mvc.perform(post("/api/v1/pevs/{id}/deliveries",pevId)
            .header("Authorization","Bearer "+access).contentType(MediaType.APPLICATION_JSON)
            .content(deliveryRequest(UUID.randomUUID(),qr,"3.70",java.time.Instant.now().minusSeconds(3600))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.pevId").value(pevId.toString()))
            .andExpect(jsonPath("$.citizenId").value(beneficiary.id().toString()))
            .andExpect(jsonPath("$.oilVolumeLiters").value(3.70))
            .andExpect(jsonPath("$.pointsEarned").value(3))
            .andExpect(jsonPath("$.balanceAfter").value(3))
            .andExpect(jsonPath("$.recordStatus").value("RECORDED"))
            .andExpect(jsonPath("$.revision").value(1)).andReturn();
        UUID deliveryId=UUID.fromString(json.readTree(delivery.getResponse().getContentAsString()).get("id").asText());
        assertThat(delivery.getResponse().getHeader("Location"))
            .isEqualTo("/api/v1/pevs/"+pevId+"/deliveries/"+deliveryId);
        mvc.perform(get(delivery.getResponse().getHeader("Location"))
            .header("Authorization","Bearer "+access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(deliveryId.toString()));
        assertThat(admin().queryForObject("SELECT points FROM citizens WHERE id=?",Long.class,beneficiary.id()))
            .isEqualTo(3);
        assertThat(admin().queryForObject("SELECT count(*) FROM delivery_pev WHERE id=?",Long.class,deliveryId))
            .isEqualTo(1);
        assertThat(admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id())).isEqualTo(qr);
    }

    @Test void rejectsInvalidDeliveryFactsAndUnauthorizedValidator() throws Exception {
        var validator=citizen();
        var beneficiary=citizen();
        var outsider=citizen();
        String access=login(validator.email());
        String otherAccess=login(outsider.email());
        String qr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id());
        var created=mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().isCreated()).andReturn();
        UUID pevId=UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("id").asText());
        deliveryPevIds.add(pevId);
        String endpoint="/api/v1/pevs/"+pevId+"/deliveries";
        String valid=deliveryRequest(UUID.randomUUID(),qr,"0.75",java.time.Instant.now().minusSeconds(60));
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(valid))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(endpoint).header("Authorization","Bearer "+otherAccess)
            .contentType(MediaType.APPLICATION_JSON).content(valid))
            .andExpect(status().isForbidden());
        mvc.perform(post(endpoint).header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(valid))
            .andExpect(status().isForbidden()); // PEV ainda PENDING.
        UUID adminId=UUID.randomUUID();
        admin().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,'fixture-only','ADMIN')",
            adminId,email());
        admin().update("UPDATE pev SET status='APPROVED',approved_by=?,approved_at=clock_timestamp() WHERE id=?",
            adminId,pevId);
        for(String volume : List.of("0","-1","1.001","1000.00"))
            mvc.perform(post(endpoint).header("Authorization","Bearer "+access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(deliveryRequest(UUID.randomUUID(),qr,volume,java.time.Instant.now().minusSeconds(60))))
                .andExpect(status().isBadRequest());
        mvc.perform(post(endpoint).header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON)
            .content(deliveryRequest(UUID.randomUUID(),qr,"1.00",java.time.Instant.now().plusSeconds(3600))))
            .andExpect(status().isBadRequest());
        assertThat(admin().queryForObject("SELECT count(*) FROM delivery_pev WHERE pev_id=?",Long.class,pevId))
            .isZero();
    }

    private String deliveryRequest(UUID key,String qr,String volume,java.time.Instant date) throws Exception {
        return json.writeValueAsString(Map.of("idempotencyKey",key,"qrToken",qr,
            "oilVolumeLiters",new java.math.BigDecimal(volume),"deliveryDate",date));
    }

    @Test void recordsFractionalDeliveryBelowOneLiterWithoutAwardingPoints() throws Exception {
        var validator=citizen();
        var beneficiary=citizen();
        String access=login(validator.email());
        UUID pev=approvedDeliveryPev(validator,access);
        String qr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id());
        var result=mvc.perform(post("/api/v1/pevs/{id}/deliveries",pev)
            .header("Authorization","Bearer "+access).contentType(MediaType.APPLICATION_JSON)
            .content(deliveryRequest(UUID.randomUUID(),qr,"0.75",
                java.time.Instant.now().minusSeconds(60))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.pointsEarned").value(0))
            .andExpect(jsonPath("$.balanceAfter").value(0)).andReturn();
        UUID id=UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
        assertThat(admin().queryForObject("SELECT points_earned FROM delivery_pev WHERE id=?",Long.class,id))
            .isZero();
        assertThat(admin().queryForObject("SELECT points FROM citizens WHERE id=?",Long.class,beneficiary.id()))
            .isZero();
    }

    @Test void listsOnlyOwnPevDeliveriesWithStablePagesAndMinimalCitizenData() throws Exception {
        var owner=citizen();
        var otherOwner=citizen();
        var beneficiary=citizen();
        String access=login(owner.email());
        String otherAccess=login(otherOwner.email());
        UUID pev=approvedDeliveryPev(owner,access);
        UUID otherPev=approvedDeliveryPev(otherOwner,otherAccess);
        String qr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id());
        var date=java.time.Instant.now().minusSeconds(3600);
        String first=recordForTest(pev,access,qr,"1.00",date.minusSeconds(60));
        String second=recordForTest(pev,access,qr,"2.00",date);
        recordForTest(otherPev,otherAccess,qr,"3.00",date.plusSeconds(60));
        String endpoint="/api/v1/pevs/"+pev+"/deliveries";
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        mvc.perform(get(endpoint).header("Authorization","Bearer "+otherAccess))
            .andExpect(status().isForbidden());
        mvc.perform(get(endpoint).header("Authorization","Bearer "+access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].id").value(second))
            .andExpect(jsonPath("$.items[0].citizenId").value(beneficiary.id().toString()))
            .andExpect(jsonPath("$.items[0].citizenName").value("Cidadão"))
            .andExpect(jsonPath("$.items[0].email").doesNotExist())
            .andExpect(jsonPath("$.items[0].cpf").doesNotExist());
        mvc.perform(get(endpoint).param("page","0").param("size","1")
            .header("Authorization","Bearer "+access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(second))
            .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(endpoint).param("page","1").param("size","1")
            .header("Authorization","Bearer "+access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(first))
            .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(endpoint).param("page","2").param("size","1")
            .header("Authorization","Bearer "+access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(endpoint+"/"+first).header("Authorization","Bearer "+otherAccess))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pevs/"+otherPev+"/deliveries/"+first)
            .header("Authorization","Bearer "+otherAccess))
            .andExpect(status().isNotFound());
        for(String query : List.of("?page=", "?size=", "?page=2147483647&size=20"))
            mvc.perform(get(endpoint+query).header("Authorization","Bearer "+access))
                .andExpect(status().isBadRequest());
    }

    @Test void citizenPointsShowOnlyOwnCurrentDeliveryMovements() throws Exception {
        var owner=citizen();
        var beneficiary=citizen();
        var otherCitizen=citizen();
        String access=login(owner.email());
        String beneficiaryAccess=login(beneficiary.email());
        String otherAccess=login(otherCitizen.email());
        UUID pev=approvedDeliveryPev(owner,access);
        String qr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id());
        var date=java.time.Instant.now().minusSeconds(3600);
        String first=recordForTest(pev,access,qr,"3.70",date.minusSeconds(60));
        String second=recordForTest(pev,access,qr,"1.50",date);
        String endpoint="/api/v1/users/me/points";
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        mvc.perform(get(endpoint).header("Authorization","Bearer "+beneficiaryAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(4))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.movements.length()").value(2))
            .andExpect(jsonPath("$.movements[0].deliveryId").value(second))
            .andExpect(jsonPath("$.movements[0].points").value(1))
            .andExpect(jsonPath("$.movements[1].deliveryId").value(first))
            .andExpect(jsonPath("$.movements[1].points").value(3));
        mvc.perform(get(endpoint).param("page","1").param("size","1")
            .header("Authorization","Bearer "+beneficiaryAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(4))
            .andExpect(jsonPath("$.movements.length()").value(1))
            .andExpect(jsonPath("$.movements[0].deliveryId").value(first));
        mvc.perform(get(endpoint).param("page","2").param("size","1")
            .header("Authorization","Bearer "+beneficiaryAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(4))
            .andExpect(jsonPath("$.movements.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(endpoint).header("Authorization","Bearer "+otherAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(0))
            .andExpect(jsonPath("$.movements.length()").value(0));
    }

    private String recordForTest(UUID pev,String access,String qr,String volume,java.time.Instant date)
            throws Exception {
        var response=mvc.perform(post("/api/v1/pevs/{id}/deliveries",pev)
            .header("Authorization","Bearer "+access).contentType(MediaType.APPLICATION_JSON)
            .content(deliveryRequest(UUID.randomUUID(),qr,volume,date)))
            .andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("id").asText();
    }

    @Test void adminCorrectsRecordedDeliveryAndRebuildsPointsWithAudit() throws Exception {
        var validator=citizen();
        var beneficiary=citizen();
        String validatorAccess=login(validator.email());
        UUID pev=approvedDeliveryPev(validator,validatorAccess);
        String qr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id());
        var originalDate=java.time.Instant.now().minusSeconds(7200);
        UUID id=UUID.fromString(recordForTest(pev,validatorAccess,qr,"3.70",originalDate));
        var adminAccount=activeAdmin();
        var correctedDate=originalDate.minusSeconds(3600);
        String route="/api/v1/admin/deliveries/"+id+"/corrections";

        mvc.perform(post(route).header("Authorization","Bearer "+adminAccount.access())
            .contentType(MediaType.APPLICATION_JSON)
            .content(correction(1,"RECORDED","5.25",correctedDate,"  Volume conferido  ")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.delivery.id").value(id.toString()))
            .andExpect(jsonPath("$.delivery.oilVolumeLiters").value(5.25))
            .andExpect(jsonPath("$.delivery.pointsEarned").value(5))
            .andExpect(jsonPath("$.delivery.revision").value(2))
            .andExpect(jsonPath("$.balanceAfter").value(5));
        assertThat(admin().queryForObject("SELECT points FROM citizens WHERE id=?",Long.class,beneficiary.id()))
            .isEqualTo(5);
        assertThat(admin().queryForObject("SELECT correction_reason FROM delivery_pev WHERE id=?",
            String.class,id)).isEqualTo("Volume conferido");
        assertThat(admin().queryForObject("SELECT corrected_by FROM delivery_pev WHERE id=?",
            UUID.class,id)).isEqualTo(adminAccount.id());
        assertThat(admin().queryForObject("SELECT count(*) FROM delivery_pev_log WHERE id=? AND revision=2 AND snapshot_kind='AFTER' AND performed_by=?",
            Long.class,id,adminAccount.id())).isEqualTo(1);
        assertThat(admin().queryForObject("SELECT count(*) FROM point_calculation WHERE delivery_pev_id=? AND is_current",
            Long.class,id)).isEqualTo(1);
    }

    @Test void correctionRejectsOldRevisionInvalidFactsAndUnauthorizedCallers() throws Exception {
        var validator=citizen();
        var beneficiary=citizen();
        String validatorAccess=login(validator.email());
        UUID pev=approvedDeliveryPev(validator,validatorAccess);
        String qr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id());
        var originalDate=java.time.Instant.now().minusSeconds(7200);
        UUID id=UUID.fromString(recordForTest(pev,validatorAccess,qr,"3.70",originalDate));
        var adminAccount=activeAdmin();
        var createdAt=admin().queryForObject("SELECT created_at FROM delivery_pev WHERE id=?",
            java.sql.Timestamp.class,id).toInstant();
        String route="/api/v1/admin/deliveries/"+id+"/corrections";
        String valid=correction(1,"RECORDED","4.00",originalDate,"Conferência");
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content(valid))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(route).header("Authorization","Bearer "+validatorAccess)
            .contentType(MediaType.APPLICATION_JSON).content(valid))
            .andExpect(status().isForbidden());
        for(String invalid:List.of(
                correction(1,"RECORDED","1000.00",originalDate,"Conferência"),
                correction(1,"RECORDED","4.00",originalDate," "),
                correction(1,"RECORDED","4.00",java.time.Instant.now().plusSeconds(3600),"Conferência")))
            mvc.perform(post(route).header("Authorization","Bearer "+adminAccount.access())
                .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
        mvc.perform(post(route).header("Authorization","Bearer "+adminAccount.access())
            .contentType(MediaType.APPLICATION_JSON)
            .content(correction(1,"RECORDED","4.00",createdAt.plusNanos(1000),"Conferência")))
            .andExpect(status().isUnprocessableEntity()); // Após o horário de criação da entrega.
        mvc.perform(post(route).header("Authorization","Bearer "+adminAccount.access())
            .contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isOk());
        mvc.perform(post(route).header("Authorization","Bearer "+adminAccount.access())
            .contentType(MediaType.APPLICATION_JSON).content(valid))
            .andExpect(status().isConflict());
        assertThat(admin().queryForObject("SELECT revision FROM delivery_pev WHERE id=?",Integer.class,id))
            .isEqualTo(2);
    }

    @Test void annulmentPreservesPhysicalFactsAndRestorationRebuildsPoints() throws Exception {
        var validator=citizen();var beneficiary=citizen();
        String access=login(validator.email());
        UUID pev=approvedDeliveryPev(validator,access);
        String qr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",String.class,beneficiary.id());
        var date=java.time.Instant.now().minusSeconds(3600);
        UUID id=UUID.fromString(recordForTest(pev,access,qr,"3.70",date));
        var actor=activeAdmin();String route="/api/v1/admin/deliveries/"+id+"/corrections";
        String annul=json.writeValueAsString(Map.of("expectedRevision",1,"recordStatus","ANNULLED","reason","Duplicidade conferida"));
        mvc.perform(post(route).header("Authorization","Bearer "+actor.access())
            .contentType(MediaType.APPLICATION_JSON).content(correction(1,"ANNULLED","3.70",date,"Anulação")))
            .andExpect(status().isBadRequest());
        mvc.perform(post(route).header("Authorization","Bearer "+actor.access())
            .contentType(MediaType.APPLICATION_JSON).content(annul))
            .andExpect(status().isOk()).andExpect(jsonPath("$.delivery.recordStatus").value("ANNULLED"))
            .andExpect(jsonPath("$.delivery.revision").value(2))
            .andExpect(jsonPath("$.delivery.oilVolumeLiters").value(3.70))
            .andExpect(jsonPath("$.delivery.pointsEarned").value(0))
            .andExpect(jsonPath("$.balanceAfter").value(0));
        assertThat(admin().queryForObject("SELECT delivery_date FROM delivery_pev WHERE id=?",java.sql.Timestamp.class,id).toInstant())
            .isEqualTo(date.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(admin().queryForObject("SELECT points FROM citizens WHERE id=?",Long.class,beneficiary.id())).isZero();
        mvc.perform(post(route).header("Authorization","Bearer "+actor.access())
            .contentType(MediaType.APPLICATION_JSON).content(annul)).andExpect(status().isConflict());
        mvc.perform(post(route).header("Authorization","Bearer "+actor.access())
            .contentType(MediaType.APPLICATION_JSON).content(correction(2,"RECORDED","3.70",date,"Restauração conferida")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.delivery.recordStatus").value("RECORDED"))
            .andExpect(jsonPath("$.delivery.revision").value(3))
            .andExpect(jsonPath("$.delivery.pointsEarned").value(3))
            .andExpect(jsonPath("$.balanceAfter").value(3));
        assertThat(admin().queryForObject("SELECT points FROM citizens WHERE id=?",Long.class,beneficiary.id())).isEqualTo(3);
        assertThat(admin().queryForObject("SELECT count(*) FROM point_calculation WHERE delivery_pev_id=? AND is_current",Long.class,id)).isEqualTo(1);
        assertThat(admin().queryForObject("SELECT count(*) FROM delivery_pev_log WHERE id=? AND snapshot_kind='AFTER' AND revision IN (2,3) AND performed_by=?",Long.class,id,actor.id())).isEqualTo(2);
    }

    private String correction(int revision,String status,String volume,java.time.Instant date,String reason)
            throws Exception {
        return json.writeValueAsString(Map.of("expectedRevision",revision,"recordStatus",status,
            "oilVolumeLiters",new java.math.BigDecimal(volume),"deliveryDate",date,"reason",reason));
    }

    private AdminAccount activeAdmin() throws Exception {
        UUID id=UUID.randomUUID();String email=email();
        var encoder=new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16,32,1,19456,2);
        admin().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,?,'ADMIN')",
            id,email,encoder.encode(PASSWORD));
        return new AdminAccount(id,login(email));
    }

    private record AdminAccount(UUID id,String access) { }

    @Test void recoversConfirmedDeliveryWithoutQrAfterPevInactivationAndRejectsChangedFacts() throws Exception {
        var validator=citizen();
        var beneficiary=citizen();
        var otherBeneficiary=citizen();
        String access=login(validator.email());
        UUID pevId=approvedDeliveryPev(validator,access);
        String qr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id());
        String otherQr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,otherBeneficiary.id());
        UUID key=UUID.randomUUID();
        var date=java.time.Instant.now().minusSeconds(120);
        String endpoint="/api/v1/pevs/"+pevId+"/deliveries";
        var first=mvc.perform(post(endpoint).header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(deliveryRequest(key,qr,"2.50",date)))
            .andExpect(status().isCreated()).andReturn();
        String deliveryId=json.readTree(first.getResponse().getContentAsString()).get("id").asText();
        admin().update("UPDATE pev SET status='INACTIVE' WHERE id=?",pevId);
        String retry=json.writeValueAsString(Map.of("idempotencyKey",key,
            "oilVolumeLiters",new java.math.BigDecimal("2.50"),"deliveryDate",date));
        mvc.perform(post(endpoint).header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(retry))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(deliveryId))
            .andExpect(jsonPath("$.pointsEarned").value(2));
        mvc.perform(post(endpoint).header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON)
            .content(deliveryRequest(key,"A".repeat(43),"2.50",date)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(deliveryId));
        mvc.perform(post(endpoint).header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON)
            .content(deliveryRequest(key,otherQr,"2.50",date)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(deliveryId))
            .andExpect(jsonPath("$.citizenId").value(beneficiary.id().toString()));
        mvc.perform(post(endpoint).header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("idempotencyKey",key,
                "oilVolumeLiters",new java.math.BigDecimal("3.00"),"deliveryDate",date))))
            .andExpect(status().isConflict());
        assertThat(admin().queryForObject("SELECT count(*) FROM delivery_pev WHERE idempotency_key=?",
            Long.class,br.com.olius.olius_core_api.service.DeliveryKeys.internal(validator.id(),key)))
            .isEqualTo(1);
        assertThat(admin().queryForObject("SELECT points FROM citizens WHERE id=?",Long.class,beneficiary.id()))
            .isEqualTo(2);
        assertThat(admin().queryForObject("SELECT points FROM citizens WHERE id=?",Long.class,otherBeneficiary.id()))
            .isZero();
        admin().update("UPDATE users SET status='INACTIVE' WHERE id=?",validator.id());
        mvc.perform(post(endpoint).header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(retry))
            .andExpect(status().isUnauthorized());
    }

    @Test void concurrentReplaysCreateOnceAndAnotherValidatorCannotReadTheOriginalKey() throws Exception {
        var validator=citizen();
        var other=citizen();
        var beneficiary=citizen();
        String access=login(validator.email());
        String otherAccess=login(other.email());
        UUID pevId=approvedDeliveryPev(validator,access);
        UUID otherPev=approvedDeliveryPev(other,otherAccess);
        String qr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id());
        UUID key=UUID.randomUUID();
        var date=java.time.Instant.now().minusSeconds(120);
        String body=deliveryRequest(key,qr,"1.25",date);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<org.springframework.test.web.servlet.MvcResult> attempt=()-> {
                start.await();
                return mvc.perform(post("/api/v1/pevs/{id}/deliveries",pevId)
                    .header("Authorization","Bearer "+access)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
            };
            var a=pool.submit(attempt);
            var b=pool.submit(attempt);
            start.countDown();
            var first=a.get(30,TimeUnit.SECONDS);
            var second=b.get(30,TimeUnit.SECONDS);
            assertThat(List.of(first.getResponse().getStatus(),second.getResponse().getStatus()))
                .containsExactlyInAnyOrder(201,200);
            assertThat(json.readTree(first.getResponse().getContentAsString()).get("id").asText())
                .isEqualTo(json.readTree(second.getResponse().getContentAsString()).get("id").asText());
        }
        assertThat(admin().queryForObject("SELECT count(*) FROM delivery_pev WHERE pev_id=?",Long.class,pevId))
            .isEqualTo(1);
        mvc.perform(post("/api/v1/pevs/{id}/deliveries",otherPev)
            .header("Authorization","Bearer "+otherAccess)
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        assertThat(admin().queryForObject("SELECT count(*) FROM delivery_pev WHERE citizen_id=?",Long.class,beneficiary.id()))
            .isEqualTo(2);
    }

    private UUID approvedDeliveryPev(Account owner,String access) throws Exception {
        var created=mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().isCreated()).andReturn();
        UUID pevId=UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("id").asText());
        deliveryPevIds.add(pevId);
        UUID adminId=UUID.randomUUID();
        admin().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,'fixture-only','ADMIN')",
            adminId,email());
        admin().update("UPDATE pev SET status='APPROVED',approved_by=?,approved_at=clock_timestamp() WHERE id=?",
            adminId,pevId);
        return pevId;
    }

    @Test void qrIdentificationRequiresApprovedPevAndKeepsTheTokenUnchanged() throws Exception {
        var validator=citizen();
        var beneficiary=citizen();
        String qr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id());
        assertThatThrownBy(() -> qrIdentification.identify(qr,validator.id(),UserType.CITIZENS))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        String access=login(validator.email());
        mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isCreated());
        assertThatThrownBy(() -> qrIdentification.identify(qr,validator.id(),UserType.CITIZENS))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        UUID adminId=UUID.randomUUID();
        admin().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,'fixture-only','ADMIN')",
            adminId,email());
        admin().update("UPDATE pev SET status='APPROVED',approved_by=?,approved_at=clock_timestamp() WHERE citizen_id=?",
            adminId,validator.id());
        assertThat(qrIdentification.identify(qr,validator.id(),UserType.CITIZENS))
            .extracting(QrIdentificationService.IdentifiedCitizen::id,
                QrIdentificationService.IdentifiedCitizen::name)
            .containsExactly(beneficiary.id(),"Cidadão");
        assertThat(qrIdentification.identify(qr,validator.id(),UserType.CITIZENS).id())
            .isEqualTo(beneficiary.id());
        assertThat(admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id())).isEqualTo(qr);

        assertThatThrownBy(() -> qrIdentification.identify("invalid",validator.id(),UserType.CITIZENS))
            .isInstanceOf(ResourceNotFoundException.class);
        String unknownQr="A".repeat(43);
        assertThatThrownBy(() -> qrIdentification.identify(unknownQr,validator.id(),UserType.CITIZENS))
            .isInstanceOf(ResourceNotFoundException.class);
        String ownQr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,validator.id());
        assertThatThrownBy(() -> qrIdentification.identify(ownQr,validator.id(),UserType.CITIZENS))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        admin().update("UPDATE users SET status='INACTIVE' WHERE id=?",beneficiary.id());
        assertThatThrownBy(() -> qrIdentification.identify(qr,validator.id(),UserType.CITIZENS))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void qrIdentificationEndpointRequiresBearerAndReturnsMinimalCitizenData() throws Exception {
        var validator=citizen();
        var beneficiary=citizen();
        String qr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id());
        String body=json.writeValueAsString(Map.of("qrToken",qr));
        mvc.perform(post("/api/v1/qr/resolutions").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        String access=login(validator.email());
        mvc.perform(post("/api/v1/qr/resolutions").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isCreated());
        UUID adminId=UUID.randomUUID();
        admin().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,'fixture-only','ADMIN')",
            adminId,email());
        admin().update("UPDATE pev SET status='APPROVED',approved_by=?,approved_at=clock_timestamp() WHERE citizen_id=?",
            adminId,validator.id());
        mvc.perform(post("/api/v1/qr/resolutions").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(beneficiary.id().toString()))
            .andExpect(jsonPath("$.name").value("Cidadão"))
            .andExpect(jsonPath("$.qrToken").doesNotExist())
            .andExpect(jsonPath("$.cpf").doesNotExist())
            .andExpect(jsonPath("$.email").doesNotExist());
        mvc.perform(post("/api/v1/qr/resolutions").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content("{\"qrToken\":\"\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/qr/resolutions").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("qrToken","A".repeat(43)))))
            .andExpect(status().isNotFound());
    }

    @Test void approvedEstablishmentPevIdentifiesOnlyCitizenQrTokens() throws Exception {
        var validator=establishment();
        var beneficiary=citizen();
        String access=login(validator.email());
        String citizenQr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id());
        String establishmentQr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,validator.id());
        mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isCreated());
        UUID adminId=UUID.randomUUID();
        admin().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,'fixture-only','ADMIN')",
            adminId,email());
        admin().update("UPDATE pev SET status='APPROVED',approved_by=?,approved_at=clock_timestamp() WHERE establishment_id=?",
            adminId,validator.id());
        assertThat(qrIdentification.identify(citizenQr,validator.id(),UserType.ESTABLISHMENT))
            .extracting(QrIdentificationService.IdentifiedCitizen::id,
                QrIdentificationService.IdentifiedCitizen::name)
            .containsExactly(beneficiary.id(),"Cidadão");
        mvc.perform(post("/api/v1/qr/resolutions").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("qrToken",citizenQr))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(beneficiary.id().toString()))
            .andExpect(jsonPath("$.name").value("Cidadão"));
        assertThatThrownBy(() -> qrIdentification.identify(establishmentQr,validator.id(),UserType.ESTABLISHMENT))
            .isInstanceOf(ResourceNotFoundException.class);
        mvc.perform(post("/api/v1/qr/resolutions").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("qrToken",establishmentQr))))
            .andExpect(status().isNotFound());
    }

    @Test void openApiShowsPublicListingAndAuthenticatedOwnerOperations() throws Exception {
        var response=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var paths=json.readTree(response.getResponse().getContentAsString()).get("paths");
        var pevs=paths.get("/api/v1/pevs");
        assertThat(pevs.get("get").get("security").isEmpty()).isTrue();
        assertThat(pevs.get("post").get("security").toString()).contains("bearer");
        assertThat(paths.get("/api/v1/pevs/me").get("get").get("security").toString())
            .contains("bearer");
        assertThat(pevs.get("get").get("responses").has("200")).isTrue();
        assertThat(pevs.get("post").get("responses").has("201")).isTrue();
    }

    @Test void citizenRequestsOwnPevAndDuplicateDoesNotLeaveAnAddress() throws Exception {
        var account=citizen(); String access=login(account.email());
        mvc.perform(get("/api/v1/pevs/me").header("Authorization","Bearer "+access))
            .andExpect(status().isNotFound());
        var created=mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().isCreated()).andExpect(header().string("Location","/api/v1/pevs/me"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.address.street").value("Rua do PEV")).andReturn();
        UUID pevId=UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("id").asText());
        assertThat(admin().queryForObject("SELECT count(*) FROM pev p JOIN addresses a ON a.id=p.address_id WHERE p.id=? AND p.citizen_id=? AND p.establishment_id IS NULL AND a.owner_kind='PEV'",Integer.class,pevId,account.id())).isEqualTo(1);
        assertThat(admin().queryForObject("SELECT count(*) FROM pev_log WHERE id=? AND performed_by=?",Integer.class,pevId,account.id())).isEqualTo(1);
        long addresses=admin().queryForObject("SELECT count(*) FROM addresses",Long.class);
        mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PEV_ALREADY_EXISTS"));
        assertThat(admin().queryForObject("SELECT count(*) FROM addresses",Long.class)).isEqualTo(addresses);
        mvc.perform(get("/api/v1/pevs/me").header("Authorization","Bearer "+access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(pevId.toString()))
            .andExpect(jsonPath("$.address.state").value("SP"));
    }

    @Test void establishmentPevHasAddressSeparateFromCommercialAddress() throws Exception {
        UUID type=UUID.randomUUID(); admin().update("INSERT INTO establishment_type(id,name) VALUES (?,?)",type,"Tipo-"+type);
        String email=email();
        var registered=mvc.perform(post("/api/v1/auth/registrations/establishments")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "name","Estabelecimento", "email",email,"password",PASSWORD,
                "cnpj",digits(14),"typeId",type,"address",commercialAddress()))))
            .andExpect(status().isCreated()).andReturn();
        UUID owner=UUID.fromString(json.readTree(registered.getResponse().getContentAsString()).get("id").asText());
        String access=login(email);
        mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(admin().queryForObject("SELECT count(*) FROM pev p JOIN establishment e ON e.id=p.establishment_id JOIN addresses a ON a.id=p.address_id WHERE p.establishment_id=? AND p.citizen_id IS NULL AND p.address_id<>e.address_id AND a.owner_kind='PEV'",Integer.class,owner)).isEqualTo(1);
        assertThat(admin().queryForObject("SELECT is_pev FROM establishment WHERE id=?",Boolean.class,owner)).isFalse();
    }

    @Test void publicListPaginatesApprovedPevsInStableOrderWithoutOwnerData() throws Exception {
        List<UUID> ids=new ArrayList<>();
        for(int i=0;i<4;i++) {
            var account=citizen(); String access=login(account.email());
            var created=mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
                .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isCreated()).andReturn();
            ids.add(UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("id").asText()));
        }
        mvc.perform(get("/api/v1/pevs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
        UUID adminId=UUID.randomUUID();
        admin().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,'fixture-only','ADMIN')",adminId,email());
        List<UUID> approved=ids.subList(0,3).stream().sorted(Comparator.comparing(UUID::toString)).toList();
        UUID earliest=approved.get(2);
        admin().update("UPDATE pev SET status='APPROVED',created_at=TIMESTAMPTZ '2020-01-01 00:00:00+00',approved_at=clock_timestamp(),approved_by=? WHERE id=?",adminId,earliest);
        for(UUID id:approved.subList(0,2))
            admin().update("UPDATE pev SET status='APPROVED',created_at=TIMESTAMPTZ '2020-01-02 00:00:00+00',approved_at=clock_timestamp(),approved_by=? WHERE id=?",adminId,id);
        mvc.perform(get("/api/v1/pevs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].id").value(earliest.toString()))
            .andExpect(jsonPath("$.items[1].id").value(approved.get(0).toString()))
            .andExpect(jsonPath("$.items[2].id").value(approved.get(1).toString()))
            .andExpect(jsonPath("$.items[0].address.street").value("Rua do PEV"))
            .andExpect(jsonPath("$.items[0].address.latitude").value(-23.5505))
            .andExpect(jsonPath("$.items[0].address.longitude").value(-46.6333))
            .andExpect(jsonPath("$.items[0].citizenId").doesNotExist())
            .andExpect(jsonPath("$.items[0].email").doesNotExist());
        var first=mvc.perform(get("/api/v1/pevs").param("page","0").param("size","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2)).andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.items.length()").value(2)).andReturn();
        var second=mvc.perform(get("/api/v1/pevs").param("page","1").param("size","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(2)).andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.items.length()").value(1)).andReturn();
        var firstItems=json.readTree(first.getResponse().getContentAsString()).get("items");
        var secondItems=json.readTree(second.getResponse().getContentAsString()).get("items");
        assertThat(List.of(firstItems.get(0).get("id").asText(),firstItems.get(1).get("id").asText(),
            secondItems.get(0).get("id").asText())).containsExactly(
                earliest.toString(),approved.get(0).toString(),approved.get(1).toString()).doesNotHaveDuplicates();
        mvc.perform(get("/api/v1/pevs").param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/pevs").param("size","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/pevs").param("page","")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/pevs").param("size","")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/pevs").param("page","-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/pevs").param("page","2147483647").param("size","20"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test void requestRequiresAuthenticationAndRejectsUnknownFields() throws Exception {
        mvc.perform(post("/api/v1/pevs").contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/pevs/me")).andExpect(status().isUnauthorized());
        var account=citizen(); String access=login(account.email());
        for(String field:List.of("\"citizenId\":\""+account.id()+"\"",
            "\"status\":\"APPROVED\"", "\"approvedBy\":\""+UUID.randomUUID()+"\""))
            mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
                .contentType(MediaType.APPLICATION_JSON).content(request().replaceFirst("\\}$",","+field+"}")))
                .andExpect(status().isBadRequest());
        assertThat(admin().queryForObject("SELECT count(*) FROM pev WHERE citizen_id=?",Long.class,account.id()))
            .isZero();
        UUID adminId=UUID.randomUUID(); String adminEmail=email();
        var encoder=new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16,32,1,19456,2);
        admin().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,?,'ADMIN')",
            adminId,adminEmail,encoder.encode(PASSWORD));
        String adminAccess=login(adminEmail);
        mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+adminAccess)
            .contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pevs/me").header("Authorization","Bearer "+adminAccess))
            .andExpect(status().isForbidden());
    }

    @Test void inactiveOwnerCannotUsePreviousTokenToRequestOrConsultPev() throws Exception {
        var account=citizen(); String access=login(account.email());
        admin().update("UPDATE users SET status='INACTIVE' WHERE id=?",account.id());
        mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/pevs/me").header("Authorization","Bearer "+access))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/pevs")).andExpect(status().isOk());
        assertThat(admin().queryForObject("SELECT count(*) FROM pev WHERE citizen_id=?",Long.class,account.id()))
            .isZero();
    }

    @Test void concurrentRequestsCreateOnlyOnePevAndOneAddress() throws Exception {
        var account=citizen(); String access=login(account.email());
        long before=admin().queryForObject("SELECT count(*) FROM addresses",Long.class);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var attempt= (java.util.concurrent.Callable<Integer>) () -> {
                start.await();
                return mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
                    .contentType(MediaType.APPLICATION_JSON).content(request()))
                    .andReturn().getResponse().getStatus();
            };
            var first=pool.submit(attempt);
            var second=pool.submit(attempt);
            start.countDown();
            assertThat(first.get(30,TimeUnit.SECONDS)).isIn(201,409);
            assertThat(java.util.List.of(first.get(),second.get())).containsExactlyInAnyOrder(201,409);
        }
        assertThat(admin().queryForObject("SELECT count(*) FROM pev WHERE citizen_id=?",Long.class,account.id())).isEqualTo(1);
        assertThat(admin().queryForObject("SELECT count(*) FROM addresses",Long.class)).isEqualTo(before+1);
    }

    private Account citizen() throws Exception {
        String email=email();
        var response=mvc.perform(post("/api/v1/auth/registrations/citizens")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "name","Cidadão","email",email,"password",PASSWORD,"cpf",digits(11)))))
            .andExpect(status().isCreated()).andReturn();
        return new Account(UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("id").asText()),email);
    }

    private Account establishment() throws Exception {
        UUID type=UUID.randomUUID();
        admin().update("INSERT INTO establishment_type(id,name) VALUES (?,?)",type,"Tipo-"+type);
        String email=email();
        var response=mvc.perform(post("/api/v1/auth/registrations/establishments")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "name","Estabelecimento PEV","email",email,"password",PASSWORD,
                "cnpj",digits(14),"typeId",type,"address",commercialAddress()))))
            .andExpect(status().isCreated()).andReturn();
        return new Account(UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("id").asText()),email);
    }

    private String login(String email) throws Exception {
        var response=mvc.perform(post("/api/v1/auth/mobile/login")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "email",email,"password",PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String request() throws Exception {
        return json.writeValueAsString(Map.of("address",Map.of("state","SP","city","Cidade",
            "neighborhood","Bairro","street","Rua do PEV","number","10","cep","01001000",
            "latitude",new java.math.BigDecimal("-23.5505"),
            "longitude",new java.math.BigDecimal("-46.6333"))));
    }

    private Map<String,String> commercialAddress() {
        return Map.of("state","SP","city","Cidade","neighborhood","Bairro",
            "street","Rua comercial","number","1","cep","01001000");
    }

    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
    }

    private String email() { return UUID.randomUUID()+"@test.invalid"; }
    private String digits(int length) { return new java.math.BigInteger(UUID.randomUUID().toString().replace("-",""),16).toString().substring(0,length); }
    private record Account(UUID id,String email) { }
}
