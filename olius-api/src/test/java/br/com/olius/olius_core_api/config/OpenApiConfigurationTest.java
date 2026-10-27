package br.com.olius.olius_core_api.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigurationTest {
    private final OpenApiConfiguration configuration = new OpenApiConfiguration();

    @Test
    void customizerSupportsAnEmptyDocument() {
        var api = new OpenAPI();
        configuration.identityContracts().customise(api);
        assertThat(api.getComponents().getSchemas()).containsKey("ApiProblem");
    }

    @Test
    void customizerCreatesResponsesAndPreservesSecurityRequirements() {
        var api = configuration.identityOpenApi();
        var web = new Operation();
        var mobile = new Operation();
        var logout = new Operation();
        var protectedOperation = new Operation();
        api.setPaths(new Paths()
            .addPathItem("/api/v1/auth/web/refresh", new PathItem().post(web))
            .addPathItem("/api/v1/auth/web/logout", new PathItem().post(logout))
            .addPathItem("/api/v1/auth/mobile/login", new PathItem().post(mobile))
            .addPathItem("/api/v1/users/me", new PathItem().get(protectedOperation)));
        api.getComponents().addSchemas("Login", new ObjectSchema().addProperty("password", new StringSchema()));
        configuration.identityContracts().customise(api);
        assertThat(web.getSecurity()).hasSize(1);
        assertThat(web.getSecurity().getFirst()).containsKeys("csrf", "refreshCookie");
        assertThat(logout.getSecurity().getFirst()).containsKeys("csrf", "refreshCookie").doesNotContainKey("bearer");
        assertThat(mobile.getSecurity()).isEmpty();
        assertThat(protectedOperation.getSecurity().getFirst()).containsKey("bearer");
        assertThat(web.getResponses()).containsKeys("400", "401", "403", "409", "429", "503");
        Schema<?> password = (Schema<?>) api.getComponents().getSchemas().get("Login").getProperties().get("password");
        assertThat(password.getWriteOnly()).isTrue();
    }

    @Test
    void pevOperationsDocumentPublicListingAndProtectedOwnerActions() {
        var api = configuration.identityOpenApi();
        var request = new Operation();
        var listing = new Operation();
        var own = new Operation();
        api.setPaths(new Paths()
            .addPathItem("/api/v1/pevs", new PathItem().post(request).get(listing))
            .addPathItem("/api/v1/pevs/me", new PathItem().get(own)));

        configuration.identityContracts().customise(api);

        assertThat(listing.getSecurity()).isEmpty();
        assertThat(request.getSecurity().getFirst()).containsKey("bearer");
        assertThat(own.getSecurity().getFirst()).containsKey("bearer");
        assertThat(request.getSummary()).isEqualTo("Solicitar PEV");
        assertThat(request.getDescription()).contains("status", "responsável");
        assertThat(request.getResponses()).containsKeys("201", "400", "401", "409");
        assertThat(listing.getSummary()).isEqualTo("Listar PEVs aprovados");
        assertThat(listing.getDescription()).contains("page", "size", "createdAt");
        assertThat(listing.getResponses()).containsKey("200");
        assertThat(own.getSummary()).isEqualTo("Consultar próprio PEV");
        assertThat(own.getResponses()).containsKeys("200", "401", "404");
    }

    @Test
    void administrativePevListingRequiresBearerAndHasItsOwnDescription() {
        var api=configuration.identityOpenApi();
        var listing=new Operation();
        var detail=new Operation();
        api.setPaths(new Paths().addPathItem("/api/v1/admin/pevs",new PathItem().get(listing))
            .addPathItem("/api/v1/admin/pevs/{id}",new PathItem().get(detail)));

        configuration.identityContracts().customise(api);

        assertThat(listing.getSecurity().getFirst()).containsKey("bearer");
        assertThat(listing.getSummary()).isEqualTo("Listar PEVs para gestão administrativa");
        assertThat(listing.getDescription()).contains("status", "ownerType", "createdAt");
        assertThat(listing.getResponses()).containsKey("200");
        assertThat(detail.getSecurity().getFirst()).containsKey("bearer");
        assertThat(detail.getSummary()).isEqualTo("Consultar detalhe administrativo de PEV");
        assertThat(detail.getDescription()).contains("approvedAt", "approvedBy", "404");
        assertThat(detail.getResponses()).containsKeys("200", "404");
    }
}
