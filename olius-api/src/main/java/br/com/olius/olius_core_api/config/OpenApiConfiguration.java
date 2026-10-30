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
            .description("Cadastro, identidade, PEV e pagamentos B2B. Perfil e permissões são controlados no servidor; motoristas não possuem login."))
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
            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                documentSecurity(path, method, operation);
                documentResponses(operation);
                operation.setDescription(description(path, method));
                documentPevResponse(path, method, operation);
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

    private boolean publicPath(String path, PathItem.HttpMethod method) {
        return (path.equals("/api/v1/pevs") && method == PathItem.HttpMethod.GET)
            || (path.equals("/api/v1/subscription-plans") && method == PathItem.HttpMethod.GET)
            || (path.equals("/api/v1/webhooks/stripe") && method == PathItem.HttpMethod.POST)
            || path.startsWith("/api/v1/auth/registrations/")
            || Set.of("/api/v1/auth/mobile/login", "/api/v1/auth/mobile/refresh",
                "/api/v1/auth/web/csrf", "/api/v1/establishment-types").contains(path);
    }

    private void documentSecurity(String path, PathItem.HttpMethod method, Operation operation) {
        operation.setSecurity(new ArrayList<>());
        if (path.startsWith("/api/v1/auth/web/") && !path.endsWith("/csrf")) {
            var requirement=new SecurityRequirement().addList(CSRF);
            if (path.endsWith("/refresh") || path.endsWith("/logout")) requirement.addList(REFRESH_COOKIE);
            operation.setSecurity(List.of(requirement));
        } else if (!publicPath(path, method)) {
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
    private void documentPevResponse(String path, PathItem.HttpMethod method, Operation operation) {
        if (path.equals("/api/v1/qr/resolutions") && method == PathItem.HttpMethod.POST) {
            operation.setSummary("Identificar cidadão por QR");
            operation.getResponses().computeIfAbsent("200", ignored -> new ApiResponse())
                .setDescription("Identificação mínima do cidadão ativo: ID e nome.");
        } else if (path.equals("/api/v1/pevs") && method == PathItem.HttpMethod.POST) {
            operation.setSummary("Solicitar PEV");
            operation.getResponses().computeIfAbsent("201", ignored -> new ApiResponse())
                .setDescription("PEV PENDING criado; Location aponta para /api/v1/pevs/me.");
        } else if (path.equals("/api/v1/pevs") && method == PathItem.HttpMethod.GET) {
            operation.setSummary("Listar PEVs aprovados");
            operation.getResponses().computeIfAbsent("200", ignored -> new ApiResponse())
                .setDescription("Página pública com endereço do ponto, sem dados pessoais do responsável.");
        } else if (path.equals("/api/v1/pevs/me") && method == PathItem.HttpMethod.GET) {
            operation.setSummary("Consultar próprio PEV");
            operation.getResponses().computeIfAbsent("200", ignored -> new ApiResponse())
                .setDescription("PEV vinculado ao titular autenticado.");
        } else if (path.equals("/api/v1/admin/pevs") && method == PathItem.HttpMethod.GET) {
            operation.setSummary("Listar PEVs para gestão administrativa");
            operation.getResponses().computeIfAbsent("200", ignored -> new ApiResponse())
                .setDescription("Página administrativa de PEVs com responsável e endereço do ponto.");
        } else if (path.equals("/api/v1/admin/pevs/{id}") && method == PathItem.HttpMethod.GET) {
            operation.setSummary("Consultar detalhe administrativo de PEV");
            operation.getResponses().computeIfAbsent("200", ignored -> new ApiResponse())
                .setDescription("Detalhe do PEV com responsável, endereço do ponto e dados de aprovação.");
        } else if (path.equals("/api/v1/admin/pevs/{id}/status") && method == PathItem.HttpMethod.PATCH) {
            operation.setSummary("Decidir ou alterar situação do PEV");
            operation.getResponses().computeIfAbsent("200", ignored -> new ApiResponse())
                .setDescription("Detalhe do PEV após a transição administrativa autorizada.");
        }
    }

    private String description(String path, PathItem.HttpMethod method) {
        if(path.equals("/api/v1/qr/resolutions") && method == PathItem.HttpMethod.POST)
            return "Responsável ativo por PEV APPROVED identifica cidadão ativo pelo token único do QR. Exige Bearer; retorna somente ID e nome. A leitura não altera o token, não registra entrega nem concede pontos. QR inválido ou desconhecido retorna 404; acesso sem PEV aprovado retorna 403. Autoidentificação é proibida.";
        if(path.equals("/api/v1/billing/renewal-checkouts")) return "Renovação manual pelo estabelecimento ativo. Corpo contém somente planId; Idempotency-Key UUID obrigatório. A Core identifica o ciclo mais recente e permite antecipar somente o próximo período. Não modifica o ciclo atual nem concede benefícios antes da confirmação. Preço/franquias são congelados no PostgreSQL. Reenvio reutiliza a cobrança e o webhook aplica a renovação.";
        if(path.equals("/api/v1/webhooks/stripe")) return "Entrada exclusiva de eventos Stripe snapshot em sandbox. Não usa JWT; exige Stripe-Signature válida sobre o corpo original. Evento válido é persistido antes do 204. Processamento financeiro ocorre depois, com consulta ao provedor e deduplicação. Não é um endpoint de confirmação manual pelo frontend.";
        if(path.equals("/api/v1/billing/initial-checkouts")) return "Estabelecimento ativo solicita contratação inicial. Corpo contém somente planId; header Idempotency-Key UUID obrigatório, preservado em reenvios. Preço/franquias vêm do PostgreSQL. Retorna a página Stripe, sem ativar assinatura. Reenvio com mesma chave/plano reutiliza a cobrança; conflito de estado/payload retorna 409. Integração desabilitada ou falha externa retorna 503.";
        if(path.equals("/api/v1/subscription-plans")) return "Consulta pública de planos ativos, ordenados por preço e ID. Preço em BRL; litros e coletas são limites por ciclo mensal. Catálogo vazio retorna []. Não cria cobrança nem concede benefícios; contratação revalida a oferta no servidor.";
        if(path.equals("/api/v1/pevs") && method == PathItem.HttpMethod.POST)
            return "Cidadão ou estabelecimento ativo solicita um PEV com endereço próprio. O responsável vem do Bearer; status, aprovação e responsável não são aceitos no corpo. Solicitação duplicada retorna 409.";
        if(path.equals("/api/v1/pevs") && method == PathItem.HttpMethod.GET)
            return "Consulta pública de PEVs APPROVED, ordenados por createdAt ASC e id ASC. page começa em 0; size padrão 20 e máximo 100. Deslocamento acima de Integer.MAX_VALUE retorna 400.";
        if(path.equals("/api/v1/pevs/me") && method == PathItem.HttpMethod.GET)
            return "Cidadão ou estabelecimento ativo consulta apenas o próprio PEV. Retorna 404 quando ainda não existe solicitação.";
        if(path.equals("/api/v1/admin/pevs") && method == PathItem.HttpMethod.GET)
            return "Somente ADMIN ativo. Lista todos os estados, com filtros opcionais status e ownerType (CITIZENS ou ESTABLISHMENT). page começa em 0; size padrão 20 e máximo 100. Ordem createdAt ASC, id ASC. Deslocamento acima de Integer.MAX_VALUE retorna 400.";
        if(path.equals("/api/v1/admin/pevs/{id}") && method == PathItem.HttpMethod.GET)
            return "Somente ADMIN ativo. Exibe o responsável e o endereço próprio do PEV, além de approvedAt e approvedBy quando houver aprovação. Não expõe CPF, CNPJ, e-mail, QR nem endereço pessoal. ID inexistente retorna 404.";
        if(path.equals("/api/v1/admin/pevs/{id}/status") && method == PathItem.HttpMethod.PATCH)
            return "Somente ADMIN ativo. Corpo: status de destino. Aceita PENDING para APPROVED/REJECTED, APPROVED para INACTIVE e INACTIVE para APPROVED; demais transições retornam 409. Aprovação inicial registra autor/data, inativação e reativação preservam esses dados; a auditoria registra cada ação.";
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
