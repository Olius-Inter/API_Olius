# Sprint 10 — Correção, anulação e restauração de coletas B2B

## Para que serve

Uma coleta registrada pode conter informações incorretas. O administrador pode
corrigir esses dados, desconsiderar a coleta por anulação ou restaurar uma coleta
anulada. Nenhuma dessas operações apaga a visita: cada alteração incrementa sua
revisão e o banco conserva os registros de auditoria.

O resultado da coleta e os pontos são derivados dos fatos, nunca escolhidos pelo
administrador. A Core chama `correct_collection`; o PostgreSQL reconstrói saldo e
recorrência e reconcilia certificados atomicamente. As operações de escrita usam
essas rotinas existentes. A consulta operacional depende da função restrita,
índice e concessão de EXECUTE implementados pelo responsável do banco, sem novas
fórmulas ou acesso direto da Core aos logs.

## Quem pode usar

Somente ADMIN autenticado, com sessão válida e conta ativa. O serviço também
revalida o administrador no banco. Estabelecimentos continuam consultando suas
próprias coletas e cálculos vigentes, inclusive registros identificados como
anulados, mas não podem corrigir registros nem consultar o histórico administrativo.

## Endpoints

Todos usam Bearer JWT e retornam 200 quando concluídos.

| Método | Caminho | Entrada |
|---|---|---|
| POST | `/api/v1/admin/collections/{id}/corrections` | Correção de coleta RECORDED |
| POST | `/api/v1/admin/collections/{id}/annulments` | Anulação de coleta RECORDED |
| POST | `/api/v1/admin/collections/{id}/restorations` | Restauração de coleta ANNULLED |
| GET | `/api/v1/admin/collections/{id}/calculation-revisions` | Histórico paginado dos cálculos |
| GET | `/api/v1/admin/collections/{id}/revisions` | Histórico operacional paginado |

Correção e restauração recebem `expectedRevision`, `justification`,
`collectedVolumeLiters`, `presentedVolumeLiters`, `oilCondition`,
`hasCompromisingOccurrence`, `failureReasonCodes` e `observation` opcional.
Anulação recebe somente `expectedRevision` e `justification`.

Exemplo de correção ou restauração:

```json
{
  "expectedRevision": 1,
  "justification": "Volume conferido no comprovante da visita",
  "collectedVolumeLiters": 10,
  "presentedVolumeLiters": 10,
  "oilCondition": "ACCEPTABLE",
  "hasCompromisingOccurrence": false,
  "failureReasonCodes": [],
  "observation": null
}
```

A restauração recebe os fatos que voltarão a valer; ela não reaproveita
automaticamente dados que possam estar incorretos. Motivos precisam continuar
ativos. Uma visita sem aceite deve preservar a ausência de atendimento; não pode
ser transformada em atendimento aceito por correção/restauração.

Validações estruturais reutilizam os limites técnicos da Sprint 9: até 1000
caracteres em observações/justificativas, até 100 entradas de motivos, códigos com
até 50 caracteres e volumes não negativos com até duas casas decimais. Os nomes
dos motivos e as regras de sucesso continuam definidos no banco.

O cliente não controla autor, resultado, pontos, revisão nova, datas de auditoria,
motorista, estabelecimento, solicitação, ciclo ou horário físico da visita.

## Concorrência e reenvios

O administrador consulta a revisão atual e a envia em `expectedRevision`. Antes
da alteração, a Core bloqueia o estabelecimento na mesma ordem utilizada pelas
rotinas operacionais e relê a coleta. Uma revisão antiga retorna 409 com
`CONCURRENT_MODIFICATION`; não há atualização silenciosa da revisão esperada.

As mudanças e reconstruções ficam na mesma transação Spring. O contexto de
auditoria usa USER, o UUID do ADMIN autenticado, motorista operacional vazio e a
justificativa; seus valores são locais à transação. Falhas revertem a operação.

Em timeout, consultar novamente a coleta antes de tentar outra alteração. A API
não promete repetir a resposta original de uma correção nem aplica retry
automático. A revisão impede duplicação silenciosa. Uma transição incompatível
com o estado atual retorna conflito de negócio.

## Como o saldo é reconstruído

O piso zero é aplicado a cada evento em sua ordem lógica original. Exemplo:
saldo 20, penalidade nominal -50 e ganho +60 resultam em 60. Anular a penalidade
reconstrói o saldo para 80, não 110. As operações posteriores podem receber novas
revisões de cálculo sem alterar sua revisão operacional.

O consumo é consultado pelas rotinas existentes: registros RECORDED contam uma
vaga e seu volume efetivamente coletado. A anulação/restauração afeta a consulta
do ciclo de origem, sem criar outra coleta, transferir o consumo para o mês atual,
reativar assinatura, criar reembolso ou renovar um ciclo encerrado. Certificados
são reconciliados pela rotina existente, preservando concessões e sua auditoria.

## Histórico de cálculos e histórico operacional

`calculation-revisions` retorna `items`, `page`, `size` e `totalElements`, ordenados
por revisão decrescente. Padrões: página 0 e tamanho 20; tamanho máximo 100.
Cada item contém o cálculo, componentes, indicador vigente, data, autor e motivo
quando existentes. Primeiros cálculos podem não ter autor de recálculo.

O histórico operacional é consultado separadamente em `revisions`, com o mesmo
envelope `items`, `page`, `size` e `totalElements`. Cada item apresenta os fatos
da revisão, situação, autoria, justificativas e referências à solicitação,
estabelecimento e motorista. `collectionDate` é a data física da visita;
`recordedAt` é o lançamento original; `changedAt` é o evento auditado da revisão.
`correctedAt`, `correctedBy` e `correctionReason` podem ser nulos no registro inicial.

O serviço usa uma transação READ ONLY/REPEATABLE READ e chama exclusivamente
`get_collection_revision_history(collectionId, authenticatedAdminId, page, size)`.
O ADMIN vem do principal autenticado. A Core revalida perfil/atividade e a função
complementa essa verificação. Não há SELECT direto nos logs nem login adicional.
Após a instalação dos papéis, a função é STABLE/SECURITY DEFINER, pertence a
`olius_business_owner` NOLOGIN e concede EXECUTE somente a `olius_api`.

Página vazia é representada pelo banco com uma linha de metadados e `revision`
nula. A Core conserva o total e devolve `items: []`, sem criar um item fictício.
Coleta inexistente retorna 404. O contrato do banco valida toda a cadeia antes de
paginar: histórico ausente, incompleto ou temporalmente ambíguo retorna SQLSTATE
55000, tratado como 500/INTERNAL_ERROR, nunca lista vazia, nem conflito a resolver
reenviando uma alteração. Falha real de ACL permanece 500; a recusa administrativa
explícita da função é 403. Não são interpretadas mensagens SQL.

`failureReasons` contém objetos `{id, currentCode}`. O UUID preserva o vínculo
histórico; `currentCode` é um rótulo do catálogo atual, não o código original
garantido. Se o catálogo não contiver mais o motivo, currentCode pode ser nulo.
Não se substituem associações antigas pelos motivos atualmente ligados à coleta.

A função separa os snapshots BEFORE/AFTER e as revisões operacionais das revisões
de cálculo. Para associações de motivos, o modelo existente utiliza fronteiras
temporais; ambiguidades são recusadas pelo banco. Não houve duplicação de histórico,
criação de tabelas, concessão de leitura dos logs à Core ou reconstrução ad hoc em Java.

Cada requisição HTTP possui seu próprio snapshot. Uma correção confirmada entre
duas requisições pode deslocar as páginas; este endpoint não promete um snapshot
persistente entre requisições. A leitura não altera contexto de auditoria ou dados.

### Instalação em base existente

Pré-requisito: o responsável do banco deve aplicar integralmente
`migrations/20261030_collection_revision_history.sql`, conforme seu guia. Não
reexecutar os scripts de estrutura 01–03 nem conceder SELECT aos logs para
contornar falta da função. Os snapshots de teste foram sincronizados com o banco
oficial (03, 04 e access_control/01_roles), com hashes no manifesto atualizado.
Esses snapshots instalam somente bancos descartáveis, não migram ambientes reais.

O usuário informou em 31/10/2026 que aplicou a migração em desenvolvimento e
produção. A integração desta entrega não executou a migração nem conferiu por
conexão o catálogo dessas instâncias; suas verificações usam bancos descartáveis.

## Ranking e integrações

O PostgreSQL é a fonte de verdade. A API Redis consulta os dados confirmados no
próximo rebuild bem-sucedido; uma resposta da Core não garante ranking já atualizado.
A Core não envia POST de publicação, não escreve no Redis e não chama serviços
externos enquanto mantém bloqueios. A validação automatizada desta sprint cobre
o banco e a Core; homologação ponta a ponta com Redis é uma verificação separada.

## Erros e testes

Entradas inválidas retornam 400; sessão inválida 401; perfil sem autorização 403;
coleta inexistente 404; conflito de revisão/estado 409; fatos incompatíveis 422.
Erros desconhecidos ou falhas reais de permissões permanecem internos, sem
interpretar mensagens SQL ou expor dados pessoais. O tradutor específico só trata
SQLSTATE conhecido emitido explicitamente pelas rotinas.

`CollectionCorrectionsIntegrationTest` usa `OfficialPostgres`, role limitada,
JWTs reais e PostgreSQL descartável. Exercita autorização, validação, revisão,
rollback, auditoria, piso zero, recorrência, concorrência com correção e com nova
coleta, consumo, restauração, certificados, histórico de cálculos e OpenAPI.
Também cobre o histórico operacional, paginação vazia, motivos sem rótulo atual,
falha fechada sem auditoria, ACL real, ausência de efeitos e snapshot concorrente.
Nenhum desses testes deve usar os bancos de desenvolvimento ou produção.

### Evidências locais — 30/10/2026

- Suíte completa com PostgreSQL 16.15: 488 testes, sem falhas, erros ou ignorados;
  `mvnw.cmd -B verify` concluído com sucesso.
- Verificação focada da versão final com PostgreSQL 18.6: 26 testes (13 de
  integração de correções e 13 do tradutor de erros), sem falhas, erros ou
  ignorados. Inclui a verificação adicional de rollback após falha técnica durante
  a reconstrução e a restauração em ciclo encerrado.
- Conferência dos scripts principais 01–08 e de `access_control/01_roles.sql`:
  snapshots usados nos testes possuem o mesmo conteúdo dos arquivos atuais do
  banco, descontando apenas quebras de linha.
- Não houve alteração de schema, permissões, dependências, configurações de CI
  ou credenciais. Não foram executadas alterações em produção/desenvolvimento,
  chamadas reais à Stripe ou envio de e-mails.
- Não foi homologada nesta etapa uma instância externa da API Redis. O histórico
  operacional completo era pendente nesta validação de 30/10; foi integrado em
  31/10/2026 pelo contrato restrito descrito acima.

### Integração do histórico operacional — 31/10/2026

- Revisados a função completa, o índice, as concessões, a migração e os contratos
  do banco. O corpo da função na migração é igual ao do script canônico 04.
- A Core acrescenta GET `/api/v1/admin/collections/{id}/revisions`, DTOs próprios
  e chamada JDBC parametrizada da função. As rotas e o histórico de cálculos
  existentes foram preservados. Não houve mudanças no repositório do banco.
- Testes focados iniciais: 33 aprovados no PostgreSQL 16.15, incluindo o manifesto.
- Suíte completa: 495 testes aprovados no PostgreSQL 16.15, sem falhas, erros ou
  testes ignorados; Maven verify concluído com sucesso.
- Compatibilidade: 32 testes focados aprovados no PostgreSQL 18.6 (18 de integração
  e 14 do tradutor), sem falhas, erros ou testes ignorados.
- A leitura foi validada com a role limitada: histórico inicial/corrigido/anulado/
  restaurado, atores, justificativas, motivos antigos, rótulos atuais nulos,
  paginação intermediária/vazia e offset máximo, negação de acesso e ACL real,
  falha fechada sem auditoria, ausência de alteração do contexto e snapshot
  repetível durante uma correção concorrente. Swagger também foi verificado.
- A antiga pendência de histórico operacional está resolvida na implementação
  local. Publicação, revisão da PR e execução do CI remoto são etapas posteriores;
  não foram feitos commits, push ou alterações de status no ClickUp nesta entrega.
