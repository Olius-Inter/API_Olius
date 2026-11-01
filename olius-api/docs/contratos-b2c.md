# Contrato da jornada B2C — decisões aprovadas e pontos de integração

**Estado:** contrato conciliado com as rotas implementadas na Core. Capacidades ainda sem endpoint são identificadas separadamente. Consulte o [inventário](jornada-b2c.md) e o [guia do Mobile](mobile-jornada-b2c.md) para a sequência de uso e os limites atuais.

**Fontes:** orientação consolidada para o desenvolvimento B2C encaminhada em 18/10/2026 e decisão posterior da equipe sobre QR único por titular; commit `849031c` da Core API, scripts 01–05 e `access_control/01_roles.sql` da fixture, `docs/identidade-seguranca.md` e `docs/tratamento-de-erros.md`.

## 1. Escopo e dependências

A jornada pertence à Core API existente, sob `/api/v1`: solicitação e reapresentação de PEV, gestão administrativa, identificação do cidadão por QR, entrega aceita, histórico e pontos, correção/anulação/restauração. Autenticação, sessões, auditoria, Problem Details e PostgreSQL são compartilhados.

O PostgreSQL é a fonte da pontuação consolidada. Na arquitetura aprovada para a Sprint 5, a API Redis consulta essa pontuação por uma identidade de leitura restrita; a Core não publica cada entrega ou coleta por HTTP para alimentar o ranking. A definição da consulta e das permissões dessa identidade pertence à integração com a Sprint 5. Os SQL da fixture são cópias versionadas: sua conferência com a revisão oficial necessária à implementação continua pendente.

## 2. Garantias verificadas na base atual

| Tema | Contrato observado |
| --- | --- |
| Identidade | Perfis `CITIZENS`, `ESTABLISHMENT`, `ADMIN`. `Caller.id` e `Caller.type` extraem a identidade autenticada; ser responsável por PEV é vínculo no banco, não perfil. |
| Auditoria | `AuditContext.user(id, reason)` requer transação ativa e define contexto local à transação. IDs de autor, validador e administrador não vêm do corpo HTTP. |
| PEV | `pev` tem exatamente um responsável, cidadão **ou** estabelecimento, endereço próprio `owner_kind=PEV` e unicidade por responsável, inclusive para registros rejeitados/inativos. Nasce `PENDING`; aprovação exige autor/data juntos. `is_pev` é sincronizado por trigger. |
| QR | O cadastro gera um token aleatório por titular em `user_qr_code`, com unicidade global entre cidadãos e estabelecimentos. O banco admite atualização e a propaga às especializações por FK; isso não implica rotação no fluxo aprovado para o MVP. |
| Entrega | `record_pev_delivery(UUID, UUID, UUID, UUID, NUMERIC, TIMESTAMPTZ, OUT UUID)` valida, na criação, responsável ativo de PEV aprovado, cidadão ativo, data não futura e volume positivo. No reenvio de chave existente, compara o INSERT original antes de verificar o estado atual do PEV. Proíbe beneficiário igual ao validador na criação, calcula `FLOOR(volume)` por entrega e reconstrói pontos internamente. |
| Correção | `correct_pev_delivery(UUID, INTEGER, UUID, record_status_t, NUMERIC, TIMESTAMPTZ, TEXT)` exige ADMIN ativo, justificativa e revisão esperada. Revisão antiga produz SQLSTATE `40001`; anulação mantém os dados físicos e zera o efeito vigente. |
| Permissões | `olius_api` pode consultar as tabelas de negócio, inserir PEV/endereço e atualizar colunas autorizadas; entrega e pontos são escritos pelas procedures. Não há DELETE físico para PEV ou entrega. |
| HTTP | A API já usa Bearer, `/api/v1`, Problem Details e `X-Request-Id`. `/api/v1/admin/**` exige ADMIN no filtro, mas a autorização do recurso ainda deve ocorrer no Service. |

## 3. Regras funcionais aprovadas

- **PEV:** transições permitidas `PENDING → APPROVED/REJECTED`, `REJECTED → PENDING`, `APPROVED → INACTIVE` e `INACTIVE → APPROVED`. `REJECTED → PENDING` reapresenta o mesmo PEV, pois a unicidade também abrange rejeitados. Não há transferência de responsável no MVP. O proprietário só edita o endereço próprio do PEV em `PENDING` ou `REJECTED`.
- **Localização:** consulta pública apenas de PEVs `APPROVED`, com endereço próprio do ponto e coordenadas disponíveis. Não divulgar dados pessoais adicionais do responsável. O contrato da consulta geográfica do mapa ainda precisa ser definido.
- **QR:** cada titular mantém o token único gerado no cadastro; leituras e entregas não geram nem substituem o token. Não há rotação pelo titular nem renovação periódica no MVP. Somente o responsável ativo por PEV aprovado pode identificar um cidadão ativo pelo QR no contexto de entrega, recebendo identificação mínima. O token de estabelecimento não identifica beneficiário B2C. Autoentrega é proibida. Uma eventual substituição excepcional do token exige contrato próprio.
- **Entrega:** `deliveryDate` é a data física obrigatória, não futura. Lançamento retroativo é permitido sem limite arbitrário de dias. Registra-se somente volume aceito, com pontos calculados por evento no banco.
- **Correção:** ADMIN pode anular sem editar volume/data. Uma entrega anulada pode ser restaurada a `RECORDED` com justificativa, revisão esperada e recálculo; na restauração, os valores informados devem cumprir as validações da procedure. O histórico e as revisões são preservados.

## 4. Operações REST disponíveis e capacidades pendentes

As rotas abaixo estão implementadas, exceto as linhas marcadas como **não implementada**. As regras aprovadas da seção anterior continuam válidas para as capacidades pendentes. Consultas públicas e identificação de beneficiário não expõem documento, contato ou endereço pessoal.

| Operação | Rota | Acesso e resposta |
| --- | --- | --- |
| Solicitar PEV | `POST /api/v1/pevs` | Cidadão/estabelecimento ativo; responsável do token. `201` com `Location` e PEV criado. |
| Consultar próprio PEV | `GET /api/v1/pevs/me` | Responsável ativo. `200` com o próprio ponto; `404` quando ausente. |
| Reapresentar PEV rejeitado | `POST /api/v1/pevs/me/resubmissions` | Titular ativo; sem corpo; `REJECTED → PENDING`, `200` com o mesmo PEV. |
| Alterar endereço próprio | `PATCH /api/v1/pevs/me/address` | Titular ativo; endereço completo; somente `PENDING` ou `REJECTED`; `200` com o próprio PEV atualizado. |
| Localizar PEVs | `GET /api/v1/pevs` | Público; somente `APPROVED`, com endereço e coordenadas disponíveis. Paginação 0/20, máximo 100; busca geográfica pendente. |
| Listar/detalhar PEVs para gestão | `GET /api/v1/admin/pevs` e `GET /api/v1/admin/pevs/{id}` | ADMIN; fila, estados e detalhe autorizado, com paginação na lista. |
| Mudar estado | `PATCH /api/v1/admin/pevs/{id}/status` | ADMIN ativo; transições administrativas permitidas. `200` com detalhe atualizado. |
| Identificar cidadão por QR | `POST /api/v1/qr/resolutions` | Responsável ativo por PEV `APPROVED`; token no corpo, identificação mínima do cidadão ativo. A leitura não altera o token nem registra entrega. |
| Registrar ou recuperar entrega | `POST /api/v1/pevs/{id}/deliveries` | Responsável autenticado do PEV; criação exige PEV aprovado e QR vigente. Recuperação por chave de entrega já confirmada segue a seção de idempotência. `201` na criação e `200` na recuperação, sob bloqueio transacional por chave interna. |
| Histórico próprio | `GET /api/v1/users/me/deliveries` | Cidadão ativo; entregas em que é beneficiário, com dados e revisão operacional vigentes, incluindo anuladas. `200` paginado, sem saldo. |
| Entregas do PEV | `GET /api/v1/pevs/{id}/deliveries` | Responsável ativo do PEV; ID e nome do beneficiário, sem documento, contato ou QR. `page=0`, `size=20` por padrão, máximo 100; ordem `createdAt DESC, id DESC`. |
| Detalhe da entrega | `GET /api/v1/pevs/{id}/deliveries/{deliveryId}` | Responsável ativo do PEV; consulta o recurso indicado pelo `Location` da criação. Entrega de outro PEV retorna `404`. |
| Listar/detalhar entregas para gestão | `GET /api/v1/admin/deliveries` e `GET /api/v1/admin/deliveries/{id}` | ADMIN; lista paginada e detalhe, incluindo estado e revisão vigentes. |
| Pontos próprios | `GET /api/v1/users/me/points` | Cidadão titular; saldo geral e movimentações vigentes de entregas, paginadas por `deliveryDate DESC, id DESC`. `page=0`, `size=20` por padrão, máximo 100. O recorte anual do ranking não compõe esta resposta. |
| Corrigir/anular/restaurar | `POST /api/v1/admin/deliveries/{id}/corrections` | ADMIN ativo; motivo e revisão esperada. `ANNULLED` preserva volume/data; `RECORDED` corrige ou restaura. `200` com detalhe e cálculo vigentes. |

### Formatos de entrada implementados

Campos desconhecidos são rejeitados pelo comportamento JSON atual. Os exemplos usam os campos implementados; substitua IDs, tokens e datas pelos valores da operação.

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

O token identifica o cidadão, mas não autoriza a operação. O responsável autenticado é `p_validator_id`; o cidadão identificado é `p_citizen_id`. A data física `deliveryDate` é obrigatória, não futura e pode ser retroativa sem limite arbitrário de dias. A API aceita volume decimal de até duas casas, maior que zero e no máximo `999.99`, sem arredondamento silencioso. O token de estabelecimento não é beneficiário B2C.

**Chave vinculada ao validador:** `idempotencyKey` é um UUID gerado pelo cliente para uma operação lógica e preservado nos reenvios. Antes de qualquer consulta ou chamada à procedure, a Core deriva deterministicamente um UUID interno a partir do par `(validatorId autenticado, idempotencyKey)`, com separação de domínio e SHA-256; define os bits de versão/variante do UUID e usa somente esse UUID interno em `delivery_pev.idempotency_key` e em `record_pev_delivery`. O algoritmo e a separação de domínio ficam fixos na implementação para preservar reenvios após reinícios e implantações. O ID do validador vem exclusivamente da sessão, nunca do corpo. Não registrar nem devolver a chave interna. A mesma chave enviada por outro validador gera outra chave interna, portanto não consulta nem aciona a ramificação de conflito da entrega alheia. A unicidade global da coluna continua válida para as chaves internas. Se houver entregas anteriores armazenadas com chave bruta, exigir migração ou compatibilidade explícita antes de ativar esse esquema; a PR atual inicia o registro B2C.

**Criação:** exige QR vigente que resolva para cidadão ativo e responsável ativo do PEV `APPROVED`. Autoentrega é proibida. O cliente preserva a chave, o PEV, o volume e a data física em tentativas após timeout.

**Recuperação de entrega já confirmada:** com a mesma chave e o mesmo validador autenticado, a Core recupera o cidadão do registro existente antes de exigir resolução do QR; `qrToken` pode ser omitido nesse reenvio. Se for enviado, seu valor é ignorado: até um QR válido de outro cidadão não altera o beneficiário, não cria entrega nem acrescenta pontos. A recuperação independe da validade atual de um token eventualmente substituído por mecanismo excepcional futuro. A Core chama `record_pev_delivery` com o cidadão recuperado, o validador autenticado e o PEV, volume e `deliveryDate` enviados originalmente. A procedure compara esses dados canônicos com o snapshot do INSERT original preservado em auditoria, inclusive após correção administrativa. Ela não recebe nem compara o token bruto. A leitura preliminar usa a chave interna derivada **e o validador autenticado**; uma chave enviada por terceiro não revela a existência, o cidadão ou o payload da operação alheia. Não verificar antecipadamente o estado atual do PEV nesse caminho: um PEV hoje inativo não invalida a recuperação de entrega confirmada quando estava aprovado. A sessão e a conta do validador ainda precisam estar válidas e ativas. Essa exceção vale apenas para recuperação, nunca para criar entrega nova com token inválido ou em PEV não aprovado.

**Concorrência e resposta:** na mesma transação, após validar a sessão e a conta ativa, a Core adquire `pg_advisory_xact_lock` sobre a chave interna, com a mesma função de hash usada pela procedure, e só então consulta a entrega por chave interna e validador. Registro ausente segue a criação; registro existente segue a recuperação. A Core chama a procedure em ambos os caminhos e usa o resultado da consulta sob bloqueio para responder `201` ou `200`, pois a procedure retorna apenas o ID. Uma segunda tentativa da mesma operação espera a primeira transação terminar e então observa a entrega confirmada antes de avaliar o QR. Uma releitura dentro da mesma transação após falha do QR não encontra confirmação concorrente normal, pois o bloqueio ainda está ativo. Não expor diferenças entre chave desconhecida e chave de outro validador por consultas globais ou tratamento de `42501`.

Com a mesma chave e o mesmo validador, PEV, volume ou `deliveryDate` diferentes geram conflito de idempotência (`22000`, a traduzir para `409` no contexto do registro). A procedure rejeita cidadão diferente com `42501` quando recebe outro `p_citizen_id`; a rota de reenvio sempre usa o cidadão persistido e não resolve o `qrToken` recebido. A aplicação não aceita um `validatorId` fornecido pelo cliente.

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

### Formatos de saída implementados

- **PEV:** `id`, `status`, `address`, `createdAt`; `approvedAt` e dados de responsável somente conforme contexto. A localização pública mostra endereço próprio e coordenadas disponíveis apenas de PEVs aprovados, sem dados pessoais adicionais.
- **Entrega (`DeliveryContracts.View`, resposta do POST e do detalhe do PEV):** `id`, `pevId`, `citizenId`, `oilVolumeLiters`, `deliveryDate`, `createdAt`, `pointsEarned`, `recordStatus`, `revision`, `balanceAfter`. A listagem do PEV usa `PevItem`, com `citizenId` e `citizenName`, sem `balanceAfter`; o detalhe administrativo possui representação própria.
- **Pontos:** `balance` geral e movimentações atuais, sem somar revisões antigas. Pontuação anual de ranking não é sinônimo de saldo geral.
- **Paginação:** listas usam `items`, `page`, `size` e `totalElements`; pontos usa `movements` e acrescenta `balance`. Padrões 0/20, máximo 100. PEVs ordenam por `createdAt ASC, id ASC`; entregas por `createdAt DESC, id DESC`; pontos por `deliveryDate DESC, id DESC`.

### Primeira entrega: solicitação e consultas de PEV

Nesta etapa, `POST /api/v1/pevs` recebe apenas `address`, cria um endereço `owner_kind=PEV` e um PEV `PENDING` na mesma transação, deriva o responsável do Bearer e retorna `201` com `Location: /api/v1/pevs/me`. `GET /api/v1/pevs/me` devolve `id`, `status`, `createdAt`, `approvedAt` e endereço do próprio responsável; ausência de PEV retorna `404`. Ambos exigem conta ativa `CITIZENS` ou `ESTABLISHMENT`.

`GET /api/v1/pevs` é público e devolve somente PEVs `APPROVED`, com `id` e endereço próprio do ponto, incluindo coordenadas quando cadastradas. A resposta tem `items`, `page`, `size` e `totalElements`; `page` começa em zero, `size` padrão é 20 e máximo é 100. Combinações cujo deslocamento `page × size` excede `Integer.MAX_VALUE` retornam `400` antes da consulta. A ordem é `createdAt ASC, id ASC`. Filtros e consulta geográfica permanecem para integração posterior.

### Gestão administrativa: listagem inicial de PEVs

`GET /api/v1/admin/pevs` exige ADMIN ativo e lista PEVs em todos os estados. Aceita filtros opcionais `status` (`PENDING`, `APPROVED`, `REJECTED`, `INACTIVE`) e `ownerType` (`CITIZENS`, `ESTABLISHMENT`), inclusive combinados. A resposta contém `items`, `page`, `size` e `totalElements`; cada item traz `id`, `status`, `createdAt`, `updatedAt`, `approvedAt`, `ownerId`, `ownerType`, `ownerName` e endereço **do ponto**. Não inclui CPF, CNPJ, e-mail, QR nem endereço pessoal do responsável.

`page` começa em zero, `size` padrão é 20 e máximo é 100; a ordem fixa é `createdAt ASC, id ASC`. Um deslocamento `page × size` acima de `Integer.MAX_VALUE`, parâmetros inválidos ou `ownerType=ADMIN` retornam `400`. Página sem itens retorna `200` com `items` vazio e o total correspondente aos filtros.

### Gestão administrativa: detalhe de PEV

`GET /api/v1/admin/pevs/{id}` exige ADMIN ativo e retorna `id`, `status`, `createdAt`, `updatedAt`, `approvedAt`, `approvedBy`, `ownerId`, `ownerType`, `ownerName` e endereço próprio do ponto. `approvedAt` e `approvedBy` são nulos antes da aprovação. O detalhe não inclui CPF, CNPJ, e-mail, QR ou endereço pessoal do responsável. Um ID de PEV inexistente retorna `404`.

### Gestão administrativa: decisão e mudança de estado

`PATCH /api/v1/admin/pevs/{id}/status` exige ADMIN ativo e recebe apenas `{"status":"APPROVED"}` (ou outro estado de destino). Permite `PENDING → APPROVED/REJECTED`, `APPROVED → INACTIVE` e `INACTIVE → APPROVED`; outras transições, inclusive repetição do mesmo estado e reapresentação de `REJECTED`, retornam `409`. ID inexistente retorna `404`. Sucesso retorna `200` com o detalhe atualizado do PEV.

A primeira aprovação define `approvedAt` pelo relógio do PostgreSQL e `approvedBy` pelo ADMIN autenticado. Inativação e reativação preservam esses campos como registro da aprovação original; a auditoria da atualização identifica o ADMIN que executou cada transição. A linha do PEV é bloqueada na transação antes de validar o estado, para serializar decisões concorrentes. A API não atualiza `establishment.is_pev` diretamente: a trigger do banco sincroniza esse campo na mesma transação. A reapresentação `REJECTED → PENDING` pertence ao titular e é executada por POST /api/v1/pevs/me/resubmissions.

### Gestão administrativa de entregas implementada

`GET /api/v1/admin/deliveries` exige ADMIN ativo e retorna `items`, `page`, `size` e `totalElements`, incluindo entregas registradas e anuladas. Padrões: `page=0`, `size=20`, máximo 100; ordem `createdAt DESC, id DESC`. Parâmetros vazios, inválidos ou deslocamento acima de `Integer.MAX_VALUE` retornam `400`. Página além do total retorna `200`, lista vazia e total preservado.

`GET /api/v1/admin/deliveries/{id}` retorna `delivery`, `balanceAfter` e `calculatedAt` do cálculo vigente; ausência retorna `404`. `delivery` inclui identificação mínima do cidadão, PEV, validador, volume, datas, pontos, estado e revisão. Não inclui QR, documento ou contato.

`POST /api/v1/admin/deliveries/{id}/corrections` exige ADMIN ativo e retorna `200` nesse mesmo formato após a alteração. Aceita `RECORDED → RECORDED`, `RECORDED → ANNULLED` e `ANNULLED → RECORDED`. Exige revisão positiva e justificativa não vazia de até 1000 caracteres. Para anular, volume e data devem estar ausentes ou nulos; seus valores anteriores são preservados. Para corrigir ou restaurar, ambos são obrigatórios: volume entre `0.01` e `999.99`, com até duas casas decimais, e data não futura nem posterior à criação original.

Revisão antiga retorna `409 CONCURRENT_MODIFICATION`; anular novamente uma entrega já anulada com revisão atual retorna `409 BUSINESS_STATE_CONFLICT`. Data posterior à criação original retorna `422 BUSINESS_RULE_VIOLATION`. Campos inválidos retornam `400`. A alteração, auditoria e reconstrução dos pontos ocorrem na mesma transação, sem repetição automática. Após timeout, consultar o detalhe antes de decidir por novo envio. A resposta mostra os efeitos vigentes; não representa o histórico completo de auditoria.

## 5. Autorização e transações

### Histórico próprio de entregas implementado

Task `86e3xqabw`. `GET /api/v1/users/me/deliveries` está implementado e documentado
no OpenAPI e no guia Mobile. Esta seção descreve a representação disponível.

Somente cidadão ativo consulta suas entregas como beneficiário. A identidade vem
da sessão e filtra `delivery_pev.citizen_id`; não aceitar ID de cidadão fornecido
pelo cliente. Estabelecimento e ADMIN recebem `403` nesta rota de titularidade.
Responsável cidadão por PEV vê aqui suas entregas realizadas, não as recebidas no ponto.

Resposta `200`: `items`, `page`, `size`, `totalElements`. Cada item contém `id`,
`pevId`, `oilVolumeLiters`, `deliveryDate`, `createdAt`, `pointsEarned`,
`recordStatus` e `revision` operacional. Incluir entregas `RECORDED` e `ANNULLED`,
com dados vigentes após correção/restauração; não listar snapshots antigos nem
expor QR, chave de reenvio, identidade do validador ou dados pessoais do responsável.
Não incluir saldo: `/users/me/points` continua responsável pelo saldo e pelas
movimentações de cálculo. O histórico não é uma trilha completa de auditoria.

Paginação segue o padrão existente: `page=0`, `size=20`, máximo 100; ordem
`deliveryDate DESC, id DESC`. Parâmetros vazios, inválidos ou deslocamento acima
de `Integer.MAX_VALUE` retornam `400`. Conta/sessão inválida retorna `401`.
Sem entregas, retornar página vazia; página além do total preserva `totalElements`.
Total e itens devem usar a mesma visão transacional (`REPEATABLE_READ`).
Cada requisição tem sua própria visão; correções da data entre páginas podem
deslocar itens, portanto o aplicativo deve deduplicar IDs ao acumular páginas.

Implementação somente de leitura, usando tabelas e permissões existentes, sem
migração. Testar isolamento entre cidadãos, distinção entre beneficiário e
validador, paginação/ordenação, estados e revisões vigentes, autenticação e perfis.

### Contrato da manutenção do próprio PEV — implementação nesta task

Task `86e3xq9ec`. As rotas desta seção estão implementadas e documentadas no OpenAPI.

| Operação | Entrada | Sucesso |
| --- | --- | --- |
| `PATCH /api/v1/pevs/me/address` | `{"address":{...}}`, com `IdentityRequests.AddressInput` completo. | `200` com `PevResponses.OwnPev` atualizado. |
| `POST /api/v1/pevs/me/resubmissions` | Sem corpo; identidade e ponto vêm da sessão. | `200` com o mesmo PEV em `PENDING`. |

Ambas exigem conta ativa `CITIZENS` ou `ESTABLISHMENT`. O titular é derivado do
Bearer; não recebem ID de PEV, responsável, aprovação ou estado no corpo.
ADMIN não executa essas ações em nome do proprietário.

A edição aceita somente `PENDING` ou `REJECTED`, sem alterar o estado. Embora a
rota use PATCH, o objeto `address` representa a substituição completa dos campos
editáveis: campos postais obrigatórios devem ser enviados; complemento e coordenadas
podem ser nulos. Campos opcionais omitidos serão nulos, não preservados implicitamente.
Reutilizar as validações do cadastro: UF com duas letras maiúsculas, CEP com oito
dígitos, limites textuais existentes e faixas de latitude/longitude. Não introduzir
novos campos ou regras geográficas. Preservar o ID do endereço, `owner_kind=PEV`,
o vínculo `address_id` e o endereço pessoal/comercial do responsável.

A reapresentação permite somente `REJECTED → PENDING`; preserva ID, responsável,
endereço e data de criação, sem criar registros substitutos. Editar o endereço e
reapresentar são ações separadas: a primeira não solicita nova análise automaticamente.
Repetir a reapresentação quando o PEV já estiver `PENDING` retorna conflito.

Cada escrita usa transação, localiza exclusivamente o ponto do titular e bloqueia
a linha do PEV antes de conferir o estado. O bloqueio deve coordenar com a decisão
administrativa existente, evitando editar um ponto que acaba de ser aprovado ou
reapresentar duas vezes. Configurar `AuditContext.user` antes da alteração, com
motivo definido pelo servidor. Auditoria e alteração devem confirmar ou reverter juntas.
Após escrita, devolver a representação atualizada sem depender de entidade em cache.

Erros: `400` para entrada inválida ou campos desconhecidos no endereço; `401` para
sessão inválida/conta inativa no HTTP; `403` para perfil não autorizado; `404` quando
o titular não possui PEV; `409 BUSINESS_STATE_CONFLICT` para estado incompatível.
Não disponibilizar consulta ou alteração de ponto alheio por parâmetro fornecido pelo cliente.

As permissões necessárias já constam em `access_control/01_roles.sql` da fixture:
UPDATE dos campos editáveis de `addresses` e de `pev.status`. Não estão previstas
mudanças de estrutura ou grants nesta task. Conferir o banco oficial antes da implantação.
O aceite deverá cobrir os dois perfis, estados permitidos/bloqueados, auditoria,
preservação de IDs e endereços independentes, decisões simultâneas e a jornada
solicitação → rejeição → edição → reapresentação → aprovação por HTTP.

1. Toda escrita abre transação, deriva a identidade do Bearer, verifica perfil e titularidade e chama `AuditContext.user` antes da alteração.
2. Criação de PEV persiste endereço PEV e PEV na mesma transação. A unicidade do banco fecha corridas de duas solicitações.
3. Gestão de PEV aplica apenas as transições aprovadas na seção 3. Reapresentação reutiliza o PEV rejeitado; não há troca de responsável. Alteração de endereço pelo proprietário exige `PENDING` ou `REJECTED`. A API nunca escreve `establishment.is_pev`.
4. Registro usa `CALL record_pev_delivery` com OUT e a conexão transacional gerenciada. Na criação, resolve QR vigente e verifica o PEV aprovado. Na recuperação, encontra a operação por chave e validador autenticado antes da resolução de QR e não bloqueia pelo estado atual do PEV. A procedure confere o payload original. A API não grava entrega, pontos ou saldo por `save` genérico. Após procedure/trigger, relê projeções necessárias para evitar entidade JPA desatualizada.
5. Correção usa `CALL correct_pev_delivery` com `expectedRevision` fornecida pelo cliente. Em `40001`, retorna conflito para releitura; não troca a revisão nem repete automaticamente.
6. Identificação por QR apenas consulta o token único gerado no cadastro; não o altera nem grava o segredo em logs ou em registros de entrega. Uma eventual substituição excepcional fica fora do MVP e depende de contrato próprio.

## 6. Erros e testes de contrato

Usar o `GlobalExceptionHandler`, `ApiProblemFactory`, `PersistenceExceptionTranslator` e os handlers de segurança existentes. Os códigos públicos atuais incluem `PEV_ALREADY_EXISTS`, `BUSINESS_STATE_CONFLICT`, `BUSINESS_RULE_VIOLATION` e `CONCURRENT_MODIFICATION`. Erros previstos: `400` entrada inválida, `401` sem autenticação, `403` sem permissão, `404` recurso consultável ausente, `409` duplicidade/estado/revisão. A tradução de SQLSTATE `22023`, `22000`, `42501`, `55000` e `P0002` deve considerar a operação; `42501` também pode ser falha de privilégio técnico. Não inferir regra por texto SQL nem expor token, documento ou parâmetros internos.

Os testes das próximas PRs devem usar `OfficialPostgres`, a role limitada e requisições HTTP com JWT real. Cobrir PEV cidadão/estabelecimento, duplicidade concorrente, reapresentação, edição de endereço nos estados permitidos, transições e trigger; localização pública sem dados pessoais; identificação pelo token único sem alteração após leituras repetidas, recusa de token desconhecido ou de estabelecimento no B2C; entrega parcial, frações, volume inválido, data futura, titularidade e idempotência concorrente; recuperação sem exigir nova leitura do QR e após inativação do PEV, sem expor chave alheia; correção, anulação, restauração, revisão antiga, auditoria e saldos. A fixture não instala `06_ctes.sql` nem massa `data_load`.

## 7. Alinhamentos de integração ainda necessários

| Decisão | Impacta |
| --- | --- |
| Representações das capacidades ainda sem endpoint e futuros filtros adicionais. As listas implementadas seguem os formatos e limites da seção 4. | REST e consultas futuras |
| Contrato da consulta geográfica do mapa. | Localização pública |
| Forma de apresentar ao Mobile a identidade e situação do PEV do titular, sem confundir `is_pev` com permissão para solicitar coleta B2B. | Mobile e PEV |
| Conferência da fixture SQL com a revisão oficial do banco necessária à implementação. | Testes e integração SQL |
| Apresentação visual do QR no aplicativo e eventual substituição excepcional. O perfil já retorna `qrToken` e a identificação retorna ID e nome. | QR e Mobile |
| Detalhes administrativos de representação e histórico de revisões sem SELECT direto nos logs pela role da API. | Gestão e correção |
| Contrato de leitura restrita da pontuação consolidada pelo Redis, inclusive recorte anual e atualizações após correção, sem publicação HTTP por operação pela Core. | Sprint 5 |

**Critério da integração:** validar e documentar a jornada disponível, mantendo explícitas as capacidades ainda não implementadas. Decisões que alterem contratos consumidos por Mobile/Frontend, estrutura ou permissões do banco, ou regras de negócio devem ser alinhadas com os respectivos responsáveis. Escolhas técnicas internas seguem os contratos acima.
