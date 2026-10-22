# Contratos propostos para a jornada B2C

**Estado:** proposta para revisão; este arquivo não cria endpoints nem aprova decisões de produto.  
**Base examinada:** commit `849031c` da Core API, scripts 01–05 e `access_control/01_roles.sql` da fixture, `docs/identidade-seguranca.md`, `docs/tratamento-de-erros.md` e o guia de desenvolvimento B2C de 10/10/2026.

## 1. Escopo e dependências

A jornada pertence à Core API existente, sob `/api/v1`: solicitação de PEV, gestão administrativa, identificação do cidadão por QR, entrega aceita, histórico e pontos, correção/anulação. Autenticação, sessões, auditoria, Problem Details e PostgreSQL são compartilhados. Ranking/Redis é uma integração da Sprint 5 e precisa de contrato conjunto.

Os documentos funcionais **Contexto do Projeto - OLIUS**, **Regras de Negócio - OLIUS**, os critérios das Sprints 3/4 e o contrato textual `API_DATABASE_CONTRACT.md` não estão neste checkout. A fixture SQL é uma cópia versionada, não substitui a confirmação do banco oficial. As propostas abaixo devem ser confrontadas com essas fontes antes de serem tratadas como decisão aprovada.

## 2. Garantias verificadas na base atual

| Tema | Contrato observado |
| --- | --- |
| Identidade | Perfis `CITIZENS`, `ESTABLISHMENT`, `ADMIN`. `Caller.id` e `Caller.type` extraem a identidade autenticada; ser responsável por PEV é vínculo no banco, não perfil. |
| Auditoria | `AuditContext.user(id, reason)` requer transação ativa e define contexto local à transação. IDs de autor, validador e administrador não vêm do corpo HTTP. |
| PEV | `pev` tem exatamente um responsável, cidadão **ou** estabelecimento, endereço próprio `owner_kind=PEV` e unicidade por responsável, inclusive para registros rejeitados/inativos. Nasce `PENDING`; aprovação exige autor/data juntos. `is_pev` é sincronizado por trigger. |
| QR | `user_qr_code` centraliza o token vigente, com unicidade global. Sua atualização propaga o token às especializações por FK. A role da API pode atualizar `qr_token`, não escrever separadamente os tokens especializados. |
| Entrega | `record_pev_delivery(UUID, UUID, UUID, UUID, NUMERIC, TIMESTAMPTZ, OUT UUID)` valida responsável ativo de PEV aprovado, cidadão ativo, data não futura, volume positivo e idempotência. Proíbe beneficiário igual ao validador. Calcula `FLOOR(volume)` por entrega e reconstrói pontos internamente. |
| Correção | `correct_pev_delivery(UUID, INTEGER, UUID, record_status_t, NUMERIC, TIMESTAMPTZ, TEXT)` exige ADMIN ativo, justificativa e revisão esperada. Revisão antiga produz SQLSTATE `40001`; anulação mantém os dados físicos e zera o efeito vigente. |
| Permissões | `olius_api` pode consultar as tabelas de negócio, inserir PEV/endereço e atualizar colunas autorizadas; entrega e pontos são escritos pelas procedures. Não há DELETE físico para PEV ou entrega. |
| HTTP | A API já usa Bearer, `/api/v1`, Problem Details e `X-Request-Id`. `/api/v1/admin/**` exige ADMIN no filtro, mas a autorização do recurso ainda deve ocorrer no Service. |

## 3. Operações REST propostas

As rotas são propostas para discussão. Respostas de lista precisam de paginação, limite e ordem estável; nenhum lookup deve expor CPF, e-mail ou endereço pessoal.

| Operação | Rota proposta | Acesso e resposta proposta |
| --- | --- | --- |
| Solicitar PEV | `POST /api/v1/pevs` | Cidadão/estabelecimento ativo; responsável do token. `201` com `Location` e PEV criado. |
| Consultar próprio PEV | `GET /api/v1/pevs/me` | Responsável autenticado. `200`; definir representação quando ainda não existe. |
| Localizar PEVs | `GET /api/v1/pevs` | Apenas aprovados; publicidade, campos, filtros e limites pendentes. |
| Mudar estado | `PATCH /api/v1/admin/pevs/{id}/status` | ADMIN; `200` com estado atualizado ou `204`, a decidir. Transições pendentes. |
| Resolver QR | `POST /api/v1/qr/resolutions` | Contexto operacional autorizado; token no corpo, resposta mínima. Público-alvo e campos pendentes. |
| Rotacionar próprio QR | `POST /api/v1/users/me/qr/rotations` | Titular autenticado; novo token retornado apenas ao titular. Forma exata da resposta pendente. |
| Registrar entrega | `POST /api/v1/pevs/{id}/deliveries` | Responsável autenticado do PEV aprovado; `201` na criação. Resposta de reenvio idempotente pendente. |
| Histórico próprio | `GET /api/v1/users/me/deliveries` | Cidadão beneficiário; `200` paginado. |
| Entregas do PEV | `GET /api/v1/pevs/{id}/deliveries` | Responsável do PEV; acesso ADMIN e dados mínimos pendentes. |
| Pontos próprios | `GET /api/v1/users/me/points` | Titular; separar saldo geral, movimentações vigentes e eventual recorte anual. |
| Corrigir/anular | `POST /api/v1/admin/deliveries/{id}/corrections` | ADMIN; motivo e revisão esperada. `200` com revisão resultante proposto. |

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

O token identifica o cidadão, mas não autoriza a operação. O responsável autenticado é `p_validator_id`; o cidadão resolvido é `p_citizen_id`. A mesma chave deve ser repetida com os mesmos dados, inclusive data, após timeout. A inclusão e a política de `deliveryDate` dependem da decisão sobre lançamento retroativo. O volume usa decimal de até duas casas, maior que zero e no máximo `999999.99`, sem arredondamento silencioso. O token de estabelecimento não é beneficiário B2C.

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

Para `ANNULLED`, a procedure preserva volume e data anteriores; decidir se esses campos devem ser omitidos ou rejeitados no DTO. A data de uma correção `RECORDED` não pode superar a criação original. `correctedBy`, pontos e nova revisão são gerados no servidor/banco.

### Formatos de saída a fechar

- **PEV:** `id`, `status`, `address`, `createdAt`; `approvedAt` e dados de responsável somente conforme contexto. A localização pública não herda automaticamente esses campos.
- **Entrega:** `id`, `pevId`, `oilVolumeLiters`, `deliveryDate`, `createdAt`, `pointsEarned`, `recordStatus`, `revision`. O histórico do PEV deve usar identificador/dados mínimos do beneficiário aprovados para esse contexto.
- **Pontos:** `balance` geral e movimentações atuais, sem somar revisões antigas. Pontuação anual de ranking não é sinônimo de saldo geral.
- **Paginação:** definir tamanho padrão/máximo, filtros permitidos, ordenação determinística e formato de cursor ou página antes da implementação.

## 4. Autorização e transações

1. Toda escrita abre transação, deriva a identidade do Bearer, verifica perfil e titularidade e chama `AuditContext.user` antes da alteração.
2. Criação de PEV persiste endereço PEV e PEV na mesma transação. A unicidade do banco fecha corridas de duas solicitações.
3. Aprovação/rejeição/inativação não são autorizadas apenas pela existência do enum. A matriz de transições precisa ser aprovada. A API nunca escreve `establishment.is_pev`.
4. Registro usa `CALL record_pev_delivery` com OUT e a conexão transacional gerenciada. Não grava entrega, pontos ou saldo por `save` genérico. Após procedure/trigger, relê projeções necessárias para evitar entidade JPA desatualizada.
5. Correção usa `CALL correct_pev_delivery` com `expectedRevision` fornecida pelo cliente. Em `40001`, retorna conflito para releitura; não troca a revisão nem repete automaticamente.
6. Rotação escreve apenas `user_qr_code.qr_token`, dentro de transação auditada. O token anterior deixa de identificar. Nunca registrar o segredo em logs.

## 5. Erros e testes de contrato

Usar o `GlobalExceptionHandler`, `ApiProblemFactory`, `PersistenceExceptionTranslator` e os handlers de segurança existentes. Os códigos públicos atuais incluem `PEV_ALREADY_EXISTS`, `BUSINESS_STATE_CONFLICT`, `BUSINESS_RULE_VIOLATION` e `CONCURRENT_MODIFICATION`. Erros previstos: `400` entrada inválida, `401` sem autenticação, `403` sem permissão, `404` recurso consultável ausente, `409` duplicidade/estado/revisão. A tradução de SQLSTATE `22023`, `22000`, `42501`, `55000` e `P0002` deve considerar a operação; `42501` também pode ser falha de privilégio técnico. Não inferir regra por texto SQL nem expor token, documento ou parâmetros internos.

Os testes das próximas PRs devem usar `OfficialPostgres`, a role limitada e requisições HTTP com JWT real. Cobrir PEV cidadão/estabelecimento, duplicidade concorrente, aprovação/inativação e trigger; QR anterior inválido; entrega parcial, frações, volume inválido, data futura, titularidade e idempotência concorrente; correção/anulação, revisão antiga, auditoria e saldos. A fixture não instala `06_ctes.sql` nem massa `data_load`.

## 6. Decisões necessárias antes das respectivas PRs

| Decisão | Impacta |
| --- | --- |
| Confirmar proibição de autoentrega presente na procedure e ausente dos documentos funcionais consultados pelo guia. | Entrega |
| Matriz de transições; reaprovação, ressubmissão, troca de responsável e edição de endereço. | PEV e gestão |
| Acesso público à localização, campos exibidos, filtros e eventual busca geográfica. | Consulta de PEV |
| Quem pode resolver/rotacionar QR, dados mínimos e formato de apresentação. Compatibilizar com B2B e com `GET /users/me`, que hoje devolve o próprio `qrToken`. | QR |
| Registro imediato ou data física informada; retroatividade e persistência exata da data no reenvio. | Entrega e idempotência |
| Status/corpo no reenvio idempotente, representação de anulação, detalhes administrativos e histórico de revisões sem SELECT direto nos logs pela role da API. | Entrega e correção |
| Paginação, filtros, ordem e limites de cada lista. | Consultas |
| Origem, ano, versão, visibilidade, desempate e publicação durável/reconstrução para ranking, com a frente NoSQL. | Integração Sprint 5 |

**Critério para encerrar esta branch:** contratos e exemplos revisados com os responsáveis, decisões acima registradas como aprovadas ou explicitamente pendentes com bloqueio da operação correspondente. As PRs seguintes implementam cada parte sobre a base aprovada.
