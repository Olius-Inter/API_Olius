# Segurança da Core API — OLIUS

## Finalidade e escopo

Esta parte do sistema permite que cidadãos e estabelecimentos criem suas contas, façam login e consultem os próprios dados. Administradores possuem acesso de gestão; motoristas continuam sendo participantes operacionais sem login. O servidor verifica identidade, perfil e titularidade em cada operação protegida.

O PostgreSQL é a fonte de verdade para contas, sessões e revogações. Este documento descreve a configuração implementada da segurança e seus contratos de uso. A autorização das futuras funcionalidades deve reutilizar essa base e acrescentar as verificações de titularidade próprias de cada recurso.

## Como entender a segurança

**Autenticação** verifica quem está fazendo a requisição. **Autorização** verifica o que essa pessoa pode fazer. Ter um JWT válido não permite acessar qualquer conta: a API também confere o perfil e a titularidade do recurso.

Há três perfis autenticados: `CITIZENS`, `ESTABLISHMENT` e `ADMIN`. Um PEV não é um perfil de login; ser responsável por ele é um vínculo de negócio. Motoristas não possuem login. A conta PostgreSQL usada pela aplicação é uma credencial técnica distinta dos usuários que fazem login no site ou aplicativo.

O access token é um comprovante temporário, assinado pela Core, enviado como `Authorization: Bearer <token>`. O refresh token é um segredo usado para renovar esse comprovante. Ambos são criados no login: o refresh não aparece somente depois dos primeiros 15 minutos. A renovação fornece um novo access e substitui o refresh anterior.

A sessão persistida no PostgreSQL permite encerrar o acesso antes do vencimento do JWT. O Spring Security não usa uma sessão HTTP (`HttpSession`) para autenticar o usuário: cada chamada protegida precisa de Bearer, enquanto a sessão de autenticação de negócio é consultada no banco.

## Regras de cadastro e tokens

- Cadastro público separado para cidadão e estabelecimento. A conta nasce ACTIVE; confirmação por e-mail não faz parte deste fluxo inicial.
- E-mail: parte anterior ao @ preserva maiúsculas/minúsculas. O domínio é convertido para minúsculas; espaços externos são removidos pelo normalizador. A entrada ainda precisa passar por Bean Validation. Não é utilizado citext nem uma consulta case-insensitive.
- Telefone opcional, armazenado conforme o banco, com 10–15 caracteres. Um número pode aparecer em contas diferentes, mas não repetir no mesmo cadastro. Não foi adicionada uma normalização ou validação de DDD que o contrato não definiu.
- Senha de cadastro: 10–128 caracteres, pelo menos uma letra maiúscula, um número e um símbolo. Espaços são preservados e não satisfazem símbolo. Não existe exigência de letra minúscula. Hash Argon2id, salt aleatório, memória 19 MiB, duas iterações e paralelismo 1.
- CPF/CNPJ enviados sem pontuação e com 11/14 dígitos. Isso valida formato e unicidade, não autenticidade documental nem dígitos verificadores.
- JWT RS256 dura 900 segundos (15 minutos). Claims incluem sub, sid, iss, aud, iat, exp e jti, sem e-mail, documento ou senha. O perfil autorizado vem do estado atual no banco.
- Refresh token opaco de 32 bytes aleatórios, codificado em Base64 URL-safe sem padding. Somente seu hash SHA-256 é enviado às funções do banco.
- Inatividade de sessão: 360 horas para cidadão/estabelecimento, 168 horas para administrador. Uma rotação válida renova a janela. Não há limite absoluto adicional aprovado.
- Logout encerra a sessão atual; logout de todas encerra as sessões do titular. Inativação administrativa revoga as sessões; reativar a conta exige novo login. Reutilizar refresh consumido revoga aquela sessão, não todas as contas.
- Alteração de senha/e-mail/perfil e recuperação de senha não fazem parte destes endpoints. Não foi introduzida uma política nova de revogação para essas alterações.

## Organização da implementação

| Camada | Responsabilidade |
|---|---|
| controller | Contrato REST, DTOs, cookies e respostas HTTP |
| service | Cadastro atômico, consulta com titularidade, autenticação e transações |
| repository | Spring Data JPA para cadastros; JDBC para functions oficiais de sessão |
| entity | Mapeamento fiel das tabelas, tipos nativos e campos controlados pelo banco |
| dto | Entradas específicas e respostas que não expõem hashes nem entidades |
| security | Validação JWT, consulta de sessão, cookies e filtros anteriores ao MVC |
| config | SecurityFilterChain, CORS, CSRF, chaves e OpenAPI |
| validation | Política estrutural da senha |
| exception | Problem Details existente, com resposta 429 para o limite técnico |

As especializações reutilizam o UUID de users. Os relacionamentos JPA são lazy e os DTOs são montados dentro do Service. O estado da conta, timestamps, saldo e flags derivadas não são inseridos livremente pelo ORM. Colunas CHAR e ENUM têm mapeamentos PostgreSQL explícitos. Campos gerados de perfil/endereço nas especializações permanecem responsabilidade do banco.

## Fluxos

### Cadastro

Controller valida o DTO → RegistrationService inicia transação e define auditoria → grava users → cria QR aleatório → grava cidadão ou endereço e estabelecimento → grava telefones → confirma tudo.

O tipo do cadastro é definido pela rota. Campos extras como userType, status, points ou createdAt são rejeitados. Falha de CPF, CNPJ, e-mail ou telefone desfaz usuário, vínculos e auditoria daquela tentativa. A consulta pública de tipos de estabelecimento permite selecionar um ID existente; esta sprint não inventa nem popula o catálogo oficial.

### Login e requisição protegida

Login valida senha com PasswordEncoder; conta inexistente também executa verificação de hash fictício para reduzir diferenças óbvias de tempo. Senha incorreta, conta inexistente ou inativa retornam o mesmo 401 público.

Credenciais válidas → open_auth_session → commit da sessão → emissão de JWT e refresh. Cada requisição protegida valida assinatura RS256, emissor, audiência, tempo e vínculo sub/sid. SessionJwtConverter consulta o PostgreSQL e monta a autoridade atual. Uma conta ou sessão revogada deixa de funcionar mesmo que o JWT ainda não tenha vencido.

### Renovação e reutilização

Refresh → hash atual e próximo hash → SessionTransactions.rotate → função oficial → commit → AuthenticationService interpreta o resultado e emite nova resposta.

SessionTransactions usa REQUIRES_NEW para que a transação termine antes de a rejeição virar uma exceção HTTP. Quando a função retorna REFRESH_REUSED, a sessão já foi revogada. O commit preserva essa revogação e somente depois a API retorna 401. Exceções inesperadas dentro da operação continuam provocando rollback.

O cliente deve coordenar uma única renovação por sessão de cada vez. Duas requisições simultâneas com o mesmo refresh podem caracterizar reutilização e revogar a sessão. Não existe uma janela de tolerância que não tenha sido aprovada. Evitar tentativas automáticas de repetição indiscriminadas.

### Segurança e erros

Falhas MVC são tratadas por GlobalExceptionHandler. Autenticação e autorização nos filtros utilizam ProblemAuthenticationEntryPoint e ProblemAccessDeniedHandler. DatabaseSecurityFailureFilter padroniza falhas de banco anteriores ao ControllerAdvice sem expor a mensagem SQL. As respostas utilizam application/problem+json e X-Request-Id.

## Segurança do acesso ao PostgreSQL

O datasource de execução utiliza uma conta membro de `olius_api`, sem ser proprietária das tabelas ou superusuária. A role tem permissões por objeto e coluna. Ela não recebe acesso direto às tabelas de sessões, hashes de refresh ou logs; opera sessões pelas functions públicas abaixo. Não existe uma conta PostgreSQL por cidadão nem RLS para substituir a autorização no backend.

| Function | Uso |
|---|---|
| `open_auth_session` | Abre sessão depois de a Core conferir a senha; fornecer um UUID sozinho não autentica ninguém. |
| `rotate_auth_refresh_token` | Confere o hash do segredo apresentado, consome o refresh e registra o próximo. |
| `get_auth_session_status` | Verifica titularidade, perfil, validade e revogação; não prolonga a sessão. |
| `revoke_auth_session` | Encerra uma sessão do titular. |
| `revoke_user_auth_sessions` | Encerra todas as sessões do titular. |

A senha do usuário é protegida com Argon2id. O refresh, por ser um segredo aleatório de alta entropia, é representado no banco por SHA-256. São mecanismos com finalidades diferentes; nenhum desses hashes deve aparecer em respostas ou logs.

Escritas de cadastro usam `AuditContext` na mesma transação: `app.audit_actor`, `app.current_user_id`, `app.operational_driver_id` e `app.audit_reason` são configurados localmente, incluindo valores vazios quando não se aplicam. Isso impede que a conexão reutilizada pelo pool carregue autoria de outra operação. O cliente não escolhe esse contexto. As functions de sessão controlam seu próprio contexto de auditoria.

## Endpoints

Prefixo: /api/v1. Corpos são JSON. Respostas de autenticação não devem ser armazenadas em cache.

| Método e rota | Acesso | Resultado |
|---|---|---|
| POST /auth/registrations/citizens | Público | 201: id e userType |
| POST /auth/registrations/establishments | Público | 201: id e userType |
| GET /establishment-types | Público | Tipos existentes |
| GET /auth/web/csrf | Público | Header CSRF, token mascarado e cookie correspondente |
| POST /auth/web/login | CSRF | Access token no JSON; refresh somente em cookie |
| POST /auth/web/refresh | Cookie refresh + CSRF | Rotação de tokens |
| POST /auth/mobile/login | Público | Access e refresh no JSON |
| POST /auth/mobile/refresh | Refresh no JSON | Rotação de tokens |
| GET /users/me | Bearer válido | Perfil próprio com documento, QR e vínculos |
| GET /users/{id} | Titular ou ADMIN | Perfil autorizado |
| GET /auth/session | Bearer válido | Identidade mínima e validade, para integração |
| POST /auth/web/logout | Cookie refresh + CSRF | 204 e exclusão do cookie, sem access válido |
| POST /auth/mobile/logout | Bearer | 204 |
| DELETE /auth/sessions | Bearer | 204: encerra todas as sessões do titular |
| POST /admin/invitations | ADMIN | 201: convite emitido e entregue pelo adapter; 503 sem serviço de entrega |
| GET /admin/invitations e /admin/invitations/{id} | ADMIN | Metadados sem segredo/hash |
| POST /admin/invitations/{id}/revocation | ADMIN | 204, inclusive se já revogado |
| POST /auth/admin-invitations/acceptance | Público com segredo | 204: conta criada; realizar login normal |
| PATCH /admin/users/{id}/status | ADMIN | 204: ACTIVE ou INACTIVE |

Erros principais: 400 entrada inválida/campo desconhecido; 401 autenticação inválida; 403 perfil/titularidade/CSRF; 404 recurso inexistente; 409 duplicidade; 429 excesso de requisições; 500 falha interna; 503 indisponibilidade do banco.

### Exemplo de cadastro B2C

```json
{
  "name": "Pessoa de exemplo",
  "email": "Pessoa@example.test",
  "password": "Exemplo123!",
  "cpf": "12345678901",
  "telephones": ["11999999999"]
}
```

É um exemplo fictício de formato, não uma comprovação de CPF ou uma senha recomendada para uso real. Para B2B, substituir cpf por cnpj, incluir typeId e address com state, city, neighborhood, street, number e cep. description, complement e coordenadas são opcionais.

## Navegador, Mobile e segunda API

### Navegador

1. GET /auth/web/csrf com credentials: include. Ler headerName/token da resposta. O token retornado é mascarado pelo Spring; não extrair o valor bruto do cookie.
2. Enviar login com Content-Type: application/json, credentials: include e o header indicado.
3. Guardar somente o access token na memória. O refresh fica no cookie host-only, HttpOnly, Secure, SameSite=Strict e path=/api/v1/auth/web.
4. No vencimento, chamar /auth/web/refresh com cookie e CSRF, sem anexar um Bearer já expirado.
5. Após recarregar a página, obter CSRF e renovar usando o cookie persistido. Não colocar o refresh em localStorage.
6. Logout Web usa cookie refresh e CSRF, sem exigir access válido nem renovação anterior. Um Bearer enviado nessa rota é ignorado. Cookie ausente, inválido, expirado ou revogado resulta em 204 idempotente. O cookie é apagado mesmo se a revogação falhar; nesse caso a resposta informa a falha e não garante revogação no servidor. Logout de todas continua exigindo Bearer válido e invalida as sessões mesmo que um cookie permaneça armazenado.

Em outra origem, configurar a lista exata de origens e credentials: include. A decisão de hospedagem ainda será necessária para avaliar SameSite. None exige Secure/HTTPS. CORS não substitui autenticação nem CSRF. O perfil local desativa Secure apenas para estudo via HTTP.

### Mobile

Usar /auth/mobile/login e /auth/mobile/refresh. Guardar o refresh em Keychain/Keystore ou mecanismo equivalente; access token em memória. As rotas mobile não leem nem alteram o cookie web e não aceitam autenticação de sessão HTTP. A proteção contra CSRF da rota web não pode ser contornada trocando de rota, pois a rota mobile exige o segredo no corpo.

### Segunda API

Encaminhar o access token original do usuário em Authorization: Bearer para GET /auth/session. A resposta contém userId, sessionId, userType e sessionExpiresAt. A segunda API aplica suas permissões e titularidade usando essa identidade. Não acessar as tabelas de sessão, não enviar ID arbitrário como prova de identidade e não continuar autorizando quando a Core negar ou ficar indisponível. Não foi implementada a segunda API nesta sprint.

## Configuração local e execução

1. Instalar os scripts atuais e os papéis no banco de desenvolvimento conforme o contrato oficial. A conta do datasource deve ser membro de olius_api, sem ser proprietária ou superusuária.
2. Preencher .env usando .env.example como referência. A senha do datasource é independente da senha dos usuários da aplicação.
3. Para estudo, ativar explicitamente o perfil local (`--spring.profiles.active=local`). Ele gera chaves efêmeras e permite cookie em HTTP. Reiniciar invalida access tokens; refresh persistente pode gerar novos access tokens.
4. Para chaves persistentes, executar scripts/Generate-JwtKeys.ps1 com PowerShell 7 e configurar os caminhos privados/públicos. O script não sobrescreve arquivos existentes. A chave privada deve permanecer restrita ao operador/processo; o par não deve ser versionado.
5. Fora do perfil local, o modo efêmero fica desativado, Secure fica ativo e os caminhos das chaves são obrigatórios. Não há segredo JWT padrão no código.

O antigo bootstrap por `FirstAdminRunner`/senha de ambiente foi removido. O primeiro ADMIN nasce por aceite de convite emitido por operador autorizado usando `issue_bootstrap_admin_invitation` na implantação. A role da Core não pode emitir esse convite, nem existe endpoint público de bootstrap. Nenhuma conta ou convite real é criado na inicialização da API. As antigas variáveis `OLIUS_INITIAL_ADMIN_*` e `olius.admin-bootstrap.enabled` não provisionam mais contas. A preparação operacional e entrega real do convite inicial pertencem à segunda sprint administrativa.

### Variáveis de configuração

| Variável | Finalidade |
|---|---|
| `CONTAINER_DEV_URL`, `CONTAINER_DEV_USER`, `CONTAINER_DEV_PASSWORD` | URL JDBC e credenciais limitadas do datasource da Core. |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | URL JDBC e conta limitada de produção; fornecer na hospedagem. |
| `CONTAINER_DEV_NAME` | Banco inicial criado pelo Compose em um volume novo. |
| `CONTAINER_DEV_ADMIN_USER`, `CONTAINER_DEV_ADMIN_PASSWORD` | Credenciais de inicialização do PostgreSQL pelo Compose; distintas das credenciais da Core. |
| `OLIUS_JWT_PRIVATE_KEY_PATH`, `OLIUS_JWT_PUBLIC_KEY_PATH` | Caminhos dos arquivos PEM usados para assinar e verificar JWT. |
| `OLIUS_JWT_EPHEMERAL_KEYS` | Padrão comum false; local fixa true e prod fixa false. |
| `OLIUS_COOKIE_SECURE` | Padrão comum true; local fixa false e prod fixa true. |
| `OLIUS_COOKIE_SAME_SITE` | Política entre sites dos cookies; padrão Strict. None exige Secure. |
| `OLIUS_ALLOWED_ORIGINS` | Origens exatas autorizadas pelo CORS; sem origem liberada por padrão. |
| `OLIUS_AUTH_REQUESTS_PER_MINUTE` | Limite técnico de requisições de autenticação por IP; padrão 60. |
| `OLIUS_API_DOCS_ENABLED` | Habilita OpenAPI/Swagger; padrão false, habilitado no perfil local. |

O `application-local.properties` importa opcionalmente o `.env` local. Não versionar credenciais, chaves privadas ou arquivos reais de ambiente. Usar `.env.example` como referência, sem dados sensíveis. A configuração exige um par RSA correspondente, com tamanho mínimo de 2048 bits. Manter as chaves persistentes preserva a capacidade de verificar tokens após reinício.

### Limites técnicos e implantação

Login, cadastro, refresh e aceite de convite possuem limite técnico configurável de 60 requisições por minuto por IP observado, por instância. Resposta 429 inclui Retry-After. O armazenamento tem capacidade máxima; entradas ativas não são descartadas para contornar o limite. Isso não bloqueia permanentemente contas nem introduz punições de negócio.

O filtro não confia em X-Forwarded-For enviado pelo cliente. Em implantação atrás de proxy confiável ou com múltiplas instâncias, configurar a fronteira de rede e um limite compartilhado no gateway; a escolha depende da hospedagem. Revisar o custo Argon2 sob a carga do ambiente final. O acesso a CPF, CNPJ e QR exige perfil/titularidade e não deve ser copiado para logs.

## OpenAPI e Swagger

Habilitar OLIUS_API_DOCS_ENABLED=true ou o perfil local. JSON em /v3/api-docs e interface em /swagger-ui/index.html. A documentação deriva dos controllers/DTOs e descreve Bearer, cookie refresh, CSRF e erros padronizados. Usar o fluxo Mobile para experimentação inicial no Swagger, com valores fictícios. A opção de documentação não permite ignorar a autorização dos endpoints.

## Como verificar a configuração

IdentitySecurityIntegrationTest instala o banco atual em container descartável, conecta a aplicação como olius_test_api e percorre a cadeia real de segurança. Observações administrativas são exclusivas das fixtures dos testes. Nenhum teste utiliza olius_dev ou produção.

Com Java 21 e Docker Desktop ativos, executar `mvnw.cmd verify` no Windows, na pasta da API. O container é criado e encerrado pelo ciclo de testes; não é necessário manter um serviço PostgreSQL adicional no CI.

Os SQL de `src/test/resources/db/olius` são cópias oficiais revisadas em 20/10/2026, com hashes no `snapshot-manifest.json`. A fixture instala 01–08, estrutura/view/carga do catálogo e roles; Data Mart e views anuais são dependências das permissões atuais. Não executa refresh manual 09, contas pessoais/técnicas reais, massa de demonstração ou migração de ambientes existentes. São 61 tabelas em public: 58 dos scripts principais e três do catálogo. Essas cópias não são migrations de desenvolvimento/produção.

Os testes verificam cadastro e vínculos, rollback e auditoria, política de senha, e-mail sensível a maiúsculas, hash Argon2id, JWT, conta inativa, titularidade, permissões administrativas, rotação e reutilização com commit, logout, CSRF real e CORS. AuthDatabaseContractTest continua validando as funções e privilégios diretamente. O CI já executa mvnw verify e descobre os novos testes automaticamente.

Para aprender, acompanhe primeiro RegistrationService, depois AuthenticationService/SessionTransactions e por último SecurityConfiguration/SessionJwtConverter. Exercícios opcionais: desenhar o caminho de um 401 por token inválido; explicar por que a revogação precisa de commit; escrever um novo teste de expiração usando a estrutura existente. A implementação necessária à sprint não depende desses exercícios.

### Resultado histórico da verificação

Em 08/10/2026, a execução completa de `mvnw verify` terminou com BUILD SUCCESS: 100 testes executados, sem falhas, erros ou testes ignorados. O JAR da aplicação e o relatório JaCoCo foram gerados. A suíte inclui proteção contra tentativas de contornar CSRF e o limite de autenticação por caminhos codificados na URL, além do carregamento de chaves RSA persistentes e do provisionamento controlado do primeiro administrador.

Esta evidência corresponde à execução local com PostgreSQL descartável via Testcontainers. Não comprova execução remota do GitHub Actions nem implantação em produção. Os bancos reais e os scripts oficiais externos não foram alterados nesta sprint.

### Ambientes locais e volumes existentes

O `.env.example` documenta as variáveis usadas pela Core e pelo Compose. O `.env` local pode conter configurações opcionais e valores antigos; `DB_URL`, `DB_USER` e `DB_PASSWORD` são consumidos exclusivamente pelo perfil prod, usando variáveis fornecidas pela hospedagem. O perfil prod não importa o `.env` local.

O Compose usa `CONTAINER_DEV_ADMIN_USER`/`CONTAINER_DEV_ADMIN_PASSWORD`; a Core no perfil local usa `CONTAINER_DEV_USER`/`CONTAINER_DEV_PASSWORD`. Na atualização de um ambiente existente, os valores administrativos foram copiados das variáveis antigas, sem alterar os dados, recriar containers ou mudar a senha do banco. A conta configurada no datasource também foi preservada: isso não comprova que já seja uma conta limitada. Verificar que corresponda a `olius_core_api`, membro apenas de `olius_api`, conforme os scripts oficiais. Se ainda for administrativa, provisionar a conta técnica e sua senha antes de trocar o datasource; não basta renomear uma variável para criar esse usuário.

As variáveis POSTGRES inicializam apenas volumes novos. Alterá-las não muda usuários ou senhas de um volume existente. Não remover o volume para aplicar esta organização. O healthcheck usa o usuário/banco do próprio container. O `.env` continua ignorado pelo Git; somente o modelo é versionado.


### Seleção de ambiente

- `application.properties`: configurações comuns de JPA, validação, segurança e documentação. Não escolhe um banco nem carrega `.env`.
- `application-local.properties`: datasource CONTAINER_DEV_*, importação opcional do `.env`, JWT temporário, cookie HTTP e Swagger habilitado para desenvolvimento.
- `application-prod.properties`: datasource DB_*, chaves persistentes e cookie Secure obrigatório na configuração do perfil. Exige caminhos dos PEM válidos e conexão com o schema já instalado; as particularidades de TLS da conexão JDBC dependem do provedor e devem constar da URL/configuração de implantação.

Selecionar **um único perfil** no ambiente de execução: `SPRING_PROFILES_ACTIVE=local` no IntelliJ; `SPRING_PROFILES_ACTIVE=prod` na hospedagem. Alternativamente usar `--spring.profiles.active=local` como argumento. Não tentar selecionar o perfil pelo `.env`, pois sua importação ocorre somente depois de local estar ativo. Não combinar local e prod: um é destinado ao computador de desenvolvimento e o outro à implantação.

Sem perfil não existe datasource padrão: é necessário fornecer uma conexão explicitamente, como fazem os testes via DynamicPropertySource, ou selecionar o ambiente. Isso impede usar o banco local como fallback acidental de produção. Os testes atuais continuam fornecendo seu próprio datasource e chaves efêmeras, sem depender do `.env` e sem exigir perfil prod/local. Não aplicar o perfil local à configuração de execução dos testes.

O `.env` existente e as credenciais nele foram preservados. O arquivo não é lido automaticamente pelo Spring ou pela hospedagem no perfil prod. No Docker Compose, o carregamento do `.env` para interpolação do YAML é um comportamento separado do carregamento de propriedades pelo Spring.


A seleção e o isolamento dos arquivos de perfil são verificados por `ProfileConfigurationTest`, sem banco: local usa CONTAINER_DEV_*, prod usa DB_* sem importar `.env`, e a configuração comum não seleciona banco. A execução completa com Testcontainers continua exigindo Docker disponível.


### Revisão CSRF e análise estática da PR #9

A regra Sonar `java:S4502` sinaliza qualquer chamada a `requireCsrfProtectionMatcher`, mesmo quando restringe a verificação a operações com autenticação por cookie. A política foi revisada: login, refresh e logout sob `/api/v1/auth/web/**` exigem CSRF em métodos de escrita; as outras operações não autenticam por cookie, HTTP Basic ou HttpSession, exigindo Bearer explícito ou credenciais/refresh no JSON. CORS não é usado como substituto dessa proteção.

A exceção de análise estática está limitada ao método `SecurityConfiguration.browserCsrf`, com justificativa no código, sem exclusões globais ou desativação do CSRF. Os testes HTTP verificam token ausente/incorreto, caminho codificado, logout Web mesmo com Bearer válido e renovação Web com cookie. Novas rotas que autentiquem por cookie precisam entrar na proteção CSRF e em testes correspondentes; não devem ser adicionadas implicitamente às exceções.


O filtro padrão `BearerTokenAuthenticationFilter` é registrado explicitamente com `JwtAuthenticationProvider`, o decoder e `SessionJwtConverter`. Isso mantém assinatura, claims e consulta de sessão existentes, sem a dispensa automática de CSRF por Bearer adicionada pela DSL de Resource Server. Assim, ter um JWT válido não dispensa o token CSRF em operações Web. Não foi criado um parser JWT próprio.

Validação das correções da PR #9 em 14/10/2026: `mvnw -o verify` concluiu com BUILD SUCCESS, 105 testes executados, nenhuma falha, nenhum erro e nenhum teste ignorado, com JAR e relatório JaCoCo gerados. A opção offline utiliza o cache Maven e não evita os testes HTTP ou o PostgreSQL descartável do Testcontainers. A análise remota do Sonar depende de novo commit/push; não foi simulada como aprovada localmente.

### Proteção da administração e das chaves

A API impede inativar o último ADMIN ativo com HTTP 409 (`BUSINESS_STATE_CONFLICT`). A autoinativação é permitida quando há outro ADMIN ativo. Um bloqueio transacional no PostgreSQL serializa as mudanças de status entre instâncias; a conta de quem executa a operação é revalidada após o bloqueio. Essa proteção vale para operações pela API: alterações administrativas diretas no banco devem respeitar a mesma política.

O logout Web valida o refresh pelas funções existentes: rotação e revogação ocorrem na mesma transação, sem entregar ou usar o sucessor. Isso preserva a revogação defensiva quando um refresh consumido é reapresentado, sem liberar acesso direto às tabelas de sessão. Não há alteração de schema.

`Generate-JwtKeys.ps1` exige PowerShell 7. No Windows, a chave privada nasce com herança de permissões desativada e acesso somente ao usuário executor; em sistemas Unix, nasce com modo 0600. Uma conta de serviço diferente precisa receber explicitamente acesso de leitura. O script não sobrescreve arquivos existentes. Na inicialização, a aplicação verifica tamanho, módulo e assinatura/verificação real do par RSA antes de utilizá-lo.


## Administração por convite — primeira sprint

### O que o fluxo faz

Um ADMIN ativo convida uma pessoa por nome e e-mail. O destinatário só ganha conta ao aceitar o convite e definir sua própria senha. Ninguém recebe senha provisória e o aceite não cria uma sessão: é preciso efetuar o login normal.

O segredo tem 32 bytes aleatórios e representação Base64URL de 43 caracteres. A Core transmite ao banco SHA-256 dos bytes ASCII dessa representação; o aceite usa exatamente a mesma transformação. Somente o segredo original é entregue ao destinatário. Nem o segredo nem o hash são retornados em listagens, logs ou DTOs de sucesso. Records de entrada e mensagem possuem `toString` redigido.

Convites usam e-mail integralmente em minúsculas. A regra já existente de cidadãos/estabelecimentos permanece: parte local sensível a maiúsculas e domínio normalizado. Um ADMIN convidado utiliza no login o e-mail em minúsculas informado no convite. O banco impede convite/aceite para e-mail ocupado, inclusive variantes de caixa de contas existentes. Não existe promoção automática.

### Sequência de emissão

1. O controller deriva o autor do JWT; `@PreAuthorize` exige ADMIN.
2. O serviço exige um `AdminInvitationDelivery` instalado. Nesta primeira sprint ele existe somente nos testes. Sem adapter, retorna 503 ANTES de gravar convite.
3. Gera novo segredo e chama `AdminInvitationTransactions.issue`, em `REQUIRES_NEW`.
4. `AdminRegistry` verifica a coordenação oficial e o autor ativo; a função `issue_admin_invitation` revoga o anterior aberto e cria o novo com 24 horas.
5. Somente depois do commit o serviço chama o adapter de entrega, sem transação de banco aberta.
6. Sucesso retorna 201, Location e metadados. Falha externa retorna 503 com `ADMIN_INVITATION_DELIVERY_FAILED`; o convite permanece no banco. Consultar antes de reenviar. Uma nova emissão para o mesmo e-mail sempre gera outro segredo e invalida o anterior, inclusive se expirado.

`Propagation.NEVER` impede chamar o orquestrador dentro de outra transação. Não existe retry oculto, segredo armazenado em fila ou transação distribuída com e-mail. A confirmação de entrega significa o retorno bem-sucedido do adapter; o provedor e seus limites serão definidos na segunda sprint.

### Aceite, revogação e status

`POST /api/v1/auth/admin-invitations/acceptance` recebe apenas `token` e `password`, valida Bean Validation e a política existente, calcula Argon2id e chama `accept_admin_invitation`. Nome, e-mail e perfil vêm do convite. O endpoint é público especificamente para permitir o primeiro acesso e compartilha o limite por IP das operações de autenticação. Resposta 204 sem JWT, refresh ou cookie de autenticação.

Revogação utiliza `revoke_admin_invitation`; revogar novamente resulta em 204, mas convite aceito é conflito. Listagens aceitam `state`, `page` (mínimo zero), `size` (1–100; padrão 20), com ordem issued_at/ID decrescente. A situação exibida deriva de aceite, revogação e prazo; AVAILABLE não comprova que o emissor continua ativo. O banco revalida o emissor no aceite.

O endpoint existente de status continua atendendo usuários: para ADMIN usa `set_admin_status`; para os outros perfis mantém UPDATE permitido/auditoria. A garantia do último ADMIN vem da função/trigger do banco. A conta pode inativar a si própria quando outro ADMIN permanece; a trigger revoga suas sessões. Não há contagem/advisory lock alternativo na Core.

### Segurança de consultas e erros

A consulta dos convites seleciona explicitamente as colunas autorizadas. Não há entidade JPA de convite carregando hash nem acesso HTTP a `admin_guard_state`/logs. A criação direta `POST /api/v1/admin/users` foi removida; ADMIN recebe 404 nessa rota. Perfis não administrativos continuam proibidos pela segurança.

A função `lock_admin_registry()` é concedida à Core pelo contrato oficial. O adaptador a usa no início das transações de escrita para verificar e manter a coordenação antes de chamar a função de negócio. Se faltar a linha de controle, a falha permanece interna (500). A chamada seguinte reacquire a mesma trava na mesma transação; incrementos de revision são técnicos, não eventos de negócio.

O tradutor local identifica rejeições intencionais por SQLSTATE e pelo campo estruturado `routine=exec_stmt_raise` do protocolo PostgreSQL 16. Não interpreta mensagens nem WHERE/SQL textuais. Conflitos de convite/status produzem 409; autor conhecido inativo é 403; recurso inexistente é 404. No aceite, emissor inativo é conflito genérico sem dados do emissor. ACL real, controle ausente e falhas desconhecidas permanecem internas. Serialização/deadlock seguem a tradução existente para 409, sem repetição automática.

### Limites da primeira sprint

Há adapter de entrega controlado exclusivamente em testes; não existe envio real, impressão do link no console ou segredo devolvido ao ADMIN. Em execução normal, emissão permanece indisponível até instalar o adapter real da segunda sprint. O aceite pode consumir convites emitidos por implantação autorizada. Provedor/remetente, URL frontend, procedimento completo de bootstrap e homologação de e-mail são a próxima entrega.

Scripts oficiais externos e bancos existentes não foram alterados. A atualização de um ambiente existente deve ser revisada com o responsável pelo banco usando o mecanismo oficial; não executar os scripts de criação sobre uma base pronta.


### Exemplos de contrato dos convites

Emissão e reenvio: `POST /api/v1/admin/invitations`, com Bearer de ADMIN ativo. O mesmo e-mail em uma nova emissão substitui o convite anterior aberto.

```json
{
  "name": "Nome do administrador",
  "email": "administrador@example.invalid"
}
```

Aceite: `POST /api/v1/auth/admin-invitations/acceptance`, sem login prévio. Os valores abaixo são marcadores; usar o segredo recebido e uma senha que atenda à política.

```json
{
  "token": "<segredo recebido no convite>",
  "password": "<nova senha escolhida pelo destinatário>"
}
```

Consulta: `GET /api/v1/admin/invitations?state=AVAILABLE&page=0&size=20`. Estados disponíveis: AVAILABLE, EXPIRED, REVOKED e ACCEPTED. Sem state, retorna todos conforme a paginação. Detalhe: `GET /api/v1/admin/invitations/{id}`. Revogação: `POST /api/v1/admin/invitations/{id}/revocation`, sem corpo. Todas essas consultas/revogação exigem ADMIN ativo e nunca retornam o segredo.


### Validação da primeira sprint administrativa — 20/10/2026

A execução completa de `mvnw -o -B verify` passou com 124 testes, sem falhas, erros ou testes ignorados. Após organizar o tradutor local em métodos menores, `AdminInvitationsIntegrationTest` e `AdminAvailabilityIntegrationTest` foram reexecutados: 12 testes, todos aprovados. O Maven gerou o JAR e o relatório JaCoCo nas execuções de validação. A reexecução dirigida tem cobertura parcial; a análise remota deve usar a suíte completa do CI.

A limpeza de target na pasta original encontrou bloqueio de diretório. A validação foi realizada em cópia isolada dos mesmos fontes e pom.xml, com igualdade de conteúdo conferida e sem .env. PostgreSQL 16.15 foi criado e descartado por Testcontainers. Foram conferidos os 14 hashes do manifesto da fixture. Nenhum banco de desenvolvimento/produção ou script oficial externo foi alterado. Não foi executado Sonar nem workflow remoto nesta entrega.

Os testes cobrem explicitamente: uso único concorrente, reenvio/revogação, senha e login, emissor inativo, e-mail ocupado sem diferenciar caixa, erro de entrega após commit, recusa de emissão sem adapter, segredo fora das respostas, ACL real, ausência da linha de coordenação, último ADMIN sob concorrência e regressão das sessões/cadastros existentes.
