# Guia de consumo B2C pelo Mobile

Este guia descreve as rotas disponíveis na Core API. Consulte também o
[inventário da jornada](jornada-b2c.md), o [contrato B2C](contratos-b2c.md) e
o schema publicado em `/v3/api-docs` no ambiente de integração.
IDs e datas dos exemplos são ilustrativos; use os valores retornados pelo servidor.

## 1. Cadastro e sessão

Cadastre o cidadão em `POST /api/v1/auth/registrations/citizens`:

```json
{"name":"Maria","email":"maria@example.com","password":"SenhaExemplo1!","cpf":"12345678901"}
```

O cadastro retorna `201` com `id` e `userType`. Depois, autentique em
`POST /api/v1/auth/mobile/login` com `email` e `password`.
A resposta contém `accessToken`, `tokenType`, `expiresIn`, `expiresAt`,
`refreshToken` e `refreshExpiresAt`.

Envie `Authorization: Bearer <accessToken>` nas chamadas autenticadas.
Armazene credenciais no armazenamento seguro da plataforma e não registre tokens
em logs. A consulta pública de PEVs dispensa autenticação.

Para renovar, envie `{"refreshToken":"<valor-vigente>"}` a
`POST /api/v1/auth/mobile/refresh`. Substitua os tokens pelos novos valores da resposta;
o refresh anterior não deve ser reutilizado. Serialize renovações no aplicativo para
evitar duas chamadas usando o mesmo refresh. Se a renovação falhar, solicite novo login.
`POST /api/v1/auth/mobile/logout` encerra a sessão autenticada e retorna `204`.

## 2. Mostrar o QR do cidadão

`GET /api/v1/users/me` retorna o perfil com `id`, `userType` e `qrToken`, entre outros
campos. O aplicativo renderiza um QR com o conteúdo exato de `qrToken`.
Não codifique o ID, um JWT ou o JSON inteiro do perfil como substituto do token.

O token é único por titular e permanece igual após leitura ou entrega.
Não existe ação de rotação pelo titular nem renovação semanal no MVP.
O QR identifica o cidadão; ele não autentica quem realiza o atendimento.

## 3. Descobrir e solicitar um PEV

`GET /api/v1/pevs?page=0&size=20` lista os pontos aprovados. A resposta contém
`items`, `page`, `size` e `totalElements`. Cada item possui `id` e `address`,
incluindo `latitude` e `longitude` quando disponíveis. Não existe filtro de
distância ou área implementado; não enviar parâmetros geográficos como se fossem suportados.

Para conhecer o ponto do titular autenticado, consulte `GET /api/v1/pevs/me`.
`404` significa que ainda não há PEV para esse titular. Uma resposta existente contém
`id`, `status`, `createdAt`, `approvedAt` e `address`.

Solicite em `POST /api/v1/pevs`:

```json
{
  "address": {
    "state":"SP", "city":"São Paulo", "neighborhood":"Centro",
    "street":"Rua Exemplo", "number":"10", "cep":"01001000",
    "complement":null, "latitude":-23.5505, "longitude":-46.6333
  }
}
```

O endereço é do ponto. Não enviar responsável, estado de aprovação ou autor da aprovação.
Sucesso retorna `201`, `Location: /api/v1/pevs/me` e o PEV `PENDING`.
Solicitação duplicada retorna `409`; consulte o próprio PEV antes de oferecer novo envio.

| Situação | Apresentação e ações disponíveis |
| --- | --- |
| `PENDING` | Solicitação aguardando decisão; identificação e novas entregas bloqueadas. |
| `APPROVED` | Responsável pode identificar cidadãos e registrar entregas. |
| `REJECTED` | Solicitação rejeitada; reapresentação e edição de endereço ainda não possuem rota. |
| `INACTIVE` | Identificação e novas entregas bloqueadas; entregas anteriores continuam consultáveis pelo responsável ativo. |

A aprovação pertence ao ADMIN, por `PATCH /api/v1/admin/pevs/{id}/status`.
O aplicativo consulta novamente `/pevs/me` para obter a situação atual.
Ser responsável por PEV não concede acesso às operações de coleta B2B.

## 4. Identificar o cidadão no atendimento

O responsável autenticado pelo PEV aprovado envia a leitura em
`POST /api/v1/qr/resolutions`:

```json
{"qrToken":"<conteúdo-exato-do-qr>"}
```

Sucesso retorna `200` com `id` e `name` do cidadão ativo. Use essa identificação
para confirmar o beneficiário na tela antes de registrar a entrega.
A identificação não registra entrega nem concede pontos. Autoentrega é proibida;
QR de estabelecimento não identifica beneficiário B2C.

## 5. Registrar e recuperar uma entrega

O responsável informa volume aceito e data física e envia
`POST /api/v1/pevs/{pevId}/deliveries`:

```json
{
  "idempotencyKey":"579e649c-6a60-4eb6-9f34-1b44ed8fbacd",
  "qrToken":"<conteúdo-exato-do-qr>",
  "oilVolumeLiters":3.70,
  "deliveryDate":"2026-10-18T14:00:00Z"
}
```

Gere um UUID por operação lógica e preserve chave, PEV, volume e data antes do envio.
Volume permitido: `0.01` a `999.99`, até duas casas decimais, como número JSON.
Envie a data com fuso explícito; ela não pode ser futura e pode ser retroativa sem
limite arbitrário de dias. Não arredonde silenciosamente o volume digitado.

`201` confirma criação e inclui `Location` consultável pelo responsável.
`200` confirma recuperação da mesma operação; ambos devem mostrar sucesso na tela.
A resposta contém `id`, `pevId`, `citizenId`, volume, datas, `pointsEarned`,
`recordStatus`, `revision` e `balanceAfter`.
Os pontos vêm do banco: uma entrega de `3.70` litros rende 3 pontos; `0.75` rende zero.
Não calcule o saldo definitivo no aplicativo.

Após timeout ou perda da resposta, reenvie a mesma operação com a mesma chave.
Nunca gere nova chave apenas porque a resposta não chegou. Na recuperação confirmada,
`qrToken` pode ser omitido e, se enviado, é ignorado; o cidadão original é preservado.
Se o primeiro envio não tiver confirmado, criar a entrega ainda exige QR válido e PEV aprovado.
Mudar PEV, volume ou data não representa um reenvio equivalente.
Recuperar uma entrega já confirmada continua possível com PEV inativo, desde que
a sessão e a conta do responsável estejam válidas e ativas.

## 6. Consultar entregas e pontos

O responsável consulta `GET /api/v1/pevs/{pevId}/deliveries` e segue para
`GET /api/v1/pevs/{pevId}/deliveries/{deliveryId}` para o detalhe.
A lista usa `items` e ordem `createdAt DESC, id DESC`; inclui identificação mínima
do cidadão, volume, datas, pontos, situação e revisão.

O cidadão consulta `GET /api/v1/users/me/points`. Exemplo simplificado:

```json
{
  "balance":3,
  "movements":[{"deliveryId":"<id-da-entrega>","points":3,"deliveryDate":"2026-10-18T14:00:00Z","revision":1}],
  "page":0,"size":20,"totalElements":1
}
```

`balance` é o saldo geral atual. `movements` contém cálculos vigentes de entregas,
ordenados por `deliveryDate DESC, id DESC`; não representa histórico completo de entregas
ou de auditoria. Uma anulação pode aparecer como movimentação vigente com zero pontos.
Após correção, anulação ou restauração administrativa, recarregue as consultas.
A `revision` de uma movimentação é de cálculo; não a use para corrigir uma entrega.
Correções exigem a revisão operacional do detalhe administrativo e acesso ADMIN.

Nas consultas de entregas do PEV e de pontos, `page=0` e `size=20` são os padrões;
máximo 100 itens por página.
Para a próxima página, incremente `page`, conservando `size`. Pare quando
`(page + 1) × size >= totalElements`. Página além do total retorna lista vazia.
Parâmetros vazios, negativos, tamanho inválido ou deslocamento acima de
`Integer.MAX_VALUE` retornam `400`. Cada requisição observa seu próprio estado;
alterações entre páginas podem deslocar itens, então deduplique por ID na interface.

## 7. Tratar erros e operações ainda indisponíveis

As falhas usam Problem Details com `status`, `code`, `detail` e `traceId`;
erros de validação podem incluir `errors`. Trate pelo status/código, não pelo texto.

| HTTP | Ação do aplicativo |
| --- | --- |
| `400` | Corrigir entrada ou parâmetros; conferir campos em `errors` quando presentes. |
| `401` | Tentar a renovação de sessão conforme a política acima; se falhar, solicitar login. |
| `403` | Reconsultar perfil/situação do PEV e informar falta de permissão; não repetir automaticamente. |
| `404` | Tratar conforme a operação: próprio PEV ausente, QR não encontrado ou recurso indisponível. |
| `409` | Conferir `code` e reler o estado. Não trocar a chave de uma entrega incerta para contornar conflito. |
| `422` | Exibir incompatibilidade da operação e permitir revisão dos dados. |
| `429` | Reduzir frequência e permitir nova tentativa posteriormente. |
| `500` / `503` | Informar falha temporária; preservar os dados e a chave de entrega para recuperação. |

Não apresentar reapresentação de PEV, edição do endereço ou
`GET /api/v1/users/me/deliveries` como operações disponíveis.
Ranking pertence à integração com a API Redis; a Core não publica cada entrega por HTTP.
