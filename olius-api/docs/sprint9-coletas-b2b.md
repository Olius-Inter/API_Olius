# Coletas B2B, motoristas e recorrência — Sprint 9

## O que esta parte do sistema faz

Um estabelecimento solicita uma coleta de óleo pela Sprint 8. Depois de o ADMIN
aprovar e agendar o pedido, a Sprint 9 permite registrar o que aconteceu na visita:
quem chegou, se o estabelecimento aceitou o atendimento, quanto óleo foi apresentado
e recolhido e quais ocorrências impediram o sucesso.

O resultado atualiza a utilização da franquia, o saldo de pontos, a recorrência e
os certificados pelas rotinas oficiais do PostgreSQL. A API Redis consulta os dados
confirmados para reconstruir o ranking anual. Uma visita não depende de Redis para
ser confirmada e não envia POST da Core para a API de ranking.

Esta implementação está na branch `feat/sprint9-b2b-collections`. Commits e PR ficam
para o final, conforme combinado. O CI remoto ainda precisa revisar a entrega.

## Responsáveis

| Participante | Responsabilidade |
|---|---|
| ADMIN ativo | Manter motoristas, consultar a operação e registrar a exceção de visita sem atendimento |
| Motorista ativo | Identificar-se pelo formulário, registrar chegada e fatos da coleta normal |
| Estabelecimento ativo | Confirmar o aceite do próprio atendimento e consultar suas coletas/pontos |
| Core API | Validar entradas, identidade/titularidade, coordenar transações e devolver respostas seguras |
| PostgreSQL | Validar transições, registrar fatos, calcular efeitos e manter integridade/auditoria |
| API Redis | Ler a projeção anual autorizada e publicar um ranking reconstruível |

Motorista não é uma conta de usuário: não recebe senha, sessão ou JWT.

## 1. Cadastro de motoristas

| Método | Endpoint | Comportamento |
|---|---|---|
| POST | `/api/v1/admin/drivers` | Cria com nome, CPF e CNH; retorna 201 e Location |
| GET | `/api/v1/admin/drivers` | Página ordenada por nome/ID, filtro opcional status |
| GET | `/api/v1/admin/drivers/{id}` | Detalhe cadastral, inclusive inativo |
| PATCH | `/api/v1/admin/drivers/{id}` | Atualiza nome, CPF e/ou CNH |
| PATCH | `/api/v1/admin/drivers/{id}/status` | Ativa/inativa sem apagar vínculos |

Exemplo de criação (dados fictícios):

```json
{"name":"Motorista de exemplo","cpf":"12345678901","cnh":"12345678901"}
```

CPF e CNH possuem 11 dígitos sem máscara e unicidade. Isso valida estrutura e
duplicidade, sem consultar órgãos oficiais. Nome é obrigatório, até 150 caracteres.
PATCH exige ao menos um campo. ID, datas e estado inicial ACTIVE são do servidor;
status tem sua operação própria. Campos desconhecidos são rejeitados.

JPA insere somente id/name/cpf/cnh, conforme os privilégios por coluna. O banco
define as datas e mantém updated_at. Atualizações bloqueiam a linha do motorista
até o commit. Documentos duplicados retornam 409 sem expor seu conteúdo ou SQL.

Não há DELETE, atribuição antecipada a rotas, disponibilidade em tempo real ou
criação de driver_log. A revisão anterior removeu essa tabela; não há histórico
automático prometido para cada edição cadastral.

## 2. Identificação operacional e escolha do pedido

O formulário envia CPF, CNH e o QR vigente do estabelecimento no corpo JSON de
cada ação. O banco resolve o motorista e o estabelecimento; o cliente não escolhe
livremente seus UUIDs. Motorista e estabelecimento precisam estar ativos.

```json
{"cpf":"12345678901","cnh":"12345678901","qrToken":"token-lido-no-estabelecimento"}
```

`POST /api/v1/operations/b2b/context?page=0&size=20` valida esses dados e retorna:

- identificação mínima do motorista e estabelecimento;
- pedidos desse estabelecimento aprovados, agendados e sem coleta;
- motivos ativos utilizáveis no registro normal;
- paginação, com máximo de 100 itens por página.

Essa consulta **não registra chegada**. O usuário precisa confirmar a ação própria.
O formulário deve manter o ID do pedido selecionado; apenas abrir o QR não escolhe
nem conclui um atendimento automaticamente. Se outro motorista já tiver registrado
a chegada, a tentativa de assumir essa chegada será rejeitada.

### Limite de segurança do MVP

CPF/CNH + QR constituem identificação fraca, não autenticação forte nem prova de
presença física. Alguém que conheça os dados pode tentar utilizá-los. Essa limitação
foi aprovada para o MVP; não há conta de motorista ou verificação GPS inventada.

Cada ação revalida dados/status/QR e mantém bloqueios compartilhados durante sua
transação curta. Documentos e QR não são colocados em URLs nem em respostas de
contexto. DriverAccess tem toString redigido. Em produção, consumir via HTTPS.

As ações POST operacionais usam limite técnico por IP, na mesma configuração
`OLIUS_AUTH_REQUESTS_PER_MINUTE`, com contador separado do login. Excedido o limite,
retornam 429 e Retry-After. O armazenamento do limitador é local à instância.
Não enviar um JWT para identificar o motorista: as credenciais do formulário são
validadas em cada chamada. JWT inválido enviado por engano pode ser rejeitado pelo
filtro de segurança antes do formulário.

## 3. Chegada e aceite

1. Motorista confirma `POST /api/v1/operations/b2b/requests/{id}/arrival`, com o JSON
   de identificação. `record_collection_arrival` define os horários no banco.
2. O responsável autenticado do próprio estabelecimento confirma
   `POST /api/v1/collection-requests/{id}/service-acceptance`, com Bearer da Core,
   sem precisar enviar o ID do estabelecimento ou um horário no corpo.
3. O aceite utiliza `accept_collection_service`. Após ele, o cancelamento da
   Sprint 8 é impedido pelo contrato oficial.

Chegada e aceite são transações separadas; nenhuma conexão fica aberta enquanto
se espera a pessoa clicar no formulário. O frontend pode consultar novamente o
pedido pela Sprint 8 para atualizar seu estado. Não há WebSocket nesta entrega.

Reenvio da mesma chegada pelo mesmo motorista retorna o instante original. Outro
motorista não a substitui. Reenvio do aceite preserva o aceite original. A abertura
do formulário, chegada e aceite não geram, isoladamente, pontos ou uma coleta.

## 4. Registro normal da coleta

`POST /api/v1/operations/b2b/requests/{id}/collection`:

```json
{
  "access":{"cpf":"12345678901","cnh":"12345678901","qrToken":"token-vigente"},
  "collectedVolumeLiters":10,
  "presentedVolumeLiters":10,
  "oilCondition":"ACCEPTABLE",
  "hasCompromisingOccurrence":false,
  "failureReasonCodes":[],
  "observation":null
}
```

O motorista informa os fatos; resultado, pontos, ID, ordem de processamento,
revisão e datas não são campos livres. Não é possível recolher mais do que foi
apresentado. Os volumes têm até duas casas decimais. Presented igual a zero exige
NOT_ASSESSED; volume apresentado positivo exige ACCEPTABLE ou UNACCEPTABLE.
Ausência de óleo, óleo inadequado ou ocorrência comprometedora exigem observação.

Motivos normais previstos são VOLUME_OUT_OF_TOLERANCE, OIL_UNACCEPTABLE e
COMPROMISING_OCCURRENCE. A lista precisa corresponder aos fatos. Motivos exclusivos
do ADMIN não podem ser usados pelo motorista. O PostgreSQL verifica coerência e
vigência no primeiro registro; reenvio histórico não é invalidado apenas porque
um motivo do registro original foi inativado depois.

A Core chama somente `record_collection`, nunca o núcleo privado. Falha de uma
etapa reverte coleta, motivos, cálculos e auditoria juntos. A resposta 200 retorna
o fato atual e seu cálculo vigente, também no primeiro registro.

### Data e reenvios

O motorista não informa a data. No primeiro registro normal, a Core obtém
clock_timestamp do banco na confirmação. Em reenvios, reutiliza collection_date
do fato existente, preservada pelas correções atuais. A abertura do formulário
não define essa data. Não há lançamento retroativo/offline nesta interface.

Um bloqueio consultivo transacional por solicitação serializa reenvios da Core.
Ele não grava estado nem substitui os bloqueios/integridade das rotinas oficiais.
A Core não recebe UPDATE da solicitação/coleta nem SELECT dos logs para isso.

O pedido é a chave natural: exatamente uma coleta, inclusive após anulação.
O mesmo payload retorna o mesmo ID, sem nova penalidade, reserva ou bônus.
Dados diferentes retornam 409. O banco compara os dados originais, mesmo depois
de correções; a resposta pode mostrar a revisão atual. Um retry não é uma edição.

## 5. Estabelecimento fechado ou sem responsável

O motorista registra a chegada, constata a impossibilidade de atendimento e
comunica o ADMIN por um canal externo. O ADMIN verifica e envia:

`POST /api/v1/admin/collection-requests/{id}/failed-visit`

```json
{"reasonCode":"ESTABLISHMENT_CLOSED","justification":"Motorista informou fechamento; situação verificada pela operação."}
```

O outro motivo permitido é NO_RESPONSIBLE_AVAILABLE. Justificativa é obrigatória.
Não cobre recusa explícita de aceite nem substitui a avaliação normal do óleo.

A função `record_failed_collection_visit` exige ADMIN ativo, pedido aprovado/
agendado, chegada de motorista ainda ativo, ausência de aceite e ausência de outra
coleta. Ela define motorista/data a partir da chegada, volumes zero, condição
NOT_ASSESSED e resultado UNSUCCESSFUL. O ADMIN fica como autor na auditoria;
o motorista permanece responsável físico. Não existe aceite fictício.

A visita consome uma vaga e zero litros, libera a reserva anterior e aplica −50
nominais uma vez, com piso zero. O pedido conserva APPROVED: a existência da
coleta encerra sua reserva. Cancelamento operacional não é substituto desse fato.

Reenvio equivalente pelo mesmo ADMIN retorna o mesmo registro. Alterar motivo,
justificativa ou ADMIN conflita, inclusive quando a coleta já foi corrigida.
O contexto de auditoria anterior é restaurado pela função em sucesso/reenvio/erro.

## 6. Pontuação, recorrência e franquia

| Situação | Efeito oficial |
|---|---|
| Sucesso | Colhido entre 90% e 110% da estimativa, inclusive, óleo aceitável e sem ocorrência comprometedora |
| Pontos do sucesso | floor(litros efetivamente recolhidos) + 50 + bônus de recorrência quando aplicável |
| Insucesso | −50 nominais por visita, independentemente da quantidade de motivos |
| Piso do saldo | Zero aplicado em sequência; com saldo 20, uma penalidade nominal −50 deixa zero |
| Recorrência | 10º sucesso +10; 20º +20; até 100º +100; múltiplos de dez seguintes mantêm o teto +100 |
| Falha entre sucessos | Não avança nem zera a contagem de sucessos |
| Volume da franquia | Volume efetivamente coletado, inclusive em visita malsucedida |
| Certificados | Reconciliação oficial considera volume efetivo, sem emitir por volume inexistente |

Recorrência é acumulada e segue processing_order; não reinicia no ano. Ranking
anual usa a view oficial do PostgreSQL, com suas regras de ano/piso/revisões.
Saldo geral do estabelecimento não é uma substituição do ranking anual.

Solicitação aprovada conserva vínculo com seu ciclo original. Expiração posterior
da assinatura não apaga a visita. Excesso efetivamente coletado é registrado e
refletido na disponibilidade, sem cobrança automática de excedente inventada.

## 7. Consultas

| Método | Endpoint | Acesso e conteúdo |
|---|---|---|
| GET | `/api/v1/collections` | Estabelecimento: somente suas coletas |
| GET | `/api/v1/collections/{id}` | Titular: fato, motivos e cálculo vigente |
| GET | `/api/v1/establishments/me/score` | Saldo geral, sucessos acumulados e volume efetivo |
| GET | `/api/v1/admin/collections` | ADMIN; filtros adicionais establishmentId e driverId |
| GET | `/api/v1/admin/collections/{id}` | ADMIN; detalhe atual sem logs internos |
| GET | `/api/v1/admin/drivers/{id}/collections` | ADMIN; histórico operacional do motorista |
| GET | `/api/v1/admin/drivers/{id}/metrics` | ADMIN; indicadores por período |

Listas aceitam result, recordStatus, from, until, page e size. Tamanho padrão 20,
máximo 100; ordem collectionDate DESC,id DESC. Sem filtro de status, anuladas
aparecem explicitamente identificadas. Períodos são [from,until), pela collectionDate:
confirmação no registro normal e chegada na visita administrativa. As leituras
compostas usam um snapshot de transação para evitar combinar revisões diferentes.

O detalhe retorna somente point_calculation.is_current e seus componentes, sem
somar revisões antigas. Revisão da coleta e do cálculo não precisam coincidir.

Métricas consideram somente RECORDED: quantidade de visitas (inclusive zero litros),
volume efetivo (inclusive insucesso), estabelecimentos distintos, última data no
período e totais de sucesso/insucesso. Motivos múltiplos não multiplicam visitas.
Sem datas, consideram todo o histórico. Motorista inativo preserva os dados.
Esses indicadores não representam disponibilidade por rota ou culpa do motorista.

## 8. Segurança, auditoria e erros

Perfis e titularidade são verificados no backend. O ADMIN vem da sessão, nunca
de um campo livre. Escritas administrativas revalidam e bloqueiam seu usuário
contra inativação concorrente. Consulta de outro titular responde 404.

AuditContext.driver usa DRIVER_FORM, limpa current_user_id e identifica o motorista.
AuditContext.user/system limpam operational_driver_id. Contextos são locais à
transação; nenhuma autoria deve contaminar a próxima conexão do pool.

| HTTP | Interpretação |
|---|---|
| 400 | Corpo, tipos, estrutura, período ou campos desconhecidos inválidos |
| 401 | Bearer/sessão ausente ou inválida onde necessária |
| 403 | Perfil ou identificação operacional não autorizada |
| 404 | Recurso inexistente ou fora da titularidade |
| 409 | Transição incompatível, duplicidade ou reenvio divergente |
| 422 | Fatos/motivos incoerentes com a operação |
| 429 | Limite técnico por IP |
| 500/503 | Falha interna/indisponibilidade, sem expor SQL ou valores pessoais |

CollectionOperationErrors interpreta somente rejeições deliberadas de PL/pgSQL,
identificadas pela metadata estruturada, e distingue a operação. Um 22023 de
chegada/aceite após validação representa estado incompatível; no registro pode
representar fatos inconsistentes. Falta real de EXECUTE não vira erro de cliente.
Não há interpretação textual de mensagens SQL nem retry silencioso de transações.

## 9. Integração com ranking Redis

A API Redis usa identidade própria, membro de olius_ranking_reader. Lê
vw_annual_scores e metadados mínimos, publica uma projeção completa e mantém a
última versão íntegra quando o rebuild falha. A Core não recebe leitura dessa
view nem chama rebuild em cada coleta. O rebuild periódico fica na API Redis;
a homologação também pode acionar seu endpoint operacional autorizado.

Com Bearer da Core, a API Redis valida a sessão por GET /api/v1/auth/session e
retorna myPosition. Sem token, seu contrato atual permite ranking público, sem
posição pessoal. Isso não dá acesso às tabelas de sessão no banco.

O contrato atual trabalha com o ano corrente. Consulta/reconstrução de rankings
de anos anteriores não é uma funcionalidade entregue por este módulo.

## 10. Banco instalado e limites do escopo

As consultas somente leitura confirmaram a migração nos dois ambientes em
29/10/2026: desenvolvimento PostgreSQL 16.15 e Aiven 18.6. As quatro rotinas
conferem com a origem, os dois motivos estão ativos, os entrypoints são
SECURITY DEFINER com proprietário interno e o núcleo continua privado.

Snapshot de testes atualizado: script 04, roles, catálogo documental e referências
iniciais/validação, com hashes conferidos. Nenhum script oficial do DB_Segundo foi
alterado por esta implementação. A Core não executa migração automaticamente.

Correções/anulações por endpoints e reconstrução administrativa permanecem na
Sprint 10. Os testes podem exercitar as rotinas já existentes para comprovar que
reenvios/leitura respeitam correções; isso não cria endpoints dessa sprint futura.

## Validação

Validação local concluída em 29/10/2026:

- Suíte completa da Core: **459 testes** em PostgreSQL **18.6**, zero falhas,
  erros ou testes ignorados; Maven verify com BUILD SUCCESS.
- Matriz focada: **55 testes** em PostgreSQL **16.15**, zero falhas, erros ou
  testes ignorados. Inclui 29 casos/execuções operacionais, nove do tradutor,
  dez de motoristas, cinco do limitador, manifesto e referências oficiais.
- Containers PostgreSQL criados pelos testes oficiais com Testcontainers,
  login limitado olius_test_api e schema/catálogo/roles oficiais.
- Reenvios sequenciais/concorrentes, correção prévia, chegada/aceite/cancelamento,
  exceção administrativa, auditoria, motivos múltiplos, tolerâncias, piso zero,
  consumo real e recorrência até o 110º sucesso.
- Certificado reconciliado considerando volume efetivo de visita malsucedida.
- Falha simulada no cálculo reverte fato/motivos/auditoria/pontos; revogação
  temporária de EXECUTE no banco de teste continua retornando erro interno.
- Swagger e consultas administrativas/titulares validados.

### Homologação real com a API Redis

Executada com uma cópia sem alterações do projeto redis-api em DB_Segundo,
PostgreSQL 16.15 e Redis 7 descartáveis e as duas aplicações Spring Boot rodando
por HTTP em portas locais temporárias. O código da API Redis não foi alterado.
Seed demonstrativo desativado; o rebuild foi disparado pelo endpoint operacional
com token exclusivo de teste, não por uma chamada da Core.

Sequência e resultados:

1. Cadastro/login e preparação de uma assinatura artificial exclusivamente na base
   descartável, sem usar ambientes ou credenciais reais de pagamento.
2. Pedido, agendamento, chegada, aceite e coleta realizados pelos endpoints da Core.
   Reenvio HTTP preservou o UUID e o horário, com uma única aplicação dos pontos.
3. API Redis leu os 60 pontos oficiais com seu login próprio e retornou myPosition
   após validar o JWT real no endpoint de sessão da Core.
4. Redis foi desligado e a Core confirmou uma segunda coleta; saldo oficial 120.
5. Após religar Redis, novo rebuild publicou 120 pontos, sem duplicar reenvios.
6. Uma revogação de leitura da view, somente no banco descartável, provocou falha
   de rebuild. A API Redis preservou os 120 pontos e o updatedAt anteriores.
7. Foram conferidas identidades distintas: Core sem SELECT da view anual e login
   de ranking sem INSERT em collection. As permissões da fixture foram restauradas.

Os processos e containers temporários foram encerrados. Nenhum dado dos bancos
de desenvolvimento/produção foi alterado nesta homologação. Não se alterou o
repositório do colega nem se acionaram serviços reais de e-mail/pagamento.

Os logs de encerramento da suíte exibiram avisos de conexões fechadas quando
containers foram removidos; não houve falhas de teste ou de build.
O CI remoto/Sonar e a revisão da PR permanecem para a publicação. Nenhum commit
ou push foi realizado nesta etapa.
