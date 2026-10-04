# Tratamento de erros da Core API

Este documento explica como a API comunica operações rejeitadas e falhas técnicas aos seus consumidores, como aplicações Web e Mobile. Não é necessário conhecer as regras de negócio do projeto para entender o fluxo.

A documentação descreve o código consultado em **04/10/2026**. Os exemplos de resposta são ilustrativos; não representam endpoints de negócio já implementados.

## 1. Finalidade e alcance

Quando uma requisição não pode ser atendida, o cliente precisa saber o que aconteceu sem receber detalhes internos do servidor. Esta infraestrutura centraliza essa comunicação, padroniza o JSON e separa mensagens públicas de informações técnicas.

Ela não implementa cadastros, coletas, pontuação ou autenticação. Essas funcionalidades poderão utilizar as exceções e os componentes descritos aqui conforme forem desenvolvidas.

Termos utilizados:

- **Recurso:** informação ou operação disponibilizada pela API.
- **Exceção:** objeto Java que sinaliza uma falha e interrompe o fluxo normal até encontrar tratamento.
- **Constraint:** restrição do banco, como impedir duplicidade de uma informação.
- **SQLSTATE:** código técnico de uma falha SQL; não é um status HTTP.
- **PEV:** ponto de entrega voluntária. Neste documento, aparece apenas porque algumas restrições dessa tabela já são reconhecidas pelo tradutor.
- **Auditoria:** registro de quem executou uma alteração e do estado dos dados envolvidos.

O tratamento MVC não cobre automaticamente falhas na inicialização, tarefas fora de uma requisição ou erros anteriores ao processamento MVC. Respostas já enviadas não podem ser normalmente substituídas.

## 2. Responsabilidades das classes

As classes de produção estão em `src/main/java/br/com/olius/olius_core_api`.

| Classe | Responsabilidade |
| --- | --- |
| `exception/enums/ApiErrorCode` | Catálogo de identificadores públicos e estáveis dos problemas. Não depende de HTTP. |
| `exception/ApiException` | Base abstrata das exceções da aplicação; armazena o código público. |
| `exception/ResourceNotFoundException` | Sinaliza que um recurso solicitado não foi encontrado. |
| `exception/ResourceConflictException` | Sinaliza conflito com o estado de um recurso. |
| `exception/BusinessRuleException` | Sinaliza rejeição por conflito de estado ou incompatibilidade semântica dos dados. |
| `exception/ApiProblemFactory` | Monta o `ProblemDetail`, mensagens, status padrão e identificação da ocorrência. |
| `exception/PersistenceExceptionTranslator` | Interpreta falhas conhecidas de persistência e devolve um `ApiErrorCode`. |
| `exception/GlobalExceptionHandler` | Coordena o tratamento MVC e devolve a resposta HTTP. Estende `ResponseEntityExceptionHandler`. |
| `exception/dto/FieldErrorResponse` | Representa um item da lista de erros de validação, não a resposta completa. |
| `security/ProblemAuthenticationEntryPoint` | Escreve a resposta 401 de autenticação necessária quando acionado pela segurança. |
| `security/ProblemAccessDeniedHandler` | Escreve a resposta 403 de acesso negado quando acionado pela segurança. |

Essa divisão é uma escolha de organização. O Spring não exige uma Factory ou um Translator próprio para toda aplicação.

## 3. Fluxos de execução

### Exceção identificada pela aplicação

```text
Cliente → Controller → Service
                         ↓ lança ApiException
              GlobalExceptionHandler
                         ↓ lê getCode()
                  ApiProblemFactory
                         ↓
                 Resposta ao cliente
```

O handler não devolve o erro ao controller para continuar a operação. Ele produz a resposta para o cliente. O tradutor de persistência não é necessário porque a exceção já informa seu significado.

### Falha originada no banco

```text
Banco rejeita uma operação
           ↓
Exceção chega ao handler
           ↓
PersistenceExceptionTranslator identifica um código conhecido
           ↓
ApiProblemFactory monta a resposta
           ↓
Cliente recebe o status e o JSON
```

O fallback genérico preserva primeiro as exceções que implementam `ErrorResponse`, utilizando seu status e cabeçalhos. Para as demais, consulta o tradutor. Uma falha não reconhecida recebe `INTERNAL_ERROR`.

O tradutor não executa rollback, reconexão, correção dos dados ou novas tentativas. O resultado transacional depende dos limites e da configuração das transações.

## 4. Contrato de resposta

O corpo utiliza `ProblemDetail`, com tipo de conteúdo `application/problem+json`. O formato segue a estrutura de Problem Details; os campos adicionais pertencem ao contrato da API.

Exemplo de validação:

```json
{
  "type": "urn:olius:problem:validation-error",
  "title": "Requisição inválida",
  "status": 400,
  "detail": "Verifique os campos informados.",
  "instance": "urn:uuid:550e8400-e29b-41d4-a716-446655440000",
  "code": "VALIDATION_ERROR",
  "timestamp": "2026-10-04T12:00:00Z",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "errors": [
    {
      "field": "email",
      "code": "INVALID_VALUE",
      "message": "Valor inválido para este campo."
    }
  ]
}
```

| Campo | Como interpretar |
| --- | --- |
| `type` | URI que identifica o tipo de problema; a URN não é uma página para abrir no navegador. |
| `title` | Título curto do problema. |
| `status` | Status HTTP também enviado na resposta. |
| `detail` | Mensagem pública controlada. |
| `instance` | Identificador opaco desta ocorrência. Evita copiar URLs que possam conter tokens de QR Code. |
| `code` | Código textual para decisões do cliente, independentemente da redação da mensagem. |
| `timestamp` | Momento da criação da resposta em UTC, gerado com `Instant`. |
| `traceId` | UUID gerado pelo servidor e reutilizado durante o tratamento da mesma requisição. |
| `errors` | Lista opcional de erros de validação; omitida quando vazia. |

O cabeçalho `X-Request-Id` contém o mesmo identificador utilizado em `traceId`. Esse identificador auxilia a correlação com os logs, mas não implementa tracing distribuído por si só.

O cliente deve utilizar `code` para distinguir situações. Não deve comparar o texto de `detail` nem tentar interpretar mensagens do banco.

## 5. Catálogo de códigos e status

| Código | HTTP padrão | Significado |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | Campos ou parâmetros rejeitados pela validação. |
| `MALFORMED_REQUEST` | 400 | Corpo ou parâmetros que não puderam ser interpretados corretamente. |
| `HTTP_REQUEST_ERROR` | 400* | Erro HTTP sem um código específico no catálogo. |
| `AUTHENTICATION_REQUIRED` | 401 | Necessidade de autenticação válida. |
| `ACCESS_DENIED` | 403 | Operação proibida para o solicitante. |
| `RESOURCE_NOT_FOUND` | 404 | Recurso inexistente. |
| `RESOURCE_CONFLICT` | 409 | Conflito com o estado atual do recurso. |
| `REGISTRATION_CONFLICT` | 409 | Restrição conhecida de e-mail, CPF ou CNPJ duplicado. |
| `DUPLICATE_TELEPHONE` | 409 | Telefone já associado ao mesmo cadastro. |
| `PEV_ALREADY_EXISTS` | 409 | Restrição conhecida que impede outro PEV para o mesmo responsável. |
| `BUSINESS_STATE_CONFLICT` | 409 | Regra de negócio rejeitada pelo estado atual. |
| `CONCURRENT_MODIFICATION` | 409 | Conflito de concorrência ou bloqueio reconhecido. |
| `BUSINESS_RULE_VIOLATION` | 422 | Dados incompatíveis com a operação. |
| `METHOD_NOT_ALLOWED` | 405 | Método HTTP não permitido para o recurso. |
| `NOT_ACCEPTABLE` | 406 | Representação solicitada pelo cliente indisponível. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Formato do conteúdo enviado não suportado. |
| `INTERNAL_ERROR` | 500* | Falha inesperada ou sem interpretação pública segura. |
| `SERVICE_UNAVAILABLE` | 503 | Indisponibilidade reconhecida pelo tratamento. |

\* Quando o erro já vem do mecanismo HTTP do Spring, o handler preserva o status original. Assim, um erro HTTP 418 pode usar `HTTP_REQUEST_ERROR` com status 418; outro erro 5xx pode usar `INTERNAL_ERROR` mantendo seu status. O status padrão da Factory não sobrescreve esse caminho.

Cabeçalhos HTTP originais, como `Allow` em um erro 405, são preservados nesse tratamento. A resposta 401 produzida pelo tratamento de autenticação inclui `WWW-Authenticate: Bearer`.

## 6. Validação estrutural e regras de negócio

Validação estrutural verifica formato, obrigatoriedade e limites de entrada. O handler converte os erros de `MethodArgumentNotValidException` em itens de `FieldErrorResponse`, remove duplicados e ordena pelo campo.

Atualmente, a mensagem de campo é deliberadamente genérica: `Valor inválido para este campo.`, com código `INVALID_VALUE`. Erros gerais usam `_request`. A validação de parâmetros por `HandlerMethodValidationException`, quando referente à entrada, usa `_request` com a mensagem `Parâmetro inválido.`. Valores rejeitados e mensagens internas não são incluídos.

Regra de negócio avalia se a operação faz sentido no contexto. Ela deve ser aplicada na camada responsável pela operação, não dentro da Factory ou do handler.

Exemplos de uso das exceções existentes, sem definir novas regras do projeto:

```java
throw new ResourceNotFoundException();
// Resultado: RESOURCE_NOT_FOUND, HTTP 404.

throw new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT);
// Resultado: BUSINESS_STATE_CONFLICT, HTTP 409.

throw new BusinessRuleException(BusinessRuleException.Reason.INCONSISTENT_DATA);
// Resultado: BUSINESS_RULE_VIOLATION, HTTP 422.
```

Um resultado de negócio negativo não é automaticamente uma falha HTTP. Cada funcionalidade deverá definir quando há rejeição e quando há um resultado normal a registrar.

## 7. Tradução das falhas PostgreSQL

O tradutor percorre causas e exceções SQL encadeadas com proteção contra ciclos. Não procura palavras na mensagem da exceção.

Para SQLSTATE `23505` (unicidade), exige `PSQLException`, schema `public` e uma combinação conhecida de tabela e constraint:

| Tabela e constraint | Código público |
| --- | --- |
| `users.users_email_key` | `REGISTRATION_CONFLICT` |
| `citizens.citizens_cpf_key` | `REGISTRATION_CONFLICT` |
| `establishment.establishment_cnpj_key` | `REGISTRATION_CONFLICT` |
| `telephone.uq_telephone_user_number` | `DUPLICATE_TELEPHONE` |
| `pev.pev_citizen_id_key` | `PEV_ALREADY_EXISTS` |
| `pev.pev_establishment_id_key` | `PEV_ALREADY_EXISTS` |

O conflito de cadastro utiliza a mensagem genérica `Não foi possível concluir o cadastro com os dados informados.`; não devolve o valor duplicado.

Outras traduções:

- `40001`, `40P01` e `55P03`: `CONCURRENT_MODIFICATION`.
- `OptimisticLockingFailureException` e `CannotAcquireLockException`: `CONCURRENT_MODIFICATION`.
- `08001`, `08006`, `57P01`, `57P02`, `57P03` e `53300`: `SERVICE_UNAVAILABLE`.
- Demais situações sem tradução reconhecida: `INTERNAL_ERROR`.

CHECK, chave estrangeira, NOT NULL, unicidade desconhecida e erros genéricos de rotinas como `P0001` não são automaticamente classificados como rejeições do cliente. Podem indicar defeito na aplicação ou contexto de auditoria incorreto. Senha incorreta do banco (`28P01`) e falta de privilégio no banco (`42501`) também não são o 401/403 do usuário da API.

Renomear uma constraint pode afetar esse mapeamento. Alterações no banco precisam ser conferidas com o tradutor e seus testes.

## 8. Integração com segurança

Os filtros de segurança podem rejeitar uma requisição antes do controller. Nesse caminho, o `GlobalExceptionHandler` não é acionado automaticamente.

`ProblemAuthenticationEntryPoint` implementa `AuthenticationEntryPoint` e escreve 401. `ProblemAccessDeniedHandler` implementa `AccessDeniedHandler` e escreve 403. Ambos utilizam a mesma Factory e o `ObjectMapper` injetado, definem `application/problem+json` e `X-Request-Id` e não reescrevem uma resposta já enviada.

**Estado atual:** as classes existem como componentes, mas não há uma `SecurityFilterChain` própria conectando-as. Sua presença não garante que serão chamadas pelo mecanismo de autenticação. A ligação aos filtros, incluindo os pontos apropriados do Resource Server, pertence à implementação de segurança futura. Os testes isolados dessas classes não comprovam essa integração.

## 9. Logs e proteção de dados

O handler não utiliza a mensagem SQL como mensagem pública. Para falhas internas ou de indisponibilidade, registra identificador, código ou status e tipo da exceção, sem imprimir diretamente mensagem e stack trace do driver.

Em `src/main/resources/application.properties`:

```properties
logging.level.org.hibernate.orm.jdbc.error=OFF
logging.level.org.hibernate.orm.jdbc.warn=OFF
```

Essas propriedades silenciam dois loggers do Hibernate que podem registrar detalhes SQL antes do handler. Não desativam constraints, exceções ou o tratamento das falhas. Também não silenciam todos os logs da aplicação.

O benefício é reduzir exposição de valores pessoais. A contrapartida é menos detalhe para diagnóstico. Outros componentes ainda precisam ser avaliados quanto ao registro de dados sensíveis; esta configuração não é uma garantia geral de anonimização.

## 10. Testes e banco descartável

Os testes estão em `src/test/java/br/com/olius/olius_core_api`:

| Classe | O que verifica |
| --- | --- |
| `exception/GlobalExceptionHandlerTest` | Respostas MVC, validação, cabeçalhos, preservação de status e proteção contra exposição de mensagens. |
| `exception/PersistenceExceptionTranslatorTest` | Metadados conhecidos, causas encadeadas, concorrência, indisponibilidade e fallback conservador. |
| `security/ProblemSecurityHandlersTest` | Serialização das respostas 401/403, cabeçalhos e resposta já enviada. Não percorre a cadeia completa de segurança. |
| `OliusApiApplicationTests` | Integração com PostgreSQL, estrutura de 52 tabelas públicas, duplicidade, auditoria, CHECKs e falhas de flush/commit. |
| `testfixture/FlushProbe` | Entidade exclusiva de teste para provocar falha real de persistência JPA. |

### Funcionamento previsto

1. A extensão JUnit do Testcontainers inicia `postgres:16.15`.
2. `@DynamicPropertySource` informa ao Spring URL, usuário e senha do container.
3. `@SpringBootTest` carrega o contexto; nesse teste, a importação do `.env` é desativada.
4. `@BeforeAll` cria as estruturas auxiliares e executa os quatro scripts, na ordem abaixo.
5. Os testes verificam o comportamento real do banco e sua tradução.
6. O container é encerrado e descartado pelo gerenciamento do Testcontainers.

O contexto é carregado antes de `@BeforeAll`. Ao introduzir entidades de produção, será necessário revisar essa ordem e o escaneamento JPA para que `ddl-auto=validate` não tente validar tabelas ainda não criadas. A preparação atual não deve ser expandida indiscriminadamente para todas as entidades futuras.

### Configuração e validação em 04/10/2026

As anotações de preparação estão ativas em `OliusApiApplicationTests`:

```java
@EntityScan(basePackageClasses = FlushProbe.class)
@Testcontainers
```

`@Testcontainers` habilita a extensão JUnit que gerencia o container declarado com `@Container`. Não é necessária uma chamada manual a `POSTGRES.start()` nesse fluxo. `@EntityScan` limita a descoberta de entidades ao pacote da fixture `FlushProbe`, mantendo as entidades de produção fora desse teste específico.

A execução local de `mvnw.cmd verify`, com Java 21 Temurin e Docker Desktop, concluiu com **BUILD SUCCESS**: **49 testes executados, nenhuma falha, nenhum erro e nenhum teste ignorado**.

| Classe | Testes aprovados |
| --- | --- |
| `GlobalExceptionHandlerTest` | 16 |
| `PersistenceExceptionTranslatorTest` | 21 |
| `OliusApiApplicationTests` | 9 |
| `ProblemSecurityHandlersTest` | 3 |

O PostgreSQL 16.15 iniciou automaticamente em uma porta dinâmica. Os testes de integração confirmaram a execução dos scripts, as 52 tabelas públicas e os cenários de persistência, auditoria e rollback. O Maven também gerou o JAR da aplicação. Essa validação foi local, sem executar a análise Sonar ou o workflow remoto.

Na validação inicial apareceram avisos sobre API obsoleta e carregamento dinâmico do Mockito. A configuração de agente explícito descrita abaixo substitui esse carregamento dinâmico. O aviso sobre compartilhamento de classes da JVM (CDS) pode continuar aparecendo; ele é diferente do aviso de autoanexação do Mockito.

### Scripts utilizados

Local: `src/test/resources/db/olius/`.

1. `01_structure.sql`: tabelas e objetos estruturais.
2. `02_check_constraints.sql`: restrições de validação do banco.
3. `03_indexes.sql`: índices.
4. `05_triggers.sql`: triggers e funções associadas.

São cópias utilizadas como recursos de teste. O método `installOfficialSchema` lê e executa os arquivos com JDBC, preservando os blocos SQL completos. Não são migrations nem scripts executados na inicialização normal da API. O arquivo de functions/procedures incompleto e o catálogo de dados não integram essa lista.

Há estruturas auxiliares exclusivas dos testes: schema `test_support`, tabela `flush_probe` e tabela temporária `commit_probe`. Não representam novas entidades de negócio.

Quando os scripts oficiais mudarem, as cópias precisam ser sincronizadas e os testes revisados, inclusive a expectativa de 52 tabelas. A existência de tabelas de auditoria não basta para preenchê-las: são necessárias as rotinas e o contexto de execução adequados.

## 11. Relação com o CI e execução local

O workflow `../../.github/workflows/ci.yml`, relativo a esta pasta, roda em PRs destinadas à `main` e pushes na `main`. Ele prepara Java 21 Temurin e executa Maven com `clean verify` e o objetivo de análise Sonar.

```text
Evento no GitHub → CI → Maven → testes → Testcontainers → PostgreSQL
```

O CI não contém mais um bloco `services` criando outro PostgreSQL nem variáveis `SPRING_DATASOURCE_*` fixas. A preparação do banco foi delegada aos testes para permitir o mesmo fluxo local e no CI. O workflow ainda utiliza `SONAR_TOKEN` para a análise Sonar.

Pré-requisitos locais: JDK 21, Docker disponível para a JVM e acesso às dependências/imagem quando ainda não estiverem em cache. Não é necessário iniciar o PostgreSQL de desenvolvimento pelo Compose para esses testes.

Execute na raiz `olius-api`:

```powershell
.\mvnw.cmd test
```

Para verificar e empacotar:

```powershell
.\mvnw.cmd clean verify
```

O comando local acima não executa o Sonar por si só. Os relatórios de testes ficam em `target/surefire-reports`. Falha ao iniciar o container, preparar o schema ou executar uma asserção faz o Maven falhar; no CI, isso reprova a etapa. A análise Sonar também pode falhar independentemente do resultado dos testes.

### Mockito como agente dos testes

O `pom.xml` declara `mockito-core` com escopo `test`, mantendo a versão gerenciada pelo Spring Boot. O objetivo `properties` do `maven-dependency-plugin`, na fase `initialize`, resolve o caminho do JAR. O Surefire inicia a JVM dos testes com:

```xml
<argLine>@{argLine} -javaagent:"${org.mockito:mockito-core:jar}"</argLine>
```

Isso carrega o agente na inicialização, em vez de o Mockito tentar anexá-lo durante a execução. As aspas aceitam caminhos com espaços. A propriedade `argLine` vazia fornece um valor padrão e permite composição posterior com agentes de cobertura. Não é necessário alterar o CI: ele já executa o ciclo Maven `verify`. Essa configuração não adiciona o agente à execução normal da aplicação.

#### Executar no IntelliJ

Após alterar o POM, utilize **Reload All Maven Projects**. Para executar com a mesma configuração do CI, abra a janela **Maven**, expanda **Lifecycle** e execute **test** ou **verify**.

Para que as ações Run/Debug sejam delegadas ao Maven, em **Settings → Build, Execution, Deployment → Build Tools → Maven → Runner**, habilite **Delegate IDE build/run actions to Maven**. Essa é uma preferência local: não foi alterada automaticamente nem adicionada ao versionamento.

Uma configuração JUnit executada diretamente pelo IntelliJ não deve ser considerada coberta automaticamente pelo `argLine` do Surefire. Se continuar usando o executor JUnit nativo, será necessário configurar o agente nas opções da JVM dessa execução. Prefira o caminho Maven acima para não duplicar caminhos e versões em configurações locais.

#### Verificação sem carregamento dinâmico

É possível verificar a configuração no Windows com:

```powershell
.\mvnw.cmd verify "-DargLine=-XX:-EnableDynamicAgentLoading"
```

Validação realizada em 04/10/2026: esse comando concluiu com **BUILD SUCCESS**, **49 testes aprovados**, nenhuma falha, erro ou teste ignorado. Não apareceram os avisos de autoanexação do Mockito ou carregamento dinâmico do agente. A análise Sonar e o executor JUnit nativo do IntelliJ não foram executados nessa validação.

Esse argumento bloqueia a anexação dinâmica durante a verificação; não oculta o aviso. O agente explícito permanece habilitado pelo Surefire. O aviso `Sharing is only supported for boot loader classes...` pode permanecer devido à instrumentação e não indica falha dos testes. Não foi adicionada uma opção para desativar CDS apenas para escondê-lo.

Referências: [instrumentação explícita no Mockito](https://github.com/mockito/mockito/blob/main/mockito-core/src/main/java/org/mockito/Mockito.java) e [delegação ao Maven no IntelliJ](https://www.jetbrains.com/help/idea/delegate-build-and-run-actions-to-maven.html).

### Cobertura de testes e Sonar

Testes aprovados indicam que as verificações passaram; cobertura indica quais linhas e decisões do código foram exercitadas. O Sonar importa essa medição, mas executar JUnit por si só não gera o relatório de cobertura.

O `jacoco-maven-plugin` 0.8.14 prepara seu agente antes dos testes e gera o relatório na fase `verify`. O Surefire combina o agente de cobertura, por meio de `@{argLine}`, com o agente explícito do Mockito. Não foram adicionadas exclusões de classes para elevar artificialmente a cobertura. A opção `append=false` evita acumular medições de execuções anteriores no processo de testes atual; se futuramente houver múltiplos forks/processos, essa estratégia deverá ser revista.

Execute `mvnw.cmd verify` (ou `mvnw.cmd clean verify` para uma construção limpa) para produzir:

- `target/site/jacoco/index.html`: relatório navegável para inspeção local.
- `target/site/jacoco/jacoco.xml`: relatório que o Sonar importa.
- `target/jacoco.exec`: dados binários coletados pelo agente.

Esses arquivos são gerados em `target/` e não devem ser versionados. `mvnw.cmd test` coleta os dados, mas não alcança a fase `verify` que gera o relatório. Use o ciclo de vida Maven, e não apenas o objetivo isolado `surefire:test`, para preparar os agentes.

A propriedade `sonar.coverage.jacoco.xmlReportPaths` aponta explicitamente para o XML. O CI já executa `clean verify` antes do objetivo Sonar; portanto, o relatório será produzido antes da importação. O Quality Gate da PR exige 80% de cobertura no código novo. A medição local de todo o projeto é uma referência, mas a confirmação desse critério depende da análise remota das linhas alteradas.

## 12. Como evoluir esta infraestrutura

1. Definir o significado da nova rejeição com base na funcionalidade aprovada.
2. Reutilizar um código quando sua semântica for a mesma; caso contrário, ampliar `ApiErrorCode`.
3. Definir o status e a mensagem segura na Factory.
4. Lançar a exceção na camada responsável pela operação, mantendo controllers sem regras de negócio.
5. Só ampliar o tradutor SQL quando schema, tabela, constraint ou SQLSTATE tiverem interpretação pública segura.
6. Testar o caminho esperado e casos desconhecidos; para persistência, verificar também rollback e falhas tardias.
7. Atualizar este contrato e, quando implementada, a documentação OpenAPI.

Não usar Bean Validation para substituir regras que dependem do estado do sistema. Não generalizar toda falha de banco como 400 ou 409. Não adicionar novas tentativas automáticas apenas porque o erro foi traduzido como concorrência ou indisponibilidade.

## 13. Limites atuais

- Nenhuma regra nova de coleta, plano, certificado ou pontuação é definida por esta infraestrutura.
- As exceções de negócio ainda utilizam categorias amplas, sem catálogo específico de cada funcionalidade.
- A configuração definitiva de autenticação/autorização e a ligação dos handlers de segurança estão pendentes.
- O tratamento não substitui permissões, integridade do banco, rotinas de auditoria ou transações.
- A resposta padronizada não abrange automaticamente falhas de startup, tarefas em segundo plano ou filtros externos.
- A suíte foi validada localmente conforme a seção 10; isso não substitui a execução do CI nem comprova funcionalidades ainda não implementadas.
