# Contrato da jornada B2C — decisões aprovadas e pontos de integração

**Estado:** regras funcionais aprovadas abaixo; rotas e formatos identificados como propostas ainda exigem conciliação. Este arquivo não implementa endpoints.

**Fontes:** orientação consolidada para o desenvolvimento B2C encaminhada em 18/10/2026, conforme decisões comunicadas à equipe; commit `849031c` da Core API, scripts 01–05 e `access_control/01_roles.sql` da fixture, `docs/identidade-seguranca.md` e `docs/tratamento-de-erros.md`.

## 1. Escopo e dependências

A jornada pertence à Core API existente, sob `/api/v1`: solicitação e reapresentação de PEV, gestão administrativa, identificação do cidadão por QR, entrega aceita, histórico e pontos, correção/anulação/restauração. Autenticação, sessões, auditoria, Problem Details e PostgreSQL são compartilhados.

O PostgreSQL é a fonte da pontuação consolidada. Na arquitetura aprovada para a Sprint 5, a API Redis consulta essa pontuação por uma identidade de leitura restrita; a Core não publica cada entrega ou coleta por HTTP para alimentar o ranking. A definição da consulta e das permissões dessa identidade pertence à integração com a Sprint 5. Os SQL da fixture são cópias versionadas: sua conferência com a revisão oficial necessária à implementação continua pendente.

## 2. Garantias verificadas na base atual

| Tema | Contrato observado |
| --- | --- |
| Identidade | Perfis `CITIZENS`, `ESTABLISHMENT`, `ADMIN`. `Caller.id` e `Caller.type` extraem a identidade autenticada; ser responsável por PEV é vínculo no banco, não perfil. |
| Auditoria | `AuditContext.user(id, reason)` requer transação ativa e define contexto local à transação. IDs de autor, validador e administrador não vêm do corpo HTTP. |
| PEV | `pev` tem exatamente um responsável, cidadão **ou** estabelecimento, endereço próprio `owner_kind=PEV` e unicidade por responsável, inclusive para registros rejeitados/inativos. Nasce `PENDING`; aprovação exige autor/data juntos. `is_pev` é sincronizado por trigger. |
| QR | `user_qr_code` centraliza o token vigente, com unicidade global. Sua atualização propaga o token às especializações por FK. A role da API pode atualizar `qr_token`, não escrever separadamente os tokens especializados. |
| Entrega | `record_pev_delivery(UUID, UUID, UUID, UUID, NUMERIC, TIMESTAMPTZ, OUT UUID)` valida, na criação, responsável ativo de PEV aprovado, cidadão ativo, data não futura e volume positivo. No reenvio de chave existente, compara o INSERT original antes de verificar o estado atual do PEV. Proíbe beneficiário igual ao validador na criação, calcula `FLOOR(volume)` por entrega e reconstrói pontos internamente. |
| Correção | `correct_pev_delivery(UUID, INTEGER, UUID, record_status_t, NUMERIC, TIMESTAMPTZ, TEXT)` exige ADMIN ativo, justificativa e revisão esperada. Revisão antiga produz SQLSTATE `40001`; anulação mantém os dados físicos e zera o efeito vigente. |
| Permissões | `olius_api` pode consultar as tabelas de negócio, inserir PEV/endereço e atualizar colunas autorizadas; entrega e pontos são escritos pelas procedures. Não há DELETE físico para PEV ou entrega. |
| HTTP | A API já usa Bearer, `/api/v1`, Problem Details e `X-Request-Id`. `/api/v1/admin/**` exige ADMIN no filtro, mas a autorização do recurso ainda deve ocorrer no Service. |

## 3. Regras funcionais aprovadas

- **PEV:** transições permitidas `PENDING → APPROVED/REJECTED`, `REJECTED → PENDING`, `APPROVED → INACTIVE` e `INACTIVE → APPROVED`. `REJECTED → PENDING` reapresenta o mesmo PEV, pois a unicidade também abrange rejeitados. Não há transferência de responsável no MVP. O proprietário só edita o endereço próprio do PEV em `PENDING` ou `REJECTED`.
- **Localização:** consulta pública apenas de PEVs `APPROVED`, com endereço próprio do ponto e coordenadas disponíveis. Não divulgar dados pessoais adicionais do responsável. O contrato da consulta geográfica do mapa ainda precisa ser definido.
- **QR:** somente o responsável ativo por PEV aprovado resolve o QR no contexto de entrega; a resposta traz identificação mínima do cidadão. O próprio titular pode rotacionar seu QR. Autoentrega é proibida.
- **Entrega:** `deliveryDate` é a data física obrigatória, não futura. Lançamento retroativo é permitido sem limite arbitrário de dias. Registra-se somente volume aceito, com pontos calculados por evento no banco.
- **Correção:** ADMIN pode anular sem editar volume/data. Uma entrega anulada pode ser restaurada a `RECORDED` com justificativa, revisão esperada e recálculo; na restauração, os valores informados devem cumprir as validações da procedure. O histórico e as revisões são preservados.

## 4. Operações REST propostas

Os nomes finais das rotas e as representações são propostas para conciliação. As capacidades e regras funcionais da seção anterior já estão aprovadas. Listas precisam de paginação, limites e ordem estável; nenhum lookup deve expor CPF, e-mail ou endereço pessoal.

| Operação | Rota proposta | Acesso e resposta proposta |
| --- | --- | --- |
| Solicitar PEV | `POST /api/v1/pevs` | Cidadão/estabelecimento ativo; responsável do token. `201` com `Location` e PEV criado. |
| Consultar próprio PEV | `GET /api/v1/pevs/me` | Responsável autenticado. `200`; definir representação quando ainda não existe. |
| Reapresentar PEV rejeitado | `POST /api/v1/pevs/me/resubmissions` | Mesmo responsável; altera o PEV `REJECTED` para `PENDING`, sem criar outro. |
| Alterar endereço próprio | `PATCH /api/v1/pevs/me/address` | Proprietário autenticado; somente PEV `PENDING` ou `REJECTED`. |
| Localizar PEVs | `GET /api/v1/pevs` | Público; somente `APPROVED`, com endereço do ponto e coordenadas disponíveis. Filtros, limites e consulta geográfica pendentes. |
| Listar/detalhar PEVs para gestão | `GET /api/v1/admin/pevs` e `GET /api/v1/admin/pevs/{id}` | ADMIN; fila, estados e detalhe autorizado, com paginação na lista. |
| Mudar estado | `PATCH /api/v1/admin/pevs/{id}/status` | ADMIN; somente transições aprovadas. `200` com estado atualizado ou `204`, a decidir. |
| Resolver QR | `POST /api/v1/qr/resolutions` | Responsável ativo por PEV `APPROVED`; token no corpo, identificação mínima do cidadão. |
| Rotacionar próprio QR | `POST /api/v1/users/me/qr/rotations` | Titular autenticado; forma exata da resposta pendente. |
| Registrar ou recuperar entrega | `POST /api/v1/pevs/{id}/deliveries` | Responsável autenticado do PEV; criação exige PEV aprovado e QR vigente. Recuperação por chave de entrega já confirmada segue a seção de idempotência. `201` na criação e `200` na recuperação; mecanismo concorrente para distingui-los pendente. |
| Histórico próprio | `GET /api/v1/users/me/deliveries` | Cidadão beneficiário; `200` paginado. |
| Entregas do PEV | `GET /api/v1/pevs/{id}/deliveries` | Responsável do PEV; dados mínimos do beneficiário e paginação. |
| Listar/detalhar entregas para gestão | `GET /api/v1/admin/deliveries` e `GET /api/v1/admin/deliveries/{id}` | ADMIN; lista paginada e detalhe, incluindo estado e revisão vigentes. |
| Pontos próprios | `GET /api/v1/users/me/points` | Titular; separar saldo geral, movimentações vigentes e eventual recorte anual. |
| Corrigir/anular/restaurar | `POST /api/v1/admin/deliveries/{id}/corrections` | ADMIN; motivo e revisão esperada. `ANNULLED` anula sem mudar volume/data; `RECORDED` corrige ou restaura. `200` com revisão resultante proposto. |

### Formatos de entrada propostos

Campos não listados são rejeitados pelo comportamento JSON atual. Os exemplos são ilustrativos; nomes finais e respostas ainda requerem aprovação.

```jsonc
// POST /api/v1/pevs
{
  "address": {
    "state": "SP", "city": "São Paulo", "neighborhood": "Centro",
    "street": "Rua Exemplo", "number": "10", "cep": "01001000",
    "complement": null, "latitude": null, "longitude": null
  }
}
```

O endereço segue o formato de `IdentityRequests.AddressInput`; a API cria um registro separado com `owner_kind=PEV`. `citizenId`, `establishmentId`, `status`, `approvedBy` e `approvedAt` não são entradas do cliente.

```jsonc
// POST /api/v1/pevs/{id}/deliveries
{
  "idempotencyKey": "579e649c-6a60-4eb6-9f34-1b44ed8fbacd",
  "qrToken": "token-lido-do-qr",
  "oilVolumeLiters": 3.70,
  "deliveryDate": "2026-10-18T14:00:00Z"
}
```

O token identifica o cidadão, mas não autoriza a operação. O responsável autenticado é `p_validator_id`; o cidadão identificado é `p_citizen_id`. A data física `deliveryDate` é obrigatória, não futura e pode ser retroativa sem limite arbitrário de dias. O volume usa decimal de até duas casas, maior que zero e no máximo `999999.99`, sem arredondamento silencioso. O token de estabelecimento não é beneficiário B2C.

**Criação:** exige QR vigente que resolva para cidadão ativo e responsável ativo do PEV `APPROVED`. Autoentrega é proibida. A chave de idempotência representa uma operação lógica; o cliente preserva a chave, o PEV, o volume e a data física em tentativas após timeout.

**Recuperação de entrega já confirmada:** com a mesma chave e o mesmo validador autenticado, a Core recupera o cidadão do registro existente antes de exigir resolução do QR; `qrToken` pode ser omitido nesse reenvio. O QR antigo não precisa continuar válido. A Core chama `record_pev_delivery` com o cidadão recuperado, o validador autenticado e o PEV, volume e `deliveryDate` enviados originalmente. A procedure compara esses dados canônicos com o snapshot do INSERT original preservado em auditoria, inclusive após correção administrativa. Ela não recebe nem compara o token bruto. A leitura preliminar deve ficar restrita à chave **e ao validador autenticado**; chave de terceiro não revela existência, cidadão ou payload. Não verificar antecipadamente o estado atual do PEV nesse caminho: um PEV hoje inativo não invalida a recuperação de entrega confirmada quando estava aprovado. Essa exceção vale apenas para recuperação, nunca para criar entrega nova com QR antigo ou em PEV não aprovado.

Com a mesma chave, cidadão ou validador diferentes são rejeitados como incompatibilidade de autoria (`42501` na procedure), sem revelar dados da operação original; PEV, volume ou `deliveryDate` diferentes geram conflito de idempotência (`22000`, a traduzir para `409` no contexto do registro). O tratamento de uma chave de terceiro deve manter a mesma proteção contra enumeração. A distinção concorrente entre resposta `201` para criação e `200` para recuperação ainda exige mecanismo de integração, pois a procedure retorna apenas o ID.

```jsonc
// POST /api/v1/admin/deliveries/{id}/corrections
{
  "expectedRevision": 1,
  "recordStatus": "RECORDED",
  "oilVolumeLiters": 3.00,
  "deliveryDate": "2026-10-18T14:00:00Z",
  "reason": "Correção do volume conferido"
}
```

Para `ANNULLED`, o contrato não permite editar volume ou data; o DTO deve rejeitar esses campos nessa operação. A procedure preserva os valores anteriores e zera o efeito vigente. `RECORDED` permite correção ou restauração de uma anulada, com justificativa, revisão esperada e recálculo. O volume deve ser positivo e a data não pode superar a criação original. `correctedBy`, pontos e nova revisão são gerados no servidor/banco.

### Formatos de saída a fechar

- **PEV:** `id`, `status`, `address`, `createdAt`; `approvedAt` e dados de responsável somente conforme contexto. A localização pública mostra endereço próprio e coordenadas disponíveis apenas de PEVs aprovados, sem dados pessoais adicionais.
- **Entrega:** `id`, `pevId`, `oilVolumeLiters`, `deliveryDate`, `createdAt`, `pointsEarned`, `recordStatus`, `revision`. O histórico do PEV deve usar identificador/dados mínimos do beneficiário aprovados para esse contexto.
- **Pontos:** `balance` geral e movimentações atuais, sem somar revisões antigas. Pontuação anual de ranking não é sinônimo de saldo geral.
- **Paginação:** a listagem pública de PEVs tem contrato inicial abaixo; as demais listas ainda precisam de tamanho, filtros, ordenação e formato final definidos.

### Primeira entrega: solicitação e consultas de PEV

Nesta etapa, `POST /api/v1/pevs` recebe apenas `address`, cria um endereço `owner_kind=PEV` e um PEV `PENDING` na mesma transação, deriva o responsável do Bearer e retorna `201` com `Location: /api/v1/pevs/me`. `GET /api/v1/pevs/me` devolve `id`, `status`, `createdAt`, `approvedAt` e endereço do próprio responsável; ausência de PEV retorna `404`. Ambos exigem conta ativa `CITIZENS` ou `ESTABLISHMENT`.

`GET /api/v1/pevs` é público e devolve somente PEVs `APPROVED`, com `id` e endereço próprio do ponto, incluindo coordenadas quando cadastradas. A resposta tem `items`, `page`, `size` e `totalElements`; `page` começa em zero, `size` padrão é 20 e máximo é 100. A ordem é `createdAt ASC, id ASC`. Filtros e consulta geográfica permanecem para integração posterior.

## 5. Autorização e transações

1. Toda escrita abre transação, deriva a identidade do Bearer, verifica perfil e titularidade e chama `AuditContext.user` antes da alteração.
2. Criação de PEV persiste endereço PEV e PEV na mesma transação. A unicidade do banco fecha corridas de duas solicitações.
3. Gestão de PEV aplica apenas as transições aprovadas na seção 3. Reapresentação reutiliza o PEV rejeitado; não há troca de responsável. Alteração de endereço pelo proprietário exige `PENDING` ou `REJECTED`. A API nunca escreve `establishment.is_pev`.
4. Registro usa `CALL record_pev_delivery` com OUT e a conexão transacional gerenciada. Na criação, resolve QR vigente e verifica o PEV aprovado. Na recuperação, encontra a operação por chave e validador autenticado antes da resolução de QR e não bloqueia pelo estado atual do PEV. A procedure confere o payload original. A API não grava entrega, pontos ou saldo por `save` genérico. Após procedure/trigger, relê projeções necessárias para evitar entidade JPA desatualizada.
5. Correção usa `CALL correct_pev_delivery` com `expectedRevision` fornecida pelo cliente. Em `40001`, retorna conflito para releitura; não troca a revisão nem repete automaticamente.
6. Rotação escreve apenas `user_qr_code.qr_token`, dentro de transação auditada. O token anterior deixa de identificar. Nunca registrar o segredo em logs.

## 6. Erros e testes de contrato

Usar o `GlobalExceptionHandler`, `ApiProblemFactory`, `PersistenceExceptionTranslator` e os handlers de segurança existentes. Os códigos públicos atuais incluem `PEV_ALREADY_EXISTS`, `BUSINESS_STATE_CONFLICT`, `BUSINESS_RULE_VIOLATION` e `CONCURRENT_MODIFICATION`. Erros previstos: `400` entrada inválida, `401` sem autenticação, `403` sem permissão, `404` recurso consultável ausente, `409` duplicidade/estado/revisão. A tradução de SQLSTATE `22023`, `22000`, `42501`, `55000` e `P0002` deve considerar a operação; `42501` também pode ser falha de privilégio técnico. Não inferir regra por texto SQL nem expor token, documento ou parâmetros internos.

Os testes das próximas PRs devem usar `OfficialPostgres`, a role limitada e requisições HTTP com JWT real. Cobrir PEV cidadão/estabelecimento, duplicidade concorrente, reapresentação, edição de endereço nos estados permitidos, transições e trigger; localização pública sem dados pessoais; QR anterior inválido para novas entregas; entrega parcial, frações, volume inválido, data futura, titularidade e idempotência concorrente; recuperação após rotação do QR e após inativação do PEV, sem expor chave alheia; correção, anulação, restauração, revisão antiga, auditoria e saldos. A fixture não instala `06_ctes.sql` nem massa `data_load`.

## 7. Alinhamentos de integração ainda necessários

| Decisão | Impacta |
| --- | --- |
| Mecanismo seguro sob concorrência para distinguir criação (`201`) de recuperação (`200`), pois `record_pev_delivery` retorna apenas o ID. | Entrega e idempotência |
| Formatos finais de respostas, paginação, filtros, ordenação e limites. | REST e consultas |
| Contrato da consulta geográfica do mapa. | Localização pública |
| Forma de apresentar ao Mobile a identidade e situação do PEV do titular, sem confundir `is_pev` com permissão para solicitar coleta B2B. | Mobile e PEV |
| Conferência da fixture SQL com a revisão oficial do banco necessária à implementação. | Testes e integração SQL |
| Detalhe da identificação mínima retornada na resolução do QR e formato de apresentação do próprio QR, em alinhamento com B2B e com `GET /users/me`. | QR |
| Detalhes administrativos de representação e histórico de revisões sem SELECT direto nos logs pela role da API. | Gestão e correção |
| Contrato de leitura restrita da pontuação consolidada pelo Redis, inclusive recorte anual e atualizações após correção, sem publicação HTTP por operação pela Core. | Sprint 5 |

**Critério para encerrar esta branch:** regras aprovadas registradas sem confundi-las com nomes finais de rotas ou formatos pendentes. Decisões que alterem contratos consumidos por Mobile/Frontend, estrutura ou permissões do banco, ou regras de negócio devem ser alinhadas com os respectivos responsáveis. Escolhas técnicas internas seguem os contratos acima.
