# Solicitações de coleta e agenda B2B — Sprint 8

## O que este módulo faz

Um estabelecimento com assinatura e ciclo pagos vigentes pode pedir uma visita de
coleta. Na criação do pedido, sua estimativa de litros e uma vaga ficam reservadas.
O pedido ainda não representa óleo recebido nem uma coleta realizada.

Um administrador combina a visita externamente e registra seu horário. O mesmo
fluxo permite reagendar, preservando o acordo anterior. Pedidos pendentes podem
ser rejeitados; pedidos ainda não atendidos podem ser cancelados conforme a regra
de antecedência. O banco preserva a autoria e os efeitos sobre a franquia.

Pedidos antigos continuam associados ao ciclo de origem. Renovar a assinatura ou
mudar de mês não transfere reservas, perdas ou consumo para outro ciclo.

## Acesso e endpoints

Todas as operações exigem `Authorization: Bearer <accessToken>` com sessão válida.
Os perfis e a atividade da conta são verificados no servidor. Não há autenticação
por cookie nestes endpoints, nem envio de identidade do autor pelo formulário.

| Método | Endpoint | Perfil |
|---|---|---|
| GET | `/api/v1/billing/cycles/current/availability` | Estabelecimento: ciclo próprio vigente |
| GET | `/api/v1/billing/cycles/{cycleId}/availability` | Estabelecimento: ciclo próprio atual ou histórico |
| POST | `/api/v1/collection-requests` | Estabelecimento |
| GET | `/api/v1/collection-requests` | Estabelecimento: próprios pedidos |
| GET | `/api/v1/collection-requests/{id}` | Estabelecimento titular |
| GET | `/api/v1/collection-requests/{id}/schedule-history` | Estabelecimento titular |
| POST | `/api/v1/collection-requests/{id}/cancellation` | Estabelecimento titular |
| GET | `/api/v1/admin/collection-requests` | ADMIN |
| GET | `/api/v1/admin/collection-requests/{id}` | ADMIN |
| GET | `/api/v1/admin/collection-requests/{id}/schedule-history` | ADMIN |
| POST | `/api/v1/admin/collection-requests/{id}/scheduling` | ADMIN |
| POST | `/api/v1/admin/collection-requests/{id}/rejection` | ADMIN |
| POST | `/api/v1/admin/collection-requests/{id}/cancellation` | ADMIN |

A consulta `current` permite descobrir o ID do ciclo antes do primeiro pedido.
Se não houver ciclo vigente, retorna 404; não cria ciclo ou assinatura. A consulta
por UUID também retorna 404 para ciclo inexistente ou de outro estabelecimento.
O retorno da página de pagamento não garante que o ciclo já esteja liberado:
o pagamento precisa ter sido confirmado e aplicado pelo backend, conforme o
[fluxo financeiro](planos-assinaturas-pagamentos.md).

## Criar um pedido

Enviar `Idempotency-Key` como UUID gerada para esta intenção de solicitação e:

```json
{
  "estimatedVolumeLiters": 10.25,
  "observation": "Acesso pelo portão lateral"
}
```

O volume deve ser positivo, ter no máximo seis dígitos inteiros e duas casas
decimais, compatível com DECIMAL(8,2). Não há arredondamento silencioso. Observação
pode ser omitida/null; se enviada, precisa conter texto. Não são aceitos campos
extras como estado, autor, ciclo, horários ou pontuação.

Retorna 201 com o pedido e `Location` apontando para sua consulta. Reenvios com a
mesma UUID e payload original retornam o mesmo pedido em seu estado atual, também
com 201 e a mesma Location, sem nova reserva. Dados diferentes com a mesma chave
retornam 409. Não gerar outra chave automaticamente após timeout: o primeiro
envio pode ter sido confirmado. Observação omitida e null equivalem; texto não é
normalizado/recortado automaticamente para a comparação idempotente.

O PostgreSQL determina titular, ciclo e disponibilidade. PENDING já compromete
litros e vaga; o frontend não pode calcular ou confirmar a reserva por conta
própria. Consulta de saldo anterior ao POST é apenas informativa.

## Consultar e acompanhar

Listagens aceitam `status`, `cycleId`, `from`, `until`, `page` e `size`.
`from` é inclusivo e `until` exclusivo, filtrando a data da solicitação
(`requestAt`), não o horário da visita. Datas usam ISO-8601 com UTC/offset.
Se os dois limites forem informados, from deve anteceder until.
A listagem ADMIN também aceita `establishmentId`.

Page começa em 0; size padrão 20, mínimo 1, máximo 100. Pedidos são ordenados por
requestAt DESC, id DESC. Resposta: `items`, `page`, `size`, `totalElements`.
Histórico de agenda aceita apenas paginação e ordena changedAt DESC, id DESC.
Cada consulta paginada lê contagem e itens em um mesmo snapshot transacional;
isso não congela páginas carregadas em requisições futuras.

O detalhe inclui estado, estimativa, ciclo de origem, horários e grupos de
aprovação, chegada, aceite e cancelamento. `collectionId` identifica uma coleta
vinculada. **APPROVED não significa automaticamente que o pedido está sem coleta**:
o enum do banco não possui estado COMPLETED; consultar o vínculo da coleta.

Não são retornados CPF, CNPJ, contatos, QR Codes, chave de idempotência ou logs
internos. Acesso de outro estabelecimento a detalhe/histórico retorna 404.

## Agendar e reagendar

O ADMIN envia ao endpoint scheduling:

```json
{
  "scheduledAt": "2026-12-10T14:00:00-03:00",
  "agreedAt": "2026-12-09T10:00:00-03:00",
  "reason": "Horário combinado com o responsável"
}
```

O acordo deve ter ocorrido antes de seu registro. O horário não pode anteceder a
solicitação. PENDING é aprovado junto do agendamento; não há aprovação sem horário.
APPROVED pode ser reagendado enquanto não houver chegada/coleta. O horário anterior,
novo, acordo, autor, justificativa e momento da mudança ficam no histórico.
A aprovação original é preservada. Reenvio do mesmo horário não cria outra linha.

Não foram criados horário comercial, antecedência mínima, roteirização ou regra
adicional de proibição de agendamento no passado. Aplicam-se os limites do contrato
SQL. O acordo é declarado pelo ADMIN; não representa confirmação eletrônica do
estabelecimento.

## Rejeitar

Enviar `{"reason":"Motivo da rejeição"}`. Somente PENDING sem atendimento pode ser
rejeitado. Libera a reserva e retorna 200 com estado REJECTED. Rejeição repetida ou
de pedido já aprovado retorna 409. Pedido aprovado usa cancelamento operacional.

O motivo fica na auditoria do banco. Não há coluna de motivo de rejeição na tabela
base, e a Core não recebe SELECT nos logs. A resposta não inventa um campo de motivo
nem amplia permissões para lê-lo. Uma futura consulta desse motivo precisa de um
contrato autorizado específico.

## Cancelar e entender a franquia

Enviar `{"reason":"Motivo do cancelamento"}`. A rota do estabelecimento fixa a
iniciativa ESTABLISHMENT; a administrativa fixa OPERATION. O cliente não escolhe
datas, responsável, política, litros ou vagas perdidas. Retorna 200 com o pedido e
o grupo `cancellation`: policy, initiative, reason, autoria, data e perdas.

| Situação | Efeito |
|---|---|
| Sem agendamento | FREE; libera reserva |
| Até quatro horas antes da visita, inclusive | FREE |
| Após esse limite, por iniciativa do estabelecimento | LATE_FORFEITURE, ressalvada exceção de atraso |
| Falha/cancelamento operacional | FREE |
| Após uma hora de atraso, sem chegada pontual e sem aceite | FREE |
| Aceite do serviço ou coleta registrada | Cancelamento proibido |

Chegada pontual significa **antes** de scheduledAt + 1 hora. Exatamente no limite
é atraso. Pelo contrato SQL atual, chegada atrasada não elimina o cancelamento
gratuito depois da tolerância enquanto não houver aceite. Essa definição atualiza
o ponto que o texto funcional antigo ainda deixava aberto. Horários efetivos de
chegada/aceite vêm das rotinas do servidor; não são informados pelo cancelamento.

Na perda tardia, a reserva vira perda de franquia: volume estimado e uma vaga,
sem desconto duplo. Não representa óleo recolhido, coleta malsucedida, -50 pontos,
volume ambiental ou certificado. Cancelar um pedido antigo libera/perde recursos
somente no ciclo original. Reenvio autorizado de cancelamento preserva o resultado,
autor, data e motivo originais; não altera a política já registrada.

## Transações, concorrência e erros

Controller trata HTTP/validação; service verifica ator/titularidade, abre transação
e define AuditContext; repository usa consultas parametrizadas e os entrypoints
oficiais: get_cycle_availability, create_collection_request,
schedule_collection_request, reject_collection_request e cancel_collection_request.
Não existe UPDATE genérico de estado, INSERT manual de reserva, DELETE ou cálculo
de saldo duplicado em Java. JPA existente permanece disponível aos demais domínios;
estas operações usam JDBC para respeitar explicitamente o contrato de procedures.

Criação serializa a UUID e a disponibilidade no banco. Transições bloqueiam a linha
do pedido. Ator ativo é conferido e protegido na transação de escrita. Autor vem da
sessão, iniciativa vem da rota e auditoria é local à transação, limpando os campos
que não se aplicam. Falha SQL reverte os efeitos; não há confirmação parcial.

Erros seguem ProblemDetail: 400 para estrutura/formato, 401 para sessão, 403 para
perfil, 404 para recurso fora do alcance, 409 para conflito de estado/idempotência/
franquia/concorrência, 422 para entrada semanticamente incompatível rejeitada pela
rotina, 500 para falha interna e 503 para indisponibilidade reconhecida.

CollectionRoutineErrors reconhece somente SQLSTATE de rejeições deliberadas
(`exec_stmt_raise`) dentro das chamadas de escrita deste domínio. Não interpreta
texto SQL. Falta real de EXECUTE/ACL ou CHECK não reconhecido segue para o handler
global; não é mascarada como erro do usuário. Snapshot original ausente impede o
reenvio no banco com 55000: não inventar payload histórico nem gerar outra chave.
Conflitos exigem consultar novamente; não há retry automático ilimitado.

## Relação com outras sprints

Sprints 6/7 fornecem ciclos pagos e mudanças de plano. Não é necessário chamar a
Stripe para solicitar ou cancelar uma coleta. A Sprint 9 expõe o fluxo operacional
de motorista, chegada, aceite e registro da visita; esses fatos já são considerados
pelas consultas e regras desta sprint. Motoristas continuam sem login.

Não há POST para a API Redis neste módulo. Solicitar, rejeitar ou cancelar não gera
pontos; a API Redis continua usando sua leitura própria do PostgreSQL conforme o
contrato atual. Não foram implementados push, roteirização, correções ou certificados.

## Validação

CollectionRequestsIntegrationTest usa PostgreSQL real com os scripts oficiais e o
login restrito de testes. Dados proprietários são exclusivamente fixtures dentro
do container. Valida HTTP, acesso cruzado, reserva, reenvios, falta de franquia,
concorrência, agenda, rejeição, perdas, ciclos históricos e efeitos dos marcos
operacionais. CollectionRoutineErrorsTest verifica o isolamento da tradução de
erros. Nenhum teste contata Stripe/Gmail nem altera o Aiven ou banco de trabalho.

Os resultados efetivamente executados são registrados ao término da validação.
Swagger e CI remotos exigem a aplicação/esteira correspondente; resultado local
não equivale a aprovação do Sonar. Não há novas credenciais ou migration de banco.

### Resultados locais — 28/10/2026

- Suíte completa no PostgreSQL 18.6: 410 testes, zero falhas, erros ou ignorados;
  Maven verify concluiu com BUILD SUCCESS.
- Sprint 8 no PostgreSQL 16.15: 41 testes (32 de integração + 9 de tradução),
  zero falhas, erros ou ignorados; BUILD SUCCESS. Inclui a conferência final dos
  filtros individuais no Swagger via ParameterObject.
- JaCoCo da suíte completa: 157/157 linhas executáveis cobertas nos oito arquivos
  novos de produção. Essa medida local não substitui a análise do Sonar nem sua
  definição de cobertura de código novo.
- git diff --check passou. Scripts SQL, permissões, dependências e credenciais não
  foram alterados. Bancos de desenvolvimento/Aiven não foram modificados.

No teste negativo de EXECUTE, o log INTERNAL_ERROR/42501 é intencional: comprova
que a aplicação não mascara falta real de privilégio como erro de negócio.
Na suíte completa ocorreram os avisos já observados de pools ligados a containers
encerrados e demora no shutdown da JVM de testes; o Surefire encerrou o processo
após o limite, e o Maven concluiu o build com todos os testes aprovados. Nenhum
controle, timeout ou logging do projeto foi alterado para ocultar esses avisos.

A implementação local está pronta para revisão e publicação. CI/Sonar remotos e
integração por PR permanecem dependentes da publicação; não foram executados
commit ou push nesta entrega.