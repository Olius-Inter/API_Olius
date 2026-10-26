# Catálogo de planos, assinaturas e pagamentos B2B

## Estado desta etapa

A Core API disponibiliza a consulta pública dos planos ativos e a solicitação de um Checkout de contratação inicial em sandbox. Essa solicitação cria uma assinatura pendente e uma cobrança; não libera benefícios. O webhook de confirmação financeira e a aplicação do pagamento também estão implementados para sandbox. A renovação manual e a escolha do plano do próximo ciclo também estão implementadas, com testes de integração. O upgrade do ciclo vigente também está implementado, com testes de integração. Reconciliação e execução externa de reembolsos pertencem à Sprint 7 e ainda serão desenvolvidas. A expiração de cobranças será organizada junto dessa recuperação financeira; não integra explicitamente o escopo registrado da Sprint 6. A homologação externa registrada neste documento cobre a contratação inicial, a renovação antecipada do mesmo plano e o upgrade do ciclo vigente. Consultar o catálogo, isoladamente, não cria assinatura ou cobrança.

## O que é um plano

Um plano é uma oferta comercial para um estabelecimento: define preço mensal, franquia de litros e quantidade de coletas permitida por ciclo. O PostgreSQL é a fonte de verdade dessas informações, na tabela `subscription_plan`. Somente ofertas com status `ACTIVE` aparecem no catálogo.

## Como consultar

Com a aplicação em execução, use o Swagger ou envie:

```http
GET /api/v1/subscription-plans
```

A consulta não exige login. Retorna `200 OK`, com uma lista JSON ordenada pelo preço crescente e, em caso de empate, pelo UUID. Um catálogo vazio retorna `[]`; a API não inventa planos ou preços padrão.

Exemplo ilustrativo, que não representa uma oferta comercial cadastrada:

```json
[
  {
    "id": "10000000-0000-0000-0000-000000000001",
    "name": "Plano de exemplo",
    "description": null,
    "monthlyPrice": 19.90,
    "volumeLimitLiters": 100.00,
    "collectionLimit": 2
  }
]
```

`monthlyPrice` é expresso em reais (BRL). As franquias de litros e de coletas correspondem ao ciclo mensal. A descrição pode ser nula. Status e datas internas não são expostos. A resposta contém somente os seis campos apresentados acima.

No ambiente local padrão, acesse `http://localhost:8080/swagger-ui/index.html`, quando a documentação estiver habilitada, e procure o grupo **Planos**. A consulta também pode ser feita diretamente em `http://localhost:8080/api/v1/subscription-plans`.

## Como o código funciona

1. `SubscriptionPlanController` recebe o GET e entrega a lista em JSON.
2. `SubscriptionPlanCatalogService` abre uma transação somente de leitura e transforma os resultados em DTOs.
3. `SubscriptionPlanCatalogRepository` consulta somente os planos ativos, com ordenação estável.
4. `SubscriptionPlan` mapeia a tabela existente usando JPA/Hibernate. Valores decimais usam `BigDecimal`, preservando a precisão do PostgreSQL.
5. `SubscriptionPlanResponse` define o contrato público, evitando devolver diretamente a entidade persistida.

A entidade usa `@Immutable` para este caso de consulta. O repositório não oferece operações de gravação. Isso não é uma proteção global da tabela: as permissões do banco e as regras dos futuros fluxos continuam necessárias.

`SecurityConfiguration` libera especificamente o GET do catálogo. Essa decisão não libera POST, DELETE ou outros recursos protegidos. `OpenApiConfiguration` documenta o acesso público e o conteúdo da consulta.

## Próximas etapas da sprint

O planejamento do MVP utiliza Stripe Checkout para pagamento por cartão em cada contratação ou renovação iniciada pelo estabelecimento. Recorrência automática e Pix ficam para uma etapa posterior.

Na contratação, a Core deverá consultar e validar novamente o plano no PostgreSQL. O frontend não determinará preço, franquia ou liberação de benefícios. Uma cotação já emitida preservará seus valores, mesmo se o catálogo mudar depois.

A confirmação financeira é verificada no provedor antes da aplicação dos benefícios pelas rotinas do banco. Um redirecionamento de sucesso no navegador não comprova o pagamento. Ciclo mensal não deve ser convertido arbitrariamente em trinta dias.

Esses fluxos futuros não estão implementados pelo endpoint de catálogo e deverão receber seus próprios testes e documentação durante o desenvolvimento.

## Verificação automatizada

`SubscriptionPlanCatalogIntegrationTest` utiliza um PostgreSQL descartável, preparado com os scripts oficiais e consultado com o usuário restrito da API. Abrange precisão decimal, ordenação, exclusão de planos inativos, catálogo vazio, contrato HTTP público, preservação da proteção de outros recursos e documentação OpenAPI. Os dados de exemplo são exclusivos dos testes; não são inseridos no banco de desenvolvimento.

## Conector Stripe Checkout

O conector interno `CheckoutGateway` representa a criação de uma página de pagamento. `StripeCheckoutGateway` implementa esse contrato com o cliente HTTP do Spring, sem exigir um SDK adicional. O contrato recebe a identidade e os valores de uma cobrança que deverá ter sido persistida pela Core. Ele não recebe dados de cartão e não concede benefícios.

O adaptador envia `mode=payment`, somente cartão, preço em centavos sem arredondamento, identificação interna da cobrança, expiração e URLs de retorno. Usa uma chave de idempotência estável: repetir a mesma tentativa deve preservar também o payload original. A chave precisa ser lida da cobrança persistida; não deve ser gerada novamente a cada chamada.

A API Stripe utilizada está fixada em `2026-09-30.endive`, a versão identificada durante a preparação do sandbox. O filtro de cartão usa `allowed_payment_method_types`, conforme a documentação consultada. Uma atualização de versão deve ser testada antes de alterar esse contrato.

O conector exige execução fora de uma transação do banco, para não manter bloqueios enquanto aguarda uma chamada externa. Há limites de tempo para conexão e resposta. Erros do provedor são convertidos em `SERVICE_UNAVAILABLE`, sem incorporar corpo externo ou credenciais à exceção pública. A resposta é conferida quanto a moeda, valor, referência, expiração, ambiente de teste e domínio da URL de pagamento.

### Configuração desta preparação

- `OLIUS_STRIPE_ENABLED=false`: desabilitado por padrão, preservando a inicialização da aplicação sem Stripe.
- `STRIPE_SECRET_KEY`: chave secreta de sandbox, usada somente pelo backend.
- `OLIUS_CHECKOUT_SUCCESS_URL`: endereço do frontend após conclusão da página de pagamento.
- `OLIUS_CHECKOUT_CANCEL_URL`: endereço para retornar sem concluir o pagamento.

Esta versão do conector aceita somente sandbox. URLs de retorno devem usar HTTPS, com exceção de HTTP em `localhost` para desenvolvimento. Não inventar URLs de produção nem substituir a configuração por endereços enviados livremente pelo cliente.

**Estado atual:** o conector é chamado pelo endpoint de contratação inicial descrito abaixo. A validade padrão aprovada é de uma hora. As URLs permanecem configuráveis, aguardando os endereços do frontend. Habilitar a configuração, isoladamente, não realiza pagamentos; uma requisição autorizada de contratação é necessária. Confirmação por webhook e concessão dos benefícios são descritas na seção seguinte.

Os testes do conector usam respostas HTTP simuladas pelo Spring. Nenhuma credencial real é lida e nenhuma transação é criada no sandbox nesses testes.

Referência: https://docs.stripe.com/api/checkout/sessions/create

## Contratação inicial: contrato HTTP

```http
POST /api/v1/billing/initial-checkouts
Authorization: Bearer <access-token>
Idempotency-Key: <UUID da tentativa, preservada em reenvios>
Content-Type: application/json
```

```json
{ "planId": "10000000-0000-0000-0000-000000000001" }
```

O UUID acima é ilustrativo: use um plano ativo retornado pelo catálogo. O estabelecimento é identificado pelo JWT e pela sessão validada; não há campo para escolher o contratante. Preço, moeda, status, prazo e dados de auditoria não são recebidos no corpo. Campos desconhecidos são rejeitados.

A resposta `200 OK` contém `chargeId`, `planId`, `amount`, `currency`, `expiresAt` e `paymentUrl`. A URL é a página hospedada pela Stripe; o frontend pode encaminhar o usuário para ela. A resposta não contém senha, token da sessão ou chave secreta do provedor.

### O que é persistido e em qual ordem

1. A Core verifica que o usuário corresponde a um estabelecimento ativo e serializa as preparações de cobrança desse estabelecimento com bloqueios curtos no PostgreSQL.
2. Para uma nova tentativa, consulta o plano ativo e congela nome, descrição, preço e franquias na cobrança. O fluxo inicial recusa estabelecimentos com histórico de ciclo pago: esses precisam do futuro fluxo de renovação.
3. Cria ou reutiliza a assinatura `PENDING` e sua ordem `INITIAL`. Uma ordem identifica o benefício; uma cobrança identifica a tentativa financeira. A chave interna `INITIAL:<id da assinatura>` mantém a ordem estável em reemissões futuras.
4. Persiste a cobrança `OPEN`, com provedor `STRIPE`, chave de idempotência e prazo padrão de uma hora. O banco fornece timestamps e defaults protegidos. A autoria vem do usuário autenticado e é definida somente dentro da transação.
5. Confirma essa transação **antes** de chamar a Stripe. O conector cria o Checkout usando os valores persistidos. Se a identidade externa já estiver salva, consulta a mesma sessão com GET em vez de criar outra.
6. Em outra transação curta, confere o estado e associa o ID `cs_test_...` a `provider_charge_id`, sem alterar o status para `PAID` ou ativar assinatura.

O uso de SQL explícito para escrita financeira respeita os privilégios por coluna do banco. Não envia campos de status ou timestamps que a conta da API não pode escolher. O catálogo permanece integrado ao Spring Data JPA; isso não exige que todas as operações financeiras sejam escritas por ORM.

### Reenvios e falhas

- A mesma chave com o mesmo estabelecimento e plano reutiliza a mesma cobrança e sua cotação, mesmo que o catálogo seja alterado ou inativado depois da emissão.
- A chave não pode ser reutilizada para outro plano, contratante, provedor ou finalidade.
- Outra chave enquanto há cobrança inicial/renovação `OPEN` ou `CANCELLATION_PENDING` gera conflito, preservando a restrição do banco.
- Se a criação externa falhar ou a resposta se perder, a cobrança local permanece registrada. O cliente deve reenviar com a mesma chave. A Core reutiliza a chave e o payload congelado no provedor, em vez de abrir outra tentativa.
- As URLs de retorno e a versão de integração devem permanecer estáveis enquanto existirem tentativas pendentes: esses dados também integram o payload idempotente enviado à Stripe.
- Uma cobrança local vencida ou encerrada não devolve um novo link por esse endpoint. A Core não altera o status por simples passagem do tempo e não presume que um timeout significa que nenhum pagamento ocorreu.
- Reconciliação de cobranças encerradas e reemissão após expiração ainda dependem das próximas etapas; confirmações são processadas pelo webhook descrito abaixo. Uma sessão externa concluída/expirada não é tratada como uma nova sessão aberta.

### Respostas esperadas

| HTTP | Situação |
|---|---|
| 200 | Link de Checkout inicial obtido ou recuperado |
| 400 | Corpo inválido, campo desconhecido, plano ausente ou header de idempotência ausente/inválido |
| 401 | Sem autenticação ou sessão/conta inválida |
| 403 | Perfil sem autorização ou titular indisponível durante a operação |
| 404 | Plano inexistente ou indisponível para nova contratação |
| 409 | Chave incompatível, cobrança já aberta, tentativa encerrada/vencida ou necessidade de renovação |
| 503 | Conector desabilitado, falha de comunicação ou resposta externa não utilizável |

### Prazo e configuração

`OLIUS_INITIAL_CHECKOUT_TTL=1h` é o padrão aprovado e é lido por `olius.payments.initial-checkout-ttl`. Para esta integração, a configuração deve superar 30 minutos e não ultrapassar 24 horas. A margem acima de 30 minutos evita emitir um prazo que já fique abaixo do mínimo do provedor durante a primeira requisição. O prazo é congelado ao criar a tentativa e não é estendido a cada reenvio.

As URLs reais ainda não foram definidas. Nos testes são utilizados endereços locais simulados. Nenhuma página fictícia foi criada no frontend nem valores reais foram inseridos no `.env`. A integração permanece desabilitada por padrão; o conector desta etapa aceita apenas chave de sandbox.

### Verificação desta entrega

`InitialCheckoutIntegrationTest` usa PostgreSQL descartável com o usuário restrito da API, cadastro/login reais e provedor simulado. Verifica cotação, prazo, auditoria, ausência de benefícios antes do pagamento, controle de acesso, rejeição de campos controlados pelo servidor, recuperação após timeout, isolamento entre contratantes, repetição concorrente e expiração. `InitialCheckoutServiceTest` verifica que o provedor desabilitado não inicia escrita no banco. O teste do adaptador verifica também a recuperação da sessão Stripe por GET.

Referências oficiais: [criar Checkout](https://docs.stripe.com/api/checkout/sessions/create), [consultar Checkout](https://docs.stripe.com/api/checkout/sessions/retrieve), [idempotência](https://docs.stripe.com/api/idempotent_requests).

## Webhook: da notificação ao benefício

Um webhook é uma requisição enviada pelo provedor ao backend para avisar que algo aconteceu. Ele não depende do navegador do cliente permanecer aberto. O evento é um aviso; a Core ainda consulta os dados no provedor antes de reconhecer um pagamento.

### Entrada autenticada pela Stripe

```http
POST /api/v1/webhooks/stripe
Stripe-Signature: <assinatura gerada pela Stripe>
Content-Type: application/json
```

Essa rota não exige JWT de usuário. Ela autentica a origem pelo protocolo de assinatura da Stripe: HMAC-SHA256 sobre o timestamp do header, um ponto e os bytes originais do corpo. A comparação é feita com tempo constante e aceita mais de uma assinatura `v1`, como ocorre durante rotação de segredo. O relógio deve estar sincronizado: assinaturas com diferença superior a cinco minutos são recusadas. O tamanho do corpo é limitado a 256 KiB; header, assinatura e corpo não são gravados em logs.

O segredo `STRIPE_WEBHOOK_SECRET` (`whsec_...`) é diferente da chave secreta `STRIPE_SECRET_KEY`. O primeiro valida os avisos recebidos; a segunda permite à Core consultar/criar recursos na Stripe. Ambos são exigidos quando a integração sandbox está habilitada. Não devem ser enviados ao frontend nem versionados.

O endpoint desta entrega aceita **eventos snapshot** de sandbox. Só enfileira `payment_intent.succeeded` com `metadata.charge_id` que identifica uma cobrança OLIUS. Outros tipos válidos e pagamentos de teste sem essa identificação são ignorados com `204`. Não cadastrar eventos thin nesse endpoint: seu formato é diferente. Evento de produção, assinatura inválida ou formato incorreto retorna `400`; integração desabilitada retorna `503`.

### Receber não significa aplicar

A assinatura é validada antes de interpretar o JSON. Para um aviso suportado, a Core insere em `payment_provider_event` somente a identidade do evento, o tipo e a identidade do PaymentIntent, usando os campos/defaults autorizados. Não armazena payload bruto, cartão, segredo ou URL de pagamento.

A resposta `204 No Content` só é devolvida depois de confirmar a transação de recebimento. Uma repetição do mesmo evento não cria outra linha e não reinicia uma tentativa já concluída. Se a persistência falhar, a requisição falha; não confirma um aviso que foi perdido.

### Processador durável

Com `OLIUS_STRIPE_ENABLED=true`, um processador agendado consulta a fila a cada cinco segundos e seleciona até dez eventos elegíveis por passagem. Isso utiliza as tabelas já existentes; não exige Redis, novo schema ou aumento de privilégios.

1. Uma transação curta reivindica o evento: `PENDING`/`FAILED` elegível passa a `PROCESSING`, incrementa `attempts` e registra um prazo de cinco minutos em `next_attempt_at`.
2. Fora da transação, a Core consulta `GET /v1/events/{id}` e `GET /v1/payment_intents/{id}` com sua chave secreta. Confere evento, PaymentIntent, ambiente sandbox, estado `succeeded`, moeda BRL e vínculo `metadata.charge_id` entre os dois recursos.
3. Outra transação verifica que a tentativa ainda é a atual, confere a cobrança local e o valor efetivamente recebido. Registra `payment`, chama `apply_verified_payment` e marca o evento `PROCESSED` na mesma transação.
4. Se houver falha, a transação de pagamento/benefícios é revertida. Uma transação separada marca o evento `FAILED`, com mensagem técnica fixa e nova tentativa em cinco minutos. O aviso original permanece no banco.

Se a aplicação parar depois de reivindicar um evento, outra execução poderá retomá-lo após o prazo de processamento. O número da tentativa impede que um processador antigo conclua ou sobrescreva uma tentativa mais recente. A consulta ao provedor não segura bloqueios do banco.

`paid_at` usa o instante `created` do evento de sucesso consultado na Stripe, como referência da confirmação financeira. Não utiliza a criação inicial do PaymentIntent, pois a autenticação/pagamento pode terminar depois. `verified_at` e `created_at` respeitam a ordem temporal do banco. Aviso antigo não é recusado por ser antigo: a tolerância de cinco minutos refere-se à assinatura da entrega, não à idade do pagamento.

### O que o PostgreSQL aplica

O registro do pagamento usa `provider_payment_id` para reconhecer o mesmo recebimento mesmo quando chegam eventos diferentes. A aplicação é realizada exclusivamente por `CALL apply_verified_payment(?, NULL, NULL, NULL)`, lendo os parâmetros OUT retornados.

Para a contratação inicial válida, a rotina ativa a assinatura, cria o ciclo mensal com a cotação congelada, registra a aplicação e marca a cobrança paga. A Core não edita esses estados diretamente nem calcula um período fixo de trinta dias. A autoria dessas escritas é `SYSTEM`, com contexto local à transação e sem atribuir o pagamento a um usuário artificial.

Se outro pagamento real confirmar o mesmo benefício, a rotina prevê `payment_refund` em `REQUESTED`. O evento pode estar processado e o reembolso ainda estar pendente: **solicitação de reembolso não é devolução concluída**. A chamada à API de reembolso e suas tentativas externas ainda não fazem parte desta entrega.

### Falhas que exigem acompanhamento

Divergência de valor/moeda/vínculo não concede benefício. Pagamento em cobrança já encerrada segue o resultado da rotina oficial; se não houver hipótese de reembolso definida, o evento fica em falha para investigação. Esta implementação não inventa reembolso, não altera a cobrança para contornar a rotina e não considera dinheiro devolvido sem confirmação externa.

Os eventos `FAILED`, `attempts`, `next_attempt_at` e `last_error` precisam ser acompanhados operacionalmente. Falhas persistentes continuam elegíveis a novas tentativas e requerem investigação. A API da Stripe só permite consultar eventos dos últimos trinta dias; uma interrupção maior exige reconciliação administrativa, não uma liberação presumida de benefícios.

### Teste local com Stripe CLI

Depois de configurar as URLs de retorno e habilitar o sandbox na aplicação:

```powershell
stripe listen --events payment_intent.succeeded --forward-to http://localhost:8080/api/v1/webhooks/stripe
```

Mantenha o terminal aberto. Use o `whsec_...` exibido por esse listener no `.env` local e reinicie a aplicação para carregar a configuração. O segredo de um endpoint do Dashboard pode ser diferente do segredo usado pelo encaminhamento da CLI.

O teste completo precisa criar o Checkout pelo endpoint da Core e concluir esse Checkout no sandbox. Um `stripe trigger payment_intent.succeeded` isolado não contém a cobrança OLIUS e, portanto, não ativa assinatura. Redirecionar para a URL de sucesso também não substitui a confirmação pelo webhook.

### Testes automatizados

Os testes cobrem assinatura válida/vencida, alteração do corpo, vetor HMAC independente, limite do corpo, recebimento sem JWT, persistência antes da confirmação HTTP, consulta financeira fora da transação, aplicação/ciclo/auditoria, deduplicação por evento e pagamento, recuperação de falha, concorrência, retomada de tentativa abandonada, divergência de valor e rollback da rotina.

Também há um teste de CORS para `Idempotency-Key`: esse header precisa ser permitido no preflight do navegador para o frontend conseguir solicitar o Checkout em outra origem autorizada.

Fontes: [assinatura e entrega de eventos](https://docs.stripe.com/webhooks), [consulta de evento](https://docs.stripe.com/api/events/retrieve), [consulta de PaymentIntent](https://docs.stripe.com/api/payment_intents/retrieve).

### Transporte HTTP e inicialização habilitada

O conector usa `RestClient` com `SimpleClientHttpRequestFactory`, timeout de conexão de cinco segundos, leitura de quinze segundos e redirecionamentos desabilitados. Essa escolha evita uma falha de inicialização observada no seletor NIO do cliente HTTP do Java neste ambiente Windows. Não exige instalar outra biblioteca e não desabilita a verificação TLS.

Há um teste que inicializa o conector habilitado com credenciais fictícias e URLs locais, verificando a criação dos componentes de Checkout, verificação financeira e assinatura. Ele não realiza chamadas à Stripe. A homologação com a conta sandbox real continua sendo uma etapa separada dos testes automatizados.

## Homologação integrada no sandbox — 26/10/2026

O cenário positivo foi executado com a conta sandbox da Stripe, listener da Stripe CLI e PostgreSQL local `olius_dev`. Foi usado um estabelecimento fictício criado pelos endpoints da Core, sem dados pessoais reais. O `.env` foi preservado: a habilitação, o segredo do listener e as URLs locais de retorno foram fornecidos apenas ao processo temporário da aplicação.

O catálogo instalado forneceu o **Plano Básico**, de **R$ 19,90**, com **50 litros e duas coletas por ciclo**. Esses valores vieram do PostgreSQL; não foram definidos pelo formulário de pagamento nem inseridos como uma nova oferta para o teste.

O usuário concluiu o Checkout hospedado pela Stripe com cartão de teste. O listener recebeu `payment_intent.succeeded` e o endpoint respondeu `204`. A consulta independente ao banco confirmou:

| Verificação | Resultado |
|---|---|
| Cobrança | `PAID`, BRL 19,90 |
| Assinatura | `ACTIVE` |
| Pagamentos para a cobrança | 1 |
| Aplicações para a ordem | 1 |
| Ciclos para o estabelecimento de teste | 1 |
| Evento | `PROCESSED`, uma tentativa, sem erro |
| Auditoria do pagamento | `SYSTEM`, sem usuário artificial |
| Vigência do ciclo | 26/10/2026 a 26/11/2026, iniciada na aplicação do pagamento |

Identificadores técnicos para rastrear esta homologação no ambiente local:

- Cobrança: `32574a1c-87fe-4230-8223-65c770cbf52d`.
- Evento Stripe: `evt_3UUlNUAcGiLZhFtz1zbpQcSu`.
- PaymentIntent: `pi_3UUlNUAcGiLZhFtz1WYtwCZn`.
- Ciclo: `22ecd2db-a7f2-49d2-a080-03d2e9aa7953`.

Antes do pagamento, o mesmo cenário apresentava cobrança `OPEN`, assinatura `PENDING` e zero pagamentos, aplicações e ciclos. A ativação foi comprovada pelo banco e pelo evento processado, não apenas pela página de sucesso do navegador.

Na execução assistida no Windows, houve falha do seletor NIO do Java também ao iniciar o Tomcat. Foi usado um ajuste restrito à JVM desse ensaio para permitir o fallback de comunicação local em TCP. Esse ajuste não foi incorporado às configurações globais da máquina nem aos padrões da API. O artefato executado foi compilado a partir dos mesmos fontes da Core, cuja igualdade com a cópia de validação foi conferida antes da execução.

Depois da confirmação, os processos temporários da aplicação, do listener e das páginas locais de retorno foram encerrados. Os registros de homologação e o volume do PostgreSQL foram preservados.

**Limite da evidência:** esta execução homologa a contratação inicial aprovada no sandbox. Não afirma homologação externa de renovação, upgrade, reembolso, cartão recusado ou indisponibilidade. Os cenários de duplicidade, concorrência e falha têm cobertura automatizada, mas não foram todos repetidos na conta Stripe durante este ensaio. A suíte automatizada anterior passou com 170 testes; nenhum código de negócio foi alterado nesta homologação.

## Renovação manual e plano do próximo ciclo

Renovar é pagar por outro período mensal. Não é estender silenciosamente o ciclo atual nem cadastrar uma cobrança recorrente. O estabelecimento inicia cada pagamento; o webhook verifica a confirmação e a rotina do PostgreSQL aplica o benefício.

```http
POST /api/v1/billing/renewal-checkouts
Authorization: Bearer <access-token>
Idempotency-Key: <UUID da tentativa, preservada em reenvios>
Content-Type: application/json
```

```json
{ "planId": "<UUID de um plano ativo do catálogo>" }
```

A resposta `200 OK` usa os mesmos campos da contratação inicial: `chargeId`, `planId`, `amount`, `currency`, `expiresAt` e `paymentUrl`. Só um estabelecimento ativo autenticado pode solicitar a renovação. O corpo não aceita contratante, ciclo de origem, preço, status ou datas: esses dados são resolvidos e controlados pela Core.

### Elegibilidade e ordem do benefício

A Core identifica o ciclo mais recente do próprio estabelecimento. Um estabelecimento sem ciclo anterior precisa contratar pelo fluxo inicial. Se o ciclo mais recente ainda não começou, o próximo período já está contratado e não se pode antecipar outro.

Uma nova renovação exige plano ativo e ausência de cobrança inicial/renovação `OPEN` ou `CANCELLATION_PENDING`. O catálogo é consultado somente ao emitir uma nova cotação. No reenvio idempotente, prevalecem os valores já congelados, mesmo que a oferta seja alterada ou inativada depois.

A ordem possui finalidade `RENEWAL`, aponta `previous_cycle_id` e preserva a assinatura de origem. A chave técnica `RENEWAL:<UUID do ciclo anterior>` identifica o benefício de maneira estável. Reemissões futuras devem reutilizar essa ordem, não cadastrar outro benefício para o mesmo ciclo anterior. As unicidades e as permissões existentes do banco continuam valendo.

### Quando o período começa

- **Pagamento aplicado antes ou exatamente no vencimento:** o próximo ciclo começa no fim do atual. A âncora mensal é preservada; dia 31 pode ajustar para um mês mais curto sem perder a referência original.
- **Pagamento aplicado depois da interrupção:** o novo ciclo começa na liberação. O tempo sem acesso não é cobrado retroativamente e o histórico antigo permanece.
- **Período futuro já pago:** seu plano e suas condições ficam preservados; outra antecipação é recusada enquanto esse ciclo não começar.

Essas datas são determinadas por `apply_verified_payment`, não por uma soma de trinta dias no Java. A emissão do Checkout não cria o novo ciclo. Se o ciclo atual terminar enquanto o Checkout estiver aberto, vale a situação real no momento da aplicação do pagamento.

### Downgrade e escolha do plano

O estabelecimento pode escolher um plano ativo diferente para o próximo período, inclusive um inferior. A cobrança usa o preço integral cotado desse plano. O ciclo atual mantém preço, franquias, vencimento e registros; não há redução retroativa nem reembolso presumido.

Depois de paga, a renovação preserva a cotação contratada. Não há endpoint nesta etapa para trocar o plano de um ciclo futuro já pago. Um upgrade do período atual, quando implementado, não deverá reescrever essa contratação futura.

### Prazo aprovado e reaproveitamento da integração

`OLIUS_RENEWAL_CHECKOUT_TTL=1h` é o prazo aprovado, com configuração própria em `olius.payments.renewal-checkout-ttl`. A configuração deve superar 30 minutos e não ultrapassar 24 horas. O prazo é congelado ao criar a tentativa e não é renovado pelo simples reenvio. Diferentemente do upgrade, a cobrança de renovação não é limitada ao fim do ciclo atual, pois a renovação também pode ocorrer após a interrupção.

`RenewalCheckoutTransactions` mantém as validações próprias da renovação. `CheckoutSessionService` e `CheckoutAssociationTransactions` compartilham a criação/recuperação do Checkout e a associação da identidade Stripe com o fluxo inicial. O transporte ocorre fora de transações do banco; as escritas usam transações curtas e contexto de auditoria do usuário autenticado.

A confirmação usa o mesmo webhook e processador financeiro já existentes. `payment`, aplicação, novo ciclo e evento processado continuam sendo confirmados atomicamente. Nenhum script SQL ou privilégio precisou ser alterado para esta etapa.

### Erros e evidência de testes

Corpo/header inválido retorna `400`; falta de autenticação, `401`; perfil não autorizado, `403`; plano indisponível para nova cotação, `404`. Ausência de histórico, ciclo futuro já pago, cobrança de período em aberto e chave/payload incompatíveis são conflitos `409`. Falha externa ou integração desabilitada retorna `503`.

Os testes de integração usam PostgreSQL descartável e a conta restrita da API. Abrangem emissão, preço congelado, reenvio, timeout, concorrência, finalidade da ordem, proteção dos dados controlados pelo servidor, renovação antecipada/tardia, escolha de plano inferior, preservação do ciclo atual e bloqueio de múltiplos períodos futuros. A confirmação é exercitada com a rotina real de pagamento; o provedor permanece simulado. A renovação antecipada do mesmo plano foi homologada na conta externa da Stripe, conforme o registro abaixo. Os demais cenários de renovação possuem cobertura automatizada.

Validação da renovação em 26/10/2026: `mvnw verify` concluiu com **183 testes, zero falhas, zero erros e zero testes ignorados**. A classe de renovação contribui com 13 cenários de integração. A execução foi feita em cópia isolada dos fontes atuais para preservar o ambiente de desenvolvimento.

## Homologação da renovação antecipada — 26/10/2026

Foi reutilizado o estabelecimento fictício da contratação inicial. A credencial desse cadastro de teste foi renovada para permitir novo login pela API; nenhum dado de uma conta real foi alterado. O fluxo utilizou o artefato validado com 183 testes, Stripe sandbox, listener da CLI e PostgreSQL local `olius_dev`, sem alterar o `.env`.

A Core emitiu um Checkout `RENEWAL` de **R$ 19,90**, vinculado ao ciclo original e ao Plano Básico. Antes do pagamento havia uma cobrança aberta, zero pagamentos/aplicações dessa ordem e somente o ciclo original.

Depois de o usuário concluir o Checkout com cartão de teste, o listener recebeu `payment_intent.succeeded` e a Core respondeu `204`. A consulta independente ao PostgreSQL comprovou:

| Verificação | Resultado |
|---|---|
| Cobrança de renovação | `PAID`, BRL 19,90 |
| Evento | `PROCESSED`, uma tentativa, sem erro |
| Pagamentos dessa cobrança | 1 |
| Aplicações dessa ordem | 1 |
| Total de ciclos do estabelecimento | 2: original e próximo período |
| Ciclo original | Registro integralmente preservado, inclusive timestamps e limites |
| Próximo ciclo | 26/11/2026 a 26/12/2026 |
| Continuidade | Início do novo ciclo exatamente igual ao fim do anterior |
| Cotação do próximo ciclo | R$ 19,90, 50 litros, duas coletas |
| Auditoria do pagamento | `SYSTEM`, sem usuário artificial |

Rastreamento técnico do ensaio:

- Cobrança: `394af992-e0f5-441f-a82a-7c97502ebd31`.
- Evento Stripe: `evt_3UUlruAcGiLZhFtz1D8Aj6a3`.
- PaymentIntent: `pi_3UUlruAcGiLZhFtz17dEgNJt`.
- Próximo ciclo: `56b0ba8b-7dd8-4aee-8858-b2ad79d41510`.

A evidência demonstra que pagar antecipadamente reserva o próximo período, sem antecipar seu início ou alterar o ciclo atual. A página de sucesso do navegador não foi utilizada como prova financeira.

Os processos temporários da aplicação, da CLI e das páginas de retorno foram encerrados após a confirmação. O volume do banco e os registros foram preservados. Nenhum commit foi criado.

Este ensaio cobre renovação antecipada do mesmo plano. Renovação tardia, escolha de outro plano, concorrência e falhas continuam cobertos pelos testes automatizados; esta execução não afirma homologação externa desses outros cenários. Upgrade pertence à Sprint 7 e está implementado e homologado nesta branch; reconciliação e execução externa de reembolsos permanecem pendentes dessa sprint.

## Upgrade do ciclo vigente

Upgrade aumenta o plano do período que já está em andamento. Ele não cria outro ciclo, não altera o vencimento e não refaz uma renovação futura já paga. A cobrança corresponde à diferença integral entre o preço do plano desejado e o preço contratado no ciclo atual, sem cálculo proporcional aos dias restantes.

Exemplo: um ciclo contratado a R$ 19,90 passa para um plano de R$ 39,90 mediante cobrança de R$ 20,00. Se o preço do plano antigo for alterado no catálogo depois da contratação, a origem continua sendo o preço preservado no ciclo, não o novo preço comercial.

```http
POST /api/v1/billing/upgrade-checkouts
Authorization: Bearer <access-token>
Idempotency-Key: <UUID da tentativa, preservada em reenvios>
Content-Type: application/json
```

```json
{ "planId": "<UUID do plano de destino ativo>" }
```

### Condições de emissão

A Core identifica o ciclo **vigente** do estabelecimento ativo autenticado. Não utiliza simplesmente o ciclo de maior número: ele pode representar um período futuro já pago, que não deve receber o upgrade atual.

O plano de destino precisa ser ativo, diferente do plano vigente e aumentar **os três valores**: preço mensal, franquia de litros e quantidade de coletas. Mesmo nome ou preço maior isoladamente não basta. Sem ciclo vigente, plano compatível ou prazo suficiente, a operação é recusada antes de criar a cobrança.

Só pode existir uma cobrança de upgrade `OPEN` ou `CANCELLATION_PENDING` por ciclo. Essa restrição é independente da cobrança de contratação/renovação por estabelecimento. Assim, uma renovação do próximo período pode coexistir com um upgrade do atual.

A preparação serializa emissões por estabelecimento. Depois de verificar a vaga de cobrança, a Core relê o ciclo para considerar uma confirmação concorrente que já tenha alterado o plano. Na confirmação financeira, a rotina do PostgreSQL novamente valida o estado e aplica o benefício atomicamente.

### Prazo aprovado para o Checkout

`OLIUS_UPGRADE_CHECKOUT_TTL=1h` define o teto padrão em `olius.payments.upgrade-checkout-ttl`. O prazo efetivo é o menor entre esse teto e o encerramento do ciclo, arredondado para segundos sem ultrapassar o vencimento real.

Foi aprovada a seguinte margem técnica para o MVP: **novos links exigem mais de 35 minutos de validade efetiva**. O provedor exige pelo menos 30 minutos; os cinco minutos adicionais reservam uma margem para preparação e comunicação. A configuração do teto deve superar 35 minutos e não ultrapassar uma hora.

Essa condição vale na emissão. Recuperar um Checkout já emitido não exige novamente 35 minutos: uma tentativa ainda `OPEN` e dentro da validade pode ser consultada com a mesma chave. Reenvios não estendem a expiração nem criam outra cobrança. Um timeout ambíguo mantém a mesma tentativa; seu tratamento segue os mecanismos de recuperação/reconciliação, sem gerar nova chave silenciosamente.

### Cotação e informações antes do pagamento

A resposta preserva os campos comuns `chargeId`, `planId`, `amount`, `currency`, `expiresAt` e `paymentUrl`. Acrescenta o objeto `upgrade`:

- `cycleId` e `cycleEndsAt`: período que será alterado e vencimento preservado.
- `fromPlanId` e `fromMonthlyPrice`: origem contratada, preservada na cobrança.
- `targetMonthlyPrice`, `targetVolumeLimitLiters` e `targetCollectionLimit`: condições do destino congeladas na emissão.
- `currentAvailableVolumeLiters` e `currentAvailableCollectionSlots`: disponibilidade consultada no momento da projeção.
- `availableVolumeLitersAfterUpgrade` e `availableCollectionSlotsAfterUpgrade`: projeção usando os novos limites e os registros existentes.
- `availabilityCalculatedAt`: instante de consulta das disponibilidades.

O frontend deve apresentar a diferença de preço, o vencimento e a projeção de franquias **antes de encaminhar o usuário para confirmar o pagamento**. A Core não implementa essa tela.

A projeção consulta `get_cycle_availability` e usa seus componentes de reserva, consumo e perda. Não calcula simplesmente saldo atual mais a diferença de limites, pois um saldo atual limitado a zero pode esconder consumo excedente. Os componentes são subtraídos dos limites de destino e o resultado é limitado a zero.

A disponibilidade é uma consulta datada, não uma reserva de litros ou vagas. Novas operações podem alterá-la durante o pagamento. A cotação financeira fica congelada; a disponibilidade pode ser atualizada em uma consulta/reenvio, sempre acompanhada do instante de cálculo.

### Persistência e identidade

A ordem recebe `UPGRADE` e `target_cycle_id`, sem `previous_cycle_id`. Nesta implementação, `UPGRADE:<ciclo>:<plano de origem>:<plano de destino>` identifica a transição. Reemissões equivalentes dessa transição reutilizam a ordem; upgrades sucessivos, por exemplo Básico → Profissional → um terceiro plano superior, possuem ordens distintas.

A cobrança preserva origem, preço de origem, destino, preço/franquias de destino, diferença cobrada, chave de idempotência e expiração. Não cria novas tabelas nem amplia privilégios. O cliente não escolhe esses valores, o ciclo, o contratante ou a finalidade por campos livres no formulário.

### Aplicação e confirmação tardia

O mesmo webhook verifica o pagamento e chama `apply_verified_payment`. Para um upgrade aplicável, o banco substitui plano, preço e limites do ciclo vigente, registra `subscription_cycle_change` e preserva início, fim, consumo, reservas e os demais registros do período. Os limites não são somados aos antigos.

Exemplo: com 20 litros e uma vaga reservados, o destino de 220 litros e quatro vagas projeta **200 litros e três vagas disponíveis**. A reserva continua vinculada ao mesmo ciclo.

Uma renovação futura paga permanece integralmente preservada, inclusive seu plano e suas condições. O upgrade atual não reescreve esse contrato futuro.

Se o ciclo já terminou quando a confirmação é aplicada, a rotina registra `payment_refund` com motivo `EXPIRED_UPGRADE` e estado `REQUESTED`, sem aplicar o upgrade ou estender o período. Isso não é prova de devolução concluída: a execução externa do reembolso continua pendente da Sprint 7. Dois pagamentos reais do mesmo benefício também seguem o tratamento de duplicidade previsto no banco; repetir apenas uma notificação não representa outro pagamento real.

### Validação desta etapa

Os testes de integração usam PostgreSQL descartável com a conta restrita da Core e Stripe simulada. Cobrem diferença integral, preço histórico de origem, cotação congelada, resposta HTTP, reservas preservadas, renovação futura intacta, limite de validade, margem de emissão, recuperação de link existente, timeout, concorrência, upgrades sucessivos e solicitação de reembolso por confirmação tardia ou pagamento duplicado.

A verificação completa da aplicação passou com 198 testes, sem falhas ou testes ignorados. A homologação externa do upgrade está registrada abaixo.

### Homologação externa do upgrade — 26/10/2026

No sandbox da Stripe, usando exclusivamente o cadastro fictício do banco local, foi pago o Checkout da cobrança `8f5c0a18-142d-45bc-b906-e2299836dbf0`, no valor de R$ 20,00. Esse valor corresponde à diferença integral entre o plano Básico (R$ 19,90) contratado no ciclo e o Profissional (R$ 39,90).

A verificação posterior no PostgreSQL confirmou:

- cobrança `PAID`, com exatamente um pagamento e uma aplicação do benefício;
- evento do provedor `PROCESSED` na primeira tentativa, sem erro;
- ciclo vigente atualizado para o Profissional, com 220 litros e 4 coletas;
- início e fim do ciclo vigente preservados, com término em 26/11/2026;
- renovação futura integralmente inalterada, mantendo o Básico, 50 litros e 2 coletas, de 26/11/2026 a 26/12/2026;
- quantidade de ciclos mantida em dois: o upgrade não criou outro período.

O resultado foi confirmado pelos registros do banco, não pelo redirecionamento do navegador. Os processos temporários da aplicação, da Stripe CLI e das páginas locais de retorno foram encerrados após a verificação. Os registros locais de homologação foram preservados; o arquivo `.env` não foi alterado.

Esta homologação externa cobre o caminho de sucesso do upgrade com renovação futura paga. Cenários de confirmação tardia, duplicidade, concorrência e reservas existentes foram verificados nos testes automatizados; não são declarados como homologados externamente nesta execução. Reconciliação/expiração e execução externa de reembolsos continuam pendentes da etapa de recuperação financeira, separada da Sprint 6.

## Verificação de encerramento da Sprint 6 — contratação e ciclos

A tarefa registrada no ClickUp para a Sprint 6 contempla catálogo, contratação, confirmação financeira, ciclo mensal e renovação manual antecipada ou após interrupção. Upgrade e reembolsos pertencem à Sprint 7; o upgrade implementado e homologado neste documento é uma antecipação dessa sprint. Pendências da Sprint 7 não devem ser apresentadas como critérios adicionais para concluir a Sprint 6.

Em 26/10/2026 foram executados 45 testes específicos de contratação inicial, renovação e webhook, sem falhas, erros ou testes ignorados. A cobertura dos critérios inclui:

| Critério | Evidência automatizada |
| --- | --- |
| Ausência de pagamento válido | Checkout mantém assinatura pendente e não cria ciclo; assinatura inválida, pagamento não confirmado e valor divergente não concedem benefício. A verificação do status do provedor também possui teste próprio do conector. |
| Confirmação repetida | Repetir evento ou receber eventos diferentes do mesmo pagamento mantém um único pagamento e benefício; trabalhadores concorrentes também preservam essa unicidade. |
| Interrupção antes do retorno do Checkout | Timeout preserva a cobrança confirmada no banco e a identidade da tentativa; repetir a solicitação recupera a mesma cobrança. |
| Interrupção do processamento | Evento persistido e marcado PROCESSING é retomado pelo trabalhador após expirar sua janela de execução; uma nova passagem não duplica o benefício. Trata-se de simulação controlada do estado persistido após interrupção, não de encerramento físico da JVM. |
| Falha durante aplicação | Falha na transação desfaz pagamento e benefícios; evento permanece rastreável. Falha temporária pode ser tentada novamente. |
| Renovação após interrupção da assinatura | Início é a liberação atual, sem período retroativo; pagamento antecipado começa no fim do ciclo anterior. |
| Calendário civil | Dez casos explícitos cobrem âncoras 29/30/31, fevereiro comum e bissexto, recuperação da referência em março e passagem de abril para maio; horário local e America/Sao_Paulo são preservados. |

Os testes de calendário executam a rotina real de aplicação do pagamento do PostgreSQL com as permissões restritas da Core. Para selecionar meses específicos sem alterar o relógio do sistema, somente o ciclo de referência do banco descartável recebe um término futuro controlado; a duração alongada dessa massa não representa um período comercial emitido pela aplicação. Os valores esperados são datas explícitas, independentes do cálculo SQL sob teste.

Contratação inicial e renovação antecipada do mesmo plano também foram homologadas externamente no sandbox da Stripe. Os cenários negativos, de interrupção e de calendário acima são evidências automatizadas com provedor simulado; não representam novas cobranças externas. Publicação no GitHub e resultado do CI devem ser verificados separadamente antes de considerar a entrega publicada.

Resultado da regressão completa de encerramento: 209 testes executados, zero falhas, zero erros e zero testes ignorados. A validação foi executada em cópia isolada dos fontes, com PostgreSQL descartável. Os critérios funcionais verificados da Sprint 6 foram atendidos; commits, PR e CI de publicação permanecem separados desta evidência local.
