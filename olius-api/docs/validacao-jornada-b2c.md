# Roteiro de validação da jornada B2C

Use o [guia do Mobile](mobile-jornada-b2c.md) para os payloads e o
[contrato](contratos-b2c.md) para regras e respostas. Execute em ambiente de
integração com a revisão oficial do PostgreSQL e uma conta ADMIN provisionada
pelo fluxo autorizado. Não use dados pessoais reais nos cenários de validação.

## Preparação

- Identificar URL e versão implantada da Core e consultar `/v3/api-docs`.
- Separar três sessões: cidadão beneficiário, responsável pelo PEV e ADMIN.
- Utilizar contas novas para conferir saldos sem interferência de entregas anteriores.
- Repetir a jornada do responsável com `CITIZENS` e `ESTABLISHMENT`.
- Registrar IDs, estados, volumes, datas e resultados HTTP. Não incluir tokens,
  senhas, QR ou dados pessoais nas evidências compartilhadas.

## Sequência e resultados esperados

| Passo | Ação | Resultado esperado |
| --- | --- | --- |
| 1 | Cadastrar cidadão e realizar login Mobile. | `201` no cadastro; `200` no login com tokens da sessão. |
| 2 | Consultar `/users/me` e `/users/me/points` com a sessão do cidadão. | QR disponível no perfil; saldo zero e nenhuma movimentação para conta nova. |
| 3 | Responsável solicitar PEV e consultar `/pevs/me`. | `201`, `Location` consultável e estado `PENDING`; endereço próprio do ponto. |
| 4 | Tentar identificar QR e registrar entrega com PEV pendente. | `403`; nenhuma entrega criada. |
| 5 | ADMIN aprovar PEV pela rota de status; responsável consultar próprio PEV. | `200`, estado `APPROVED`; aprovação com autor e data. |
| 6 | Consultar lista pública de PEVs. | Ponto aprovado disponível, endereço e coordenadas cadastradas, sem dados pessoais do responsável. |
| 7 | Responsável identificar QR do outro cidadão. | `200`, somente identificação mínima; nenhum ponto ou entrega criado pela identificação. |
| 8 | Registrar `3.70` litros com UUID novo e data física passada. | `201`, 3 pontos, revisão operacional 1 e `Location` consultável. |
| 9 | Consultar detalhe e lista do PEV e pontos do cidadão. | Mesmo ID; uma entrega e uma movimentação vigente; saldo 3. Responsável não recebe os pontos do beneficiário. |
| 10 | Reenviar mesma chave, volume e data, omitindo QR. | `200`, mesmo ID; saldo e quantidade de entregas preservados. |
| 11 | Reenviar com a mesma chave e data ou volume diferente. | `409`; entrega e pontos preservados. |
| 12 | ADMIN corrigir volume para `5.25`, com revisão 1, data válida e justificativa. | `200`, revisão 2, 5 pontos; consultas do responsável e cidadão refletem a mudança. |
| 13 | Repetir alteração com revisão antiga. | `409 CONCURRENT_MODIFICATION`; não cria nova revisão. |
| 14 | ADMIN anular com revisão 2, sem volume/data e com justificativa. | `200`, revisão 3, estado `ANNULLED`, dados físicos preservados e saldo zero. |
| 15 | Anular novamente com revisão 3. | `409 BUSINESS_STATE_CONFLICT`. |
| 16 | Restaurar com revisão 3, volume `2.75`, data válida e justificativa. | `200`, revisão 4, estado `RECORDED`, saldo 2 e uma movimentação vigente. |
| 17 | ADMIN inativar PEV e responsável consultar `/pevs/me`. | Estado `INACTIVE`; identificação e novas entregas retornam `403`. |
| 18 | Reenviar a operação original do passo 8 com a mesma chave e fatos originais. | `200`, mesmo ID e estado corrigido vigente; sem duplicar pontos, mesmo após inativação. |
| 19 | Consultar entrega anterior pelo responsável ativo. | Acesso preservado ao histórico recebido; outro responsável recebe `403`. |
| 20 | Inativar a conta do responsável e reutilizar a sessão antiga. | `401`; a exceção de recuperação não dispensa conta e sessão válidas. |

## Paginação e falhas

Nas consultas de entregas do PEV, entregas administrativas e pontos, criar pelo
menos dois registros e consultar páginas consecutivas com `size=1`. Conferir ordem,
IDs distintos e `totalElements`. Consultar uma página além do total: lista vazia
com total preservado. Para pontos, o array é `movements`; nas demais listas, `items`.
Testar parâmetros vazios, negativos, `size=101` e deslocamento acima de
`Integer.MAX_VALUE`: `400`.

Após perda da resposta do POST, conservar a chave e os fatos originais para
recuperação. Uma chave nova representa outra operação. Na correção administrativa,
após timeout consultar o detalhe e sua revisão antes de decidir por novo envio.
Conferir erros pelo campo `code`, sem depender do texto de `detail`.

## Verificação automatizada

Com Java 21 e Docker Desktop disponíveis, executar dentro de `olius-api`:

```powershell
.\mvnw.cmd verify
```

O comando executa a suíte e gera o relatório JaCoCo em `target/site/jacoco`.
Na verificação local de encerramento desta branch, em 31/10/2026, o comando
concluiu com `BUILD SUCCESS`: 529 testes, zero falhas, zero erros e zero ignorados.
O Surefire registrou encerramento forçado do processo de testes após o prazo de
30 segundos de desligamento; isso ocorreu após os testes e não impediu o build,
o empacotamento ou a geração do relatório. O aviso permanece para acompanhamento
da infraestrutura de testes, sem atribuição de causa à jornada B2C.
O teste `PevIntegrationTest` cobre a sequência HTTP integrada e as regras de PEV,
entregas e pontos. Os testes administrativos e de status complementam permissões,
paginação e transições. A cobertura do novo código e o Quality Gate são confirmados
pelo Sonar no CI; um build local aprovado não substitui essa avaliação.

## Limites do aceite

Este roteiro valida a jornada disponível. Não declara implementados reapresentação
de PEV rejeitado, edição de endereço pelo titular, histórico próprio completo de
entregas ou busca geográfica por distância/área. Essas capacidades estão registradas
no [inventário](jornada-b2c.md) para etapas próprias.

A integração do ranking pela API Redis e a conferência da revisão oficial do banco
continuam dependências externas. Esta branch não cria tabelas, índices ou permissões
e não publica eventos HTTP de entrega para alimentar o ranking.

Para registrar o aceite, informar ambiente, revisão implantada, cenários executados,
resultados, limitações observadas e responsável pela validação. O roteiro manual
descrito aqui não foi executado em um aplicativo Mobile por esta branch.
