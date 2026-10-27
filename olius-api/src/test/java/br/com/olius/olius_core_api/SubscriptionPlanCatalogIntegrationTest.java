package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.service.SubscriptionPlanCatalogService;
import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.import=","olius.security.ephemeral-keys=true","springdoc.api-docs.enabled=true"})
class SubscriptionPlanCatalogIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",() -> OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",() -> OfficialPostgres.API_PASSWORD);
    }
    @Autowired SubscriptionPlanCatalogService service;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private JdbcTemplate owner;

    @BeforeEach void cleanCatalog() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),POSTGRES.getPassword()));
        // Fixture exclusiva desta classe, sem ciclos ou cobranças; não altera banco de trabalho.
        owner.update("DELETE FROM public.subscription_plan");
    }

    @Test void readsOnlyActiveOffersWithExactDecimalsAndStableOrder() {
        UUID first=UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID second=UUID.fromString("10000000-0000-0000-0000-000000000002");
        UUID expensive=UUID.randomUUID();
        offer(second,"B","19.90","ACTIVE",null);
        offer(expensive,"C","39.90","ACTIVE","Descrição da oferta");
        offer(first,"A","19.90","ACTIVE",null);
        offer(UUID.randomUUID(),"Inativo","1.00","INACTIVE",null);
        var result=service.activePlans();
        assertThat(result).extracting(plan -> plan.id()).containsExactly(first,second,expensive);
        assertThat(result.getFirst().monthlyPrice()).isEqualByComparingTo(new BigDecimal("19.90"));
        assertThat(result.getFirst().volumeLimitLiters()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(result.getFirst().collectionLimit()).isEqualTo(3);
        assertThat(result.getFirst().description()).isNull();
        assertThat(result.getLast().description()).isEqualTo("Descrição da oferta");
    }

    @Test void emptyOrInactiveCatalogDoesNotInventFallbackPlans() {
        assertThat(service.activePlans()).isEmpty();
        offer(UUID.randomUUID(),"Indisponível","10.00","INACTIVE",null);
        assertThat(service.activePlans()).isEmpty();
    }

    @Test void anonymousHttpReturnsOnlyPublicFieldsFromActivePlans() throws Exception {
        UUID id=UUID.randomUUID();
        offer(id,"Oferta pública","19.90","ACTIVE",null);
        offer(UUID.randomUUID(),"Oferta oculta","9.90","INACTIVE",null);
        var response=mvc.perform(get("/api/v1/subscription-plans"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].monthlyPrice").value(19.90))
                .andExpect(jsonPath("$[0].volumeLimitLiters").value(123.45))
                .andExpect(jsonPath("$[0].collectionLimit").value(3)).andReturn();
        var plan=json.readTree(response.getResponse().getContentAsString()).get(0);
        assertThat(plan.propertyNames()).containsExactlyInAnyOrder("id","name","description",
                "monthlyPrice","volumeLimitLiters","collectionLimit");
        assertThat(plan.get("description").isNull()).isTrue();
    }

    @Test void emptyHttpCatalogIsAnEmptyArray() throws Exception {
        mvc.perform(get("/api/v1/subscription-plans"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test void publicReadDoesNotGrantAnonymousWriteOrAccessToOtherResources() throws Exception {
        mvc.perform(post("/api/v1/subscription-plans").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/subscription-plans/"+UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        assertThat(service.activePlans()).isEmpty();
    }

    @Test void openApiDescribesAnonymousReadAndItsResponseSchema() throws Exception {
        var response=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var operation=json.readTree(response.getResponse().getContentAsString())
                .get("paths").get("/api/v1/subscription-plans").get("get");
        assertThat(operation.get("security").isArray()).isTrue();
        assertThat(operation.get("security").size()).isZero();
        assertThat(operation.get("description").asText()).contains("Consulta pública","BRL");
        var schema=operation.get("responses").get("200").get("content").get("application/json").get("schema");
        assertThat(schema.get("type").asText()).isEqualTo("array");
        assertThat(schema.get("items").get("$ref").asText()).endsWith("/SubscriptionPlanResponse");
    }

    private void offer(UUID id,String name,String price,String status,String description) {
        owner.update("""
                INSERT INTO public.subscription_plan
                    (id,name,description,monthly_price,volume_limit_liters,collection_limit,status)
                VALUES (?,?,?, ?,123.45,3,?::public.active_status_t)
                """,id,name,description,new BigDecimal(price),status);
    }
}
