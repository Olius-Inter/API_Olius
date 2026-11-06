# Central de notificações e agendamento idempotente

Atualizado em 06/11/2026. Referência do banco: `Olius-Inter/DB_Segundo`,
main `42f9977`. Este documento descreve o comportamento e os limites da entrega;
não comprova a instalação das migrações em desenvolvimento ou produção.

## Finalidade

A central permite que uma pessoa autenticada acompanhe mudanças do seu PEV ou
da sua solicitação B2B. Os avisos são persistidos pelo PostgreSQL junto da operação
que os originou. Não são e-mails nem notificações push do celular.

A proteção do agendamento identifica cada ação por uma UUID. Se uma resposta se
perder e o cliente reenviar a ação antiga após outro reagendamento, o sistema
reconhece o comando e conserva a agenda mais recente.

## Notificações

| Método e rota | Resultado |
|---|---|
| GET /api/v1/notifications | Página de avisos próprios: items, page, size e total. |
| GET /api/v1/notifications/unread-count | Contagem própria no campo count. |
| PUT /api/v1/notifications/{id}/read | Sem corpo; registra a primeira leitura e retorna 204. |

Todas exigem `Authorization: Bearer <accessToken>` e sessão válida. A identidade
é extraída da autenticação, nunca de recipientId enviado pelo cliente. Consultas
e marcação filtram o destinatário; ID de outra pessoa ou inexistente retorna 404
na marcação. As respostas usam Cache-Control: no-store.

A listagem aceita page (padrão 0), size (padrão 20, entre 1 e 100) e unreadOnly
(padrão false). Ordenação: createdAt DESC, id DESC. Total e itens são lidos na
mesma transação REPEATABLE READ. Isso não congela páginas de requisições futuras.
O item contém id, type, title, message, createdAt, readAt e resource (type/id).
Ao abrir o recurso relacionado, seus endpoints continuam verificando a autorização:
o vínculo da notificação não concede acesso por conta própria.

Os seis eventos são PEV_APPROVED, PEV_REJECTED, PEV_INACTIVATED, PEV_REACTIVATED,
COLLECTION_REQUEST_SCHEDULED e COLLECTION_REQUEST_RESCHEDULED. O destinatário é o
titular do PEV ou o estabelecimento da solicitação. Não há geração retroativa de
avisos anteriores à instalação das triggers. Rollback da operação remove seu aviso.

O Controller recebe a chamada e responde; NotificationService coordena as
transações; NotificationRepository executa SQL parametrizado. A conta da Core
possui SELECT e UPDATE somente de read_at. INSERT pertence às triggers, sem DELETE
ou edição de mensagens pela API. Ler novamente um aviso já lido retorna 204 sem
outra gravação e sem alterar a primeira data.

## Agendamento e reenvios

Rota: POST /api/v1/admin/collection-requests/{id}/scheduling, somente ADMIN ativo.
Corpo: scheduledAt, agreedAt e reason. Datas ISO-8601 com offset ou UTC.
O cliente envia `Idempotency-Key: <UUID da ação>` desde a primeira tentativa e
reutiliza chave/corpo nos reenvios. Uma ação diferente exige outra UUID.

A Core encaminha a chave para schedule_collection_request_once em transação
READ COMMITTED, com contexto de auditoria do ADMIN autenticado. O banco valida o
ator, bloqueia a solicitação e compara o comando antes de aplicar outra mudança.
Chave reconhecida retorna o estado atual sem desfazer uma ação posterior. Mesma
chave com horário, acordo, ADMIN ou motivo diferentes retorna 409. O motivo é
comparado após btrim no banco.

Uma ação que conserva o horário também registra sua chave, mesmo sem novo histórico
ou aviso. Exemplo: ação A agenda 10h, B agenda 14h e reenvio de A conserva 14h.
Retornar para 10h por nova decisão exige uma nova ação/chave.

O header continua opcional por compatibilidade. Sem ele, a rotina anterior permanece
em uso e não oferece proteção contra reenvios antigos após outra alteração.
Não gerar outra chave automaticamente depois de timeout.

SQLSTATE 40001 representa conflito concorrente, nunca confirmação de sucesso.
A Core desfaz a transação e responde 409 CONCURRENT_MODIFICATION; a nova tentativa
HTTP deve preservar chave e dados e iniciar outra transação. Não há retry automático
neste endpoint, nem repetição parcial do CALL dentro do snapshot que falhou.
As correções com expectedRevision mantêm seu comportamento separado.

## Banco e snapshot dos testes

Os scripts oficiais incluem as duas tabelas no 01, CHECK de notificações no 02,
índices no 03, wrapper no 04, triggers no 05 e concessões no access_control/01_roles.
O catálogo documenta os objetos em data_catalog/04_load.sql. O wrapper atual inclui
o tratamento específico de colisão concorrente e verificação de ACLs na instalação.
A Core não recebe acesso direto a collection_schedule_command.

Os 16 recursos do snapshot foram sincronizados com a referência oficial. O manifesto
registra commit, data de revisão, SHA-256 LF e hash do arquivo de origem.
A instalação completa dos testes tem 65 tabelas em public: 62 na estrutura
principal (25 logs incluídos) e três no catálogo. Estruturas opcionais de importação
em olius_etl e refresh de BI não pertencem ao startup da Core.

A fixture cria PostgreSQL descartável e usa uma conta operacional restrita nos
testes de autorização. Não acessa Aiven nem o container de desenvolvimento.
O ddl-auto=validate é preservado; não executa migrações ou cria o schema real.

## Implantação e limites

Antes da Core consumidora, confirmar que foram aplicadas no destino, nesta ordem:
20261104_notifications.sql e 20261104_schedule_idempotency.sql. Usar a conta de
implantação autorizada, com backup e conferência do destino. Os scripts de criação
01–08 não substituem migrações sobre bases existentes. Esta correção da API não
executou migrações, não alterou dados reais e não ampliou privilégios.

O banco preserva ON DELETE CASCADE de users para os avisos; a Core não recebeu
permissão de exclusão de usuários. Retenção/expurgo de avisos e de comandos de
idempotência continuam pendentes de decisão explícita, sem limpeza automática.
Web/Mobile devem consumir os endpoints e preservar as chaves. Push externo tem
integração própria e não foi acrescentado por esta entrega.

## Verificação da entrega

Regressões cobrem privacidade, primeira leitura sem regravação, permissões, reenvio
atrasado, comando sem mudança de horário, disputa de comandos iguais e rollback.
O CI contempla os três destinos da stack: main, feat/central-notificacoes e
fix/idempotencia-agendamento-b2b, preservando a matriz PostgreSQL 16.15/18.6.

Validação local concluída em 06/11/2026, sobre o código de 1c92848:

| Execução | Resultado |
|---|---|
| Suíte completa, PostgreSQL 16.15 | 746 testes, zero falhas/erros/ignorados; BUILD SUCCESS. |
| Contratos principais, PostgreSQL 18.6 | 102 testes, zero falhas/erros/ignorados; BUILD SUCCESS. |
| Conferência dos recursos SQL | Os 16 recursos correspondem à referência oficial; manifesto válido. |
| CI YAML e diffs da stack | Destinos conferidos e git diff --check aprovado. |

A execução 18.6 cobriu autenticação, convites, recuperação, notificações, agenda,
manifesto, inicialização e instalação sem SUPERUSER. Não representa a suíte
completa nessa versão. Foram usadas cópias isoladas somente com arquivos Git,
sem .env, integrações reais Stripe/SMTP ou dados reais.

O Windows exigiu um diretório temporário explícito para sockets da JDK
(jdk.net.unixdomain.tmpdir), somente nos processos de validação. A limpeza do
build no OneDrive encontrou arquivos bloqueados; por isso a validação final foi
feita nas cópias isoladas. Não foram alteradas propriedades da aplicação para
contornar essas condições. Avisos de pools associados a containers encerrados
não foram ocultados e não causaram falhas na suíte.

A aprovação do CI/Sonar remoto depende da publicação das branches corrigidas.
As migrações e a instalação efetiva nos bancos reais não foram homologadas por
estas execuções. Retenção/expurgo continuam pendentes de decisão da equipe.
