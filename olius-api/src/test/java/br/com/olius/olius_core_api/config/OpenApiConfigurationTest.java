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
}
