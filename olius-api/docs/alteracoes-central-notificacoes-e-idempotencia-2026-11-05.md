# Alterações da central de notificações e da idempotência B2B

Data do registro: 05/11/2026.

## Escopo

Implementação local no PostgreSQL e na Core API para os seis eventos aprovados: PEV aprovado, rejeitado, inativado e reativado, além de coleta B2B agendada e reagendada. Também foi adicionada proteção contra o reenvio atrasado de uma ação de agendamento.

Foram criados ou alterados 19 arquivos. As alterações estão na cópia local da API e na cópia do banco em `artifacts/revisao-api-banco-2026-11-01/DB_Segundo`. A aplicação das migrações em um banco em execução e a execução dos testes integrados estão pendentes.

## Banco de dados — 7 arquivos

Os caminhos desta seção partem de `artifacts/revisao-api-banco-2026-11-01/DB_Segundo/PostgreSQL`.

| Arquivo | Alteração | Por que foi necessária |
|---|---|---|
| `01_structure.sql` | Criação de `notification` e `collection_schedule_command`. | Persistir avisos, destinatários e leitura, além de registrar as chaves de idempotência das ações de agendamento. |
| `03_indexes.sql` | Índices por destinatário, data e ID, incluindo um índice parcial para avisos não lidos. | Atender à listagem ordenada e às consultas de não lidas. |
| `04_functions_procedures.sql` | Criação de `schedule_collection_request_once`. | Reconhecer uma ação já processada antes de executar novamente o procedimento de agendamento existente. |
| `05_triggers.sql` | Criação de funções e triggers para mudanças de status do PEV e inserções no histórico de agendamento. | Gerar os seis avisos aprovados dentro da mesma transação da operação que os originou. |
| `access_control/01_roles.sql` | Inclusão das tabelas no escopo de permissões, definição de propriedade e concessão dos acessos necessários. | Permitir que a Core consulte avisos, atualize a leitura e execute o novo procedimento. |
| `migrations/20261104_notifications.sql` — novo | Migração da tabela de avisos, índices, funções, triggers e permissões. | Introduzir a central em uma base existente, sem depender da reinstalação do schema. |
| `migrations/20261104_schedule_idempotency.sql` — novo | Migração da tabela de comandos e do procedimento de agendamento com chave. | Introduzir a proteção contra reenvios em uma base existente. |

### Persistência dos avisos

A tabela `notification` armazena ID, destinatário vinculado a `users`, tipo, título, mensagem, referência tipada ao recurso, identificador do evento, data de criação e data da primeira leitura.

Os tipos implementados são `PEV_APPROVED`, `PEV_REJECTED`, `PEV_INACTIVATED`, `PEV_REACTIVATED`, `COLLECTION_REQUEST_SCHEDULED` e `COLLECTION_REQUEST_RESCHEDULED`.

O destinatário de PEV é obtido de `citizen_id` ou `establishment_id`. No B2B, é o estabelecimento titular da solicitação. Cada transição efetiva de PEV recebe um UUID de evento. Os avisos de agendamento usam o ID de `collection_schedule_history`. A tabela impõe unicidade por destinatário, tipo e evento.

As triggers gravam o aviso junto com a operação de negócio. Um rollback também desfaz o aviso. A migração começa a gerar avisos quando suas triggers são instaladas.

### Idempotência do agendamento

A tabela `collection_schedule_command` guarda solicitação, chave de idempotência, horário agendado, instante do acordo, administrador e motivo. A chave é única dentro de cada solicitação.

O procedimento `schedule_collection_request_once` valida o administrador, bloqueia a solicitação para serializar as alterações e procura a chave. Se a chave já existe com os mesmos dados, encerra o processamento. Se os dados diferem, lança o conflito que a Core traduz para HTTP 409. Para uma chave nova, executa o procedimento existente e registra o comando na mesma transação.

O comando é registrado inclusive quando o horário enviado já é o atual e nenhuma linha nova de histórico é criada. Isso permite reconhecer essa ação se ela for reenviada após outro reagendamento.

Exemplo esperado: agendar para 10h com a chave A, reagendar para 14h com a chave B e reenviar a ação A preserva 14h e a quantidade de avisos. Uma nova ação para voltar às 10h deve usar uma nova chave.

## Core API — 6 arquivos

Os caminhos Java partem de `API_Olius/olius-api/src`. O pacote principal é `br/com/olius/olius_core_api`.

| Arquivo | Alteração | Por que foi necessária |
|---|---|---|
| `main/java/br/com/olius/olius_core_api/controller/NotificationController.java` — novo | Implementação de listagem, contador de não lidas e marcação de leitura. | Expor a central autenticada com consultas restritas ao destinatário da sessão. |
| `main/java/br/com/olius/olius_core_api/controller/CollectionRequestAdministrationController.java` | Recebimento do cabeçalho opcional `Idempotency-Key` e atualização da descrição OpenAPI. | Permitir que o cliente identifique uma ação de agendamento e reutilize sua chave nos reenvios. |
| `main/java/br/com/olius/olius_core_api/service/CollectionRequestService.java` | Inclusão de uma chamada com chave e encaminhamento dentro da transação. | Integrar a idempotência ao fluxo normal da Core, preservando a chamada existente sem chave. |
| `main/java/br/com/olius/olius_core_api/repository/CollectionRequestRepository.java` | Escolha do procedimento SQL conforme a presença da chave. | Encaminhar os dados ao procedimento com idempotência quando o cabeçalho estiver presente. |
| `test/java/br/com/olius/olius_core_api/PevAdministrationIntegrationTest.java` | Adição de teste de aviso de aprovação, titularidade e primeira leitura. | Verificar que uma conta acessa seus avisos e que a repetição da leitura preserva o primeiro horário. |
| `test/java/br/com/olius/olius_core_api/CollectionRequestsIntegrationTest.java` | Adição de teste de reenvio atrasado e conflito de chave. | Cobrir o cenário que motivou a mudança e conferir a quantidade de registros de histórico e avisos. |

### Rotas adicionadas

| Método e rota | Comportamento |
|---|---|
| `GET /api/v1/notifications` | Lista paginada dos avisos da conta autenticada, com filtro opcional `unreadOnly`. Ordenação por data de criação e ID, ambos decrescentes. |
| `GET /api/v1/notifications/unread-count` | Contagem dos avisos não lidos da conta autenticada. |
| `PUT /api/v1/notifications/{id}/read` | Marca um aviso próprio como lido e preserva a primeira data de leitura nos reenvios. Retorna 404 se o aviso estiver indisponível para a conta. |

As rotas usam a autenticação existente e retornam `Cache-Control: no-store`. A listagem usa uma transação com isolamento `REPEATABLE_READ` para obter total e itens no mesmo snapshot.

A rota administrativa de agendamento continua em `POST /api/v1/admin/collection-requests/{id}/scheduling`. O novo cabeçalho aceita um UUID. O reenvio reconhecido retorna o estado atual da solicitação.

## Snapshot SQL da Core — 6 arquivos

Os caminhos desta seção partem de `API_Olius/olius-api/src/test/resources/db/olius`.

| Arquivo | Alteração e finalidade |
|---|---|
| `01_structure.sql` | Reprodução das novas tabelas no banco utilizado pelos testes. |
| `03_indexes.sql` | Reprodução dos índices da central. |
| `04_functions_procedures.sql` | Reprodução do procedimento de agendamento com chave. |
| `05_triggers.sql` | Reprodução das funções e triggers que geram os avisos. |
| `access_control/01_roles.sql` | Reprodução das permissões e propriedades dos novos objetos. |
| `snapshot-manifest.json` | Atualização dos hashes dos scripts modificados para permitir a verificação de integridade do snapshot. |

## Decisões adotadas na implementação

- O cabeçalho `Idempotency-Key` é opcional para compatibilidade com os clientes atuais. A proteção contra reenvios atrasados depende de enviar a chave desde a primeira tentativa e reutilizar a mesma chave com os mesmos dados.
- As chaves são armazenadas em uma tabela própria, inclusive para comandos que mantêm o horário atual. Essa decisão cobre ações que não produzem uma nova linha no histórico de agendamento.
- Os avisos de PEV são produzidos diretamente na mudança de status e recebem um identificador próprio de evento. O `audit_event_id` não foi usado nessa implementação.
- A FK de destinatário em `notification` utiliza `ON DELETE CASCADE`. Isso remove os avisos quando o registro de usuário é excluído. Essa política foi adotada na implementação e ainda precisa de alinhamento explícito com a equipe.
- O prazo de retenção dos avisos e dos comandos de idempotência continua pendente de definição.

## Verificações realizadas e pendências

A compilação Java isolada passou para os arquivos alterados e para os testes adicionados. Os hashes do snapshot SQL foram conferidos. As definições da central foram comparadas entre a migração, o schema do banco e o snapshot da Core. A checagem de whitespace do Git passou ao final das alterações.

A compilação isolada não comprova o funcionamento integrado. A tentativa de executar o Maven em modo offline encontrou dependências ausentes no cache, e o Docker estava indisponível para subir o PostgreSQL dos testes.

Antes da implantação, é necessário executar os testes integrados, validar as duas migrações em uma base existente e verificar as permissões com a role utilizada pela Core. A homologação deve cobrir reenvio atrasado, conflito de chave, rollback, concorrência, isolamento entre contas e leitura repetida.
