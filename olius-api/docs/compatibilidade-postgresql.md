# Compatibilidade PostgreSQL antes da Sprint 8

Revisão de 28/10/2026, na branch fix/pre-sprint8-compatibility, criada a partir da
main após a incorporação da PR 16. A integração reaproveita o trabalho existente
em feat/admin-invitations e preserva os fluxos financeiros das Sprints 6 e 7.

## O que foi corrigido

A Core cria ADMIN pelo aceite de convite e gerencia seu status pela função
set_admin_status. O endpoint antigo de criação com senha e o bootstrap por conta
operacional foram removidos. O controle concorrente do último ADMIN usa o mutex
e as funções do PostgreSQL, mantendo a revogação das sessões na inativação.
Cadastros de cidadão/estabelecimento preservam seu comportamento e a política de
e-mail aprovada; somente convites administrativos são integralmente minúsculos.

O envio de convites já desenvolvido foi integrado, mas continua desligado por
padrão. Isso não comprova credenciais Gmail, recebimento real ou página frontend.
As pendências de homologação continuam em RETOMADA_SPRINT_ADMIN.md, na raiz.

O snapshot de testes agora contém os scripts principais 01–08, catálogo atual,
permissões e validadores necessários. Os recursos de initial_data são exercitados
em teste próprio; não carregam configurações ou usuários automaticamente no Aiven.
O arquivo antigo 04_functions_procedures_window_functions.sql foi substituído pelo
nome atual, inclusive no teste de instalação sem superusuário.

O manifesto registra o SHA-256 dos recursos com finais de linha LF e o hash dos
arquivos oficiais revisados. A regra em .gitattributes evita diferenças entre
Windows e Linux. DatabaseSnapshotManifestTest verifica todos os hashes publicados.
OfficialReferenceDataIntegrationTest instala as 16 referências oficiais, repete a
carga e verifica que carga idempotente/validação não acrescentam auditoria indevida.

A imagem de teste pode ser escolhida por -Dolius.test.postgres-image=postgres:18.6.
O padrão continua postgres:16.15. Essa propriedade pertence somente à fixture dos
testes e não troca o datasource da aplicação. O CI executa as duas versões; apenas
o job 16.15 publica a análise Sonar, preservando o nome do check original.

## Validação executada

| Ambiente | Resultado |
|---|---|
| PostgreSQL 18.6, suíte completa Java | 368 testes, zero falhas/erros/ignorados; BUILD SUCCESS. |
| PostgreSQL 16.15, execução inicial completa | 368 testes; uma fixture antiga de PEV falhou ao tentar inativar o último ADMIN; os demais passaram. |
| PostgreSQL 16.15, reexecução das três classes ajustadas | 28 testes aprovados; BUILD SUCCESS. |
| Regressões SQL oficiais em 16.15 e 18.6 | Instalação 01–08, catálogo, papéis/contas, 13 arquivos de regressão, validação documental e carga/validação oficial aprovados em cada versão. |
| Concorrência oficial em 16.15 e 18.6 | admin_management_concurrency.py, procedures_concurrency.py e auth_sessions_concurrency.py aprovados com conexões reais e espera por lock. |

A fixture foi corrigida para criar outro ADMIN ativo e usar a função oficial de
status, inclusive nos testes de conta inativa de PEV e recuperação financeira.
Nenhuma proteção do banco foi desabilitada para fazer os testes passarem.

As execuções Java usaram cópias isoladas sem .env; integrações Stripe e SMTP reais
não foram utilizadas. Os testes SQL usaram containers sem portas publicadas e
sem rede externa, criados exclusivamente para a validação e removidos ao final.
A suíte completa apresentou avisos de pools associados a containers já encerrados;
isso não causou falhas de teste e não foi ocultado por alteração de logging.

O CI/Sonar remotos só poderão ser confirmados depois da publicação. Os resultados
locais não equivalem a aprovação remota. Não foram criados commits ou enviados
arquivos ao GitHub durante esta correção.

## Documentação externa da modelagem

Também foram corrigidos os guias em DB_Segundo/PostgreSQL:
access_control/API_DATABASE_CONTRACT.md, access_control/README.md,
AUTH_SESSIONS.md, admin_manegement.md e data_catalog/README.md.
Eles agora refletem 01–08 como instalação completa, contagens atuais, ausência de
upgrade_view.sql, a política de e-mails e a leitura própria da API Redis.
São arquivos de outro repositório e exigem versionamento separado da Core.
Nenhum script SQL funcional oficial foi alterado.

## Limites e próximos passos

O Aiven e os bancos existentes não foram migrados, recriados ou modificados.
A instalação Aiven já possui o contrato administrativo e foi identificada como
PostgreSQL 18.6. O container de desenvolvimento existente estava parado e foi
preservado; os testes não comprovam o schema que estiver instalado nele.
Antes de executar a API nesse container, iniciá-lo e conferir o contrato instalado,
sem aplicar CREATEs ou limpar dados automaticamente.

Os bloqueios de código e compatibilidade levantados na auditoria foram tratados.
A Sprint 8 não foi implementada. Seu próximo passo é retomar o desenho dos endpoints
sobre as rotinas de reserva, agenda e cancelamento já definidas.


## Recuperação de senha — 01/11/2026

Snapshot atualizado para DB_Segundo feat/password-reset, commit 7e6ed3c. Login exige open_auth_session(uuid,bytea,text); o contrato anterior é incompatível. Migração oficial permanece no repositório do banco e não é aplicada automaticamente pela Core. Ver [recuperacao-de-senha.md](recuperacao-de-senha.md) para implantação coordenada, permissões e pendências.


## Notificações e agenda idempotente — 06/11/2026

Referência atual: `01_DB_Segundo`, main `42f9977`. Os 16 recursos SQL do
snapshot reproduzem os scripts oficiais revisados, com hashes LF e hashes de
origem no manifesto. O schema public da instalação de teste contém 65 tabelas:
62 da estrutura principal (incluindo 25 logs) e três do catálogo documental.
Os objetos técnicos opcionais de `olius_etl` não pertencem à instalação da Core.

As mudanças são aditivas: autenticação, convites, pagamentos e operações anteriores
mantêm seus contratos. As triggers inserem notificações junto da transação da
operação; a Core consulta somente o destinatário autenticado e marca a primeira
leitura. Não insere avisos nem consulta diretamente collection_schedule_command.
A rotina schedule_collection_request_once recebe a chave da ação de agenda.

A fixture cria um banco descartável completo; não migra ambientes existentes.
Antes da publicação das funcionalidades, confirmar no destino as migrações
20261104_notifications.sql e 20261104_schedule_idempotency.sql, nesta ordem,
com a conta de implantação autorizada. Não reaplicar os scripts de criação sobre
uma base em uso nem ampliar os privilégios da Core. A presença no repositório
não comprova que as migrações foram aplicadas no Aiven ou no desenvolvimento.

O refresh do Data Mart (script 09) pertence à operação de BI. Não precisa ser
executado no startup da Core. Importação legada e tabelas de proveniência também
continuam sob responsabilidade da manutenção; não geram acesso extra pela API.
