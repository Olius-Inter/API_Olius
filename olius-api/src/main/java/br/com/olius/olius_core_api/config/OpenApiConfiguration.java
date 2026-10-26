package br.com.olius.olius_core_api.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.*;
import java.util.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfiguration {
    private static final String BEARER = "bearer";
    private static final String CSRF = "csrf";
    private static final String REFRESH_COOKIE = "refreshCookie";

    @Bean
    public OpenAPI identityOpenApi() {
        return new OpenAPI().info(new Info().title("OLIUS Core API").version("v1")
            .description("Cadastro e identidade. Perfil e permissões são controlados no servidor; motoristas não possuem login."))
            .components(new Components()
                .addSecuritySchemes(BEARER,new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme(BEARER).bearerFormat("JWT"))
                .addSecuritySchemes(CSRF,new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-XSRF-TOKEN"))
                .addSecuritySchemes(REFRESH_COOKIE,new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("olius_refresh")));
    }

    @Bean
    public OpenApiCustomizer identityContracts() {
        return api -> {
            if (api.getComponents() == null) api.setComponents(new Components());
            documentProblems(api.getComponents());
            documentPasswords(api.getComponents());
            if (api.getPaths() == null) return;
            api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                documentSecurity(path, operation);
                documentResponses(operation);
                operation.setDescription(description(path));
            }));
        };
    }

    private void documentProblems(Components components) {
        var problem=new ObjectSchema().addProperty("type",new StringSchema()).addProperty("title",new StringSchema())
            .addProperty("status",new IntegerSchema()).addProperty("detail",new StringSchema())
            .addProperty("code",new StringSchema()).addProperty("traceId",new StringSchema())
            .addProperty("instance",new StringSchema()).addProperty("timestamp",new StringSchema())
            .addProperty("errors",new ArraySchema().items(new ObjectSchema()));
        components.addSchemas("ApiProblem",problem);
    }

    private void documentPasswords(Components components) {
        if (components.getSchemas() == null) return;
        components.getSchemas().values().forEach(schema -> {
            if (schema.getProperties() != null && schema.getProperties().containsKey("password")) {
                Schema<?> password=(Schema<?>)schema.getProperties().get("password");
                password.setWriteOnly(true);
                password.setDescription("Cadastro: 10–128 caracteres, maiúscula, número e símbolo; espaços não são símbolo. Login não reaplica a política.");
            }
        });
    }

    private boolean publicPath(String path) {
        return path.startsWith("/api/v1/auth/registrations/")
            || Set.of("/api/v1/auth/mobile/login", "/api/v1/auth/mobile/refresh",
                "/api/v1/auth/web/csrf", "/api/v1/establishment-types", "/api/v1/subscription-plans").contains(path);
    }

    private void documentSecurity(String path, Operation operation) {
        operation.setSecurity(new ArrayList<>());
        if (path.startsWith("/api/v1/auth/web/") && !path.endsWith("/csrf")) {
            var requirement=new SecurityRequirement().addList(CSRF);
            if (path.endsWith("/refresh") || path.endsWith("/logout")) requirement.addList(REFRESH_COOKIE);
            operation.setSecurity(List.of(requirement));
        } else if (!publicPath(path)) {
            operation.addSecurityItem(new SecurityRequirement().addList(BEARER));
        }
    }

    private void documentResponses(Operation operation) {
        if (operation.getResponses() == null) operation.setResponses(new ApiResponses());
        var responses=operation.getResponses();
        responses.addApiResponse("400",error("Dados inválidos ou campos desconhecidos"));
        responses.addApiResponse("401",error("Credenciais, token ou sessão inválidos"));
        responses.addApiResponse("403",error("Perfil, titularidade ou CSRF não autorizado"));
        responses.addApiResponse("404",error("Recurso não encontrado"));
        responses.addApiResponse("409",error("Duplicidade ou conflito de cadastro"));
        responses.addApiResponse("429",error("Limite técnico de requisições excedido"));
        responses.addApiResponse("500",error("Falha interna, sem detalhes sensíveis"));
        responses.addApiResponse("503",error("Serviço temporariamente indisponível"));
    }

    private ApiResponse error(String description) {
        return new ApiResponse().description(description).content(new Content().addMediaType("application/problem+json",
            new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiProblem"))));
    }
    private String description(String path) {
        if(path.equals("/api/v1/subscription-plans")) return "Consulta pública de planos ativos, ordenados por preço e ID. Preço em BRL; litros e coletas são limites por ciclo mensal. Catálogo vazio retorna []. Não cria cobrança nem concede benefícios; contratação revalida a oferta no servidor.";
        if(path.endsWith("/csrf")) return "Obter token CSRF e cookie; enviar token no header das operações web.";
        if(path.endsWith("/refresh")) return "Rotacionar refresh token. Web usa cookie HttpOnly; Mobile usa corpo JSON. Reutilização revoga a sessão.";
        if(path.equals("/api/v1/auth/web/logout")) return "Cookie refresh e CSRF encerram a sessão, sem exigir access válido. Cookie ausente/inválido: 204 idempotente. Falha de banco: erro e cookie removido.";
        if(path.endsWith("/login")) return "Credenciais válidas e conta ativa geram JWT de 15 minutos e sessão renovável.";
        if(path.equals("/api/v1/auth/session")) return "Contrato com a segunda API: encaminhar Bearer do usuário, verificar identidade e revogação online. Retorna dados mínimos.";
        if(path.startsWith("/api/v1/admin/")) return "Somente ADMIN ativo. Criar administrador ou alterar status, preservando histórico; inativação revoga sessões e não pode remover o último ADMIN ativo (409).";
        if(path.startsWith("/api/v1/auth/registrations/")) return "Cadastro público atômico, ativo sem confirmação por e-mail. Nenhum privilégio, timestamp, saldo ou status é controlado pelo cliente.";
        if(path.startsWith("/api/v1/users/")) return "Titular consulta o próprio cadastro; ADMIN pode consultar outro usuário. CPF/CNPJ e QR restritos a esse acesso.";
        return "Operação de identidade; consulte docs/identidade-seguranca.md para o fluxo completo.";
    }
}
