# Integração da jornada B2C

## Escopo e estado inicial

Task: `86e3p3mt8` — integrar e documentar jornada B2C.

Este documento distingue o comportamento disponível na Core API das capacidades
previstas em [contratos-b2c.md](contratos-b2c.md) que ainda precisam de implementação
ou alinhamento. O inventário resulta da leitura dos controllers, serviços, DTOs e
testes atuais; não constitui evidência de execução dos testes desta branch.

A integração usa a Core existente em `/api/v1`, suas sessões e o PostgreSQL.
O Mobile consome os contratos HTTP; não chama procedures nem acessa o banco.

## Capacidades disponíveis

| Passo | Rota | Consumidor e comportamento atual |
| --- | --- | --- |
| Cadastrar cidadão | `POST /api/v1/auth/registrations/citizens` | Cria identidade de cidadão; o token do QR é gerado no cadastro. |
| Autenticar | `POST /api/v1/auth/mobile/login` | Retorna credenciais da sessão; exige conta ativa. |
| Obter próprio QR | `GET /api/v1/users/me` | Perfil autenticado contém `qrToken`; apresentação visual é responsabilidade do aplicativo. |
| Solicitar PEV | `POST /api/v1/pevs` | Cidadão ou estabelecimento ativo solicita com endereço próprio do ponto; retorna `201`, PEV `PENDING` e `Location`. |
| Consultar próprio PEV | `GET /api/v1/pevs/me` | Retorna identidade, situação e endereço do ponto; ausência retorna `404`. |
| Editar endereço próprio | `PATCH /api/v1/pevs/me/address` | Titular ativo; endereço completo; somente `PENDING` ou `REJECTED`; preserva IDs e estado. |
| Reapresentar ponto rejeitado | `POST /api/v1/pevs/me/resubmissions` | Titular ativo; sem corpo; mesmo PEV passa de `REJECTED` para `PENDING`. |
| Localizar pontos aprovados | `GET /api/v1/pevs` | Consulta pública paginada com endereço e coordenadas disponíveis; somente `APPROVED`. |
| Consultar PEVs para gestão | `GET /api/v1/admin/pevs` e `GET /api/v1/admin/pevs/{id}` | ADMIN ativo; lista e detalhe administrativos. |
| Decidir situação do PEV | `PATCH /api/v1/admin/pevs/{id}/status` | ADMIN ativo aprova, rejeita, inativa ou reativa nas transições permitidas. |
| Identificar beneficiário | `POST /api/v1/qr/resolutions` | Responsável ativo por PEV aprovado identifica outro cidadão ativo; retorna ID e nome. |
| Registrar ou recuperar entrega | `POST /api/v1/pevs/{pevId}/deliveries` | Responsável registra volume aceito e data física; `201` cria, `200` recupera operação confirmada. |
| Consultar entregas recebidas | `GET /api/v1/pevs/{pevId}/deliveries` e `GET /api/v1/pevs/{pevId}/deliveries/{deliveryId}` | Responsável ativo consulta somente entregas do próprio PEV. |
| Consultar pontos | `GET /api/v1/users/me/points` | Cidadão consulta saldo geral e movimentações vigentes de entregas. |
| Consultar histórico próprio | `GET /api/v1/users/me/deliveries` | Cidadão ativo; entregas realizadas, registradas e anuladas, com dados vigentes e paginação por data física e ID decrescentes. Sem saldo ou snapshots antigos. |
| Consultar entregas para gestão | `GET /api/v1/admin/deliveries` e `GET /api/v1/admin/deliveries/{id}` | ADMIN ativo consulta estado, revisão e efeitos vigentes. |
| Corrigir, anular ou restaurar | `POST /api/v1/admin/deliveries/{id}/corrections` | ADMIN ativo informa justificativa e revisão esperada; banco audita e recalcula na mesma transação. |

## Regras a preservar na integração

- Cidadão beneficiário, responsável pelo PEV e administrador são atores distintos
  no fluxo de aprovação e entrega. Autoentrega é proibida.
- O mesmo QR é utilizado nas leituras. Identificação não gera token, entrega ou pontos.
- Tanto cidadão quanto estabelecimento podem ser responsáveis por PEV; somente
  cidadão é beneficiário da entrega B2C. O atendimento B2B tem contrato próprio.
- A identidade do responsável e do ADMIN vem da sessão, nunca de campos do cliente.
- O aplicativo consulta `/pevs/me` para conhecer a situação do próprio ponto.
  Ser responsável por PEV não concede permissão de coleta B2B.
- A criação de entrega exige PEV aprovado. Recuperar uma operação confirmada
  dispensa nova resolução do QR e aprovação atual do ponto, mantendo sessão e conta válidas.
- Reenvios preservam chave UUID, PEV, volume e data originais. O QR eventualmente
  enviado na recuperação é ignorado; o cidadão original é reutilizado.
- Volume aceito: `0.01` a `999.99` litros, até duas casas. A data física pode ser
  retroativa, sem limite arbitrário de dias, e não pode ser futura.
- Saldo e movimentações refletem cálculos vigentes; revisões antigas não são somadas.
  A revisão da movimentação é de cálculo e não deve ser usada como `expectedRevision`
  operacional da entrega. Para corrigir, consultar o detalhe administrativo.
- Correção exige revisão atual; conflito requer releitura, sem atualização automática
  da revisão para reenviar a alteração. Anulação preserva volume e data.

## Lacunas confirmadas

| Capacidade prevista | Estado no código | Consequência para o consumidor |
| --- | --- | --- |
| Busca geográfica do mapa | A listagem pública não possui contrato de consulta por distância ou área. | Coordenadas disponíveis podem ser exibidas; filtros geográficos dependem de alinhamento. |

Essas lacunas não devem ser ocultadas por documentação nem por endpoints fictícios.
Sua implementação precisa de etapas próprias e conciliação das representações com
Mobile/Frontend. A conclusão dos oito commits de integração valida o fluxo disponível;
não significa conclusão de todas as capacidades previstas no contrato B2C.

## Cobertura existente e objetivo dos próximos testes

`PevIntegrationTest` cobre solicitação, identificação, entregas, reenvios, paginação,
pontos e gestão de entregas. Parte dos cenários aprova o PEV diretamente na fixture.
`PevAdministrationIntegrationTest` cobre listagem e detalhe administrativos;
`PevStatusIntegrationTest` cobre decisões, autorização e alterações simultâneas.
Os testes de correção também incluem validações de DTO, tradução de erros e falha
da procedure. A presença desses testes não garante execução bem-sucedida nesta branch.

Os cenários integrados desta branch conectam cadastro, perfil/QR, solicitação,
aprovação HTTP, identificação, entrega e pontos com JWT real e `OfficialPostgres`.
Também conectam correção/anulação/restauração às consultas do cidadão e do
responsável. O [roteiro de validação](validacao-jornada-b2c.md) descreve o aceite
manual e o comando de verificação automatizada.

## Dependências e limites

- Mobile: apresentação do QR, estados de tela, paginação e preservação da chave de
  reenvio precisam seguir as respostas reais do [guia de consumo](mobile-jornada-b2c.md).
- Banco: conferir a fixture com a revisão oficial antes da implantação. Não há
  migração ou criação de índice nesta etapa.
- Ranking: a API Redis lê a pontuação consolidada do PostgreSQL por identidade
  restrita. A Core não publica cada entrega via HTTP para atualizar ranking.
- Permissões e contrato de leitura do Redis, busca geográfica e substituição
  excepcional de QR dependem de alinhamento com os respectivos responsáveis.
- Certificados e consultas ambientais existentes têm contratos próprios; sua
  presença na Core não implica disponibilidade dessas funcionalidades para cidadão.
