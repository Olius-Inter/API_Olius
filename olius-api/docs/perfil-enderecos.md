# Manutenção de perfil e endereços

**Entrega local:** branch `feat/profile-address`, 05/11/2026. Implementação de nome/telefones, endereço comercial e atualização dos PEVs. Troca segura de e-mail permanece para etapa posterior.

## O que o usuário pode fazer

Cidadão, estabelecimento e ADMIN ativos podem alterar o próprio nome e telefones. Estabelecimento ativo pode corrigir seu endereço comercial, mesmo sem assinatura. O responsável por um PEV pode editar o endereço daquele ponto; um PEV já aprovado retorna à análise antes de voltar ao mapa.

O endereço comercial serve às visitas B2B; o endereço do PEV é um ponto de entrega independente. Editar um não modifica o outro. Cidadão não passa a ter endereço pessoal. ADMIN não usa os recursos de autoatendimento para editar terceiros.

## Autenticação e perfil

Usar Bearer JWT da Core com sessão válida em todas as escritas. Essas rotas não autenticam pelo cookie de refresh e não exigem CSRF; os endpoints web de autenticação mantêm seu fluxo próprio.

```http
PATCH /api/v1/users/me
Authorization: Bearer <access_token>
Content-Type: application/json
```

```json
{
  "name": "Nome atualizado",
  "telephones": ["11987654321", "1134567890"]
}
```

Campo omitido não é alterado. Null explícito, corpo vazio, campos desconhecidos e duplicatas na lista retornam 400. O nome não pode ser vazio, tem limite de 150 caracteres e perde espaços nas extremidades. Cada telefone deve conter de 10 a 15 caracteres, sem ser branco; preservar o formato atual, sem exigir nova normalização de dígitos/DDD.

A lista representa o conjunto completo desejado: `[]` remove todos, novos números são inseridos e números mantidos preservam seus IDs. O mesmo número pode pertencer a outra conta. A resposta é o mesmo Profile do GET /users/me, com lista ordenada e Cache-Control no-store. Um reenvio equivalente não recria telefones ou gera atualização desnecessária do nome.

A operação é atômica e serializada por titular. Erro ao salvar telefone desfaz mudança de nome e remoções. Alterar nome/telefones não revoga sessões, muda QR ou recalcula pontos. O ranking poderá refletir o novo nome no próximo rebuild normal do seu cache.

O corpo não aceita e-mail, senha, CPF/CNPJ, perfil, status, pontos, endereço ou dados de auditoria. O planejamento de confirmação do novo e-mail continua separado; não realizar troca direta de e-mail por este PATCH.

## Endereço comercial

```http
PUT /api/v1/establishments/me/address
Authorization: Bearer <access_token>
Content-Type: application/json
```

```json
{
  "address": {
    "state": "SP",
    "city": "São Paulo",
    "neighborhood": "Centro",
    "street": "Rua Exemplo",
    "number": "100",
    "cep": "01001000",
    "complement": null,
    "latitude": -23.5505,
    "longitude": -46.6333
  }
}
```

Retorna 200 com AddressView atualizado, mantendo ID e vínculo. Todo o endereço é substituído: campos postais obrigatórios devem vir juntos; complemento e coordenadas omitidos são limpos. Não precisa de plano ativo para manter cadastro.

Uma mudança efetiva retorna 409 BUSINESS_STATE_CONFLICT se houver pedido PENDING/APPROVED ainda sem registro de coleta. Um registro de coleta, mesmo anulado, preserva a identidade da visita no contrato atual e não reabre o pedido; por isso pedidos atendidos não bloqueiam indefinidamente. Rejeitados/cancelados também não bloqueiam. Nenhuma solicitação é cancelada, transferida de ciclo ou liberada de penalidade automaticamente.

O bloqueio do estabelecimento é o mesmo da procedure create_collection_request. Quando uma criação concorrente confirma primeiro, a edição vê o pedido e recusa a mudança; quando a mudança confirma primeiro, o novo pedido usa o cadastro atualizado. Pedido em andamento não impede reenvio equivalente do mesmo endereço, pois ele não muda o cadastro.

## Endereço do PEV e nova análise

`PATCH /api/v1/pevs/me/address` mantém o corpo completo `address` mostrado acima. O titular é obtido do JWT; não se envia ID de outro PEV ou responsável. Retorna 200 com OwnPev.

| Estado anterior | Após uma mudança efetiva |
|---|---|
| PENDING | Continua PENDING |
| REJECTED | Continua REJECTED; POST /pevs/me/resubmissions é separado |
| APPROVED | Volta a PENDING e sai da lista pública até nova aprovação |
| INACTIVE | Continua INACTIVE; editar não reativa |

Reenvio equivalente mantém o estado. Endereço, estado e auditoria confirmam no mesmo commit; falhas não deixam o endereço novo com aprovação antiga ainda vigente. Entrega em andamento que já bloqueou o PEV pode confirmar primeiro; a edição aguarda, e a entrega registrada conserva volume, pontos e histórico. Novas entregas exigem novamente APPROVED pelos controles atuais.

Metadados da última aprovação são preservados durante a análise. Portanto, o aplicativo deve usar `status` para decidir se o local está aprovado, não somente `approvedAt`. Uma nova aprovação administrativa grava novos autor/data; a auditoria existente preserva o evento anterior. A Core não atualiza establishment.is_pev diretamente: a trigger deriva a flag do estado APPROVED.

## Coordenadas e histórico

Na edição, latitude/longitude são par ou ambas nulas, com faixas geográficas existentes e até seis casas decimais significativas, compatíveis com DECIMAL(9,6). Zeros extras à direita não mudam a coordenada. Precisão que exigiria arredondamento silencioso é rejeitada com 400.

Não foi adicionada geração automática: nenhuma consulta a CEP, GPS ou provedor de geocodificação. O cliente envia coordenadas correspondentes ao endereço novo ou deixa ambas nulas. A Core não verifica correspondência geográfica; apenas formato, limites e par. Não preserva os valores anteriores quando o par é omitido. As regras do cadastro original não foram alteradas implicitamente por esta nova validação de edição.

Operações, identidades, saldos e auditoria existentes são preservados. Não foi criada consulta histórica nem snapshot que congele o endereço físico na data da coleta/entrega. Consultas que usam endereço cadastral podem mostrar o endereço atual; isso não é comprovante do endereço da época.

## Consulta pública do mapa

GET /api/v1/pevs continua público, paginado e restrito a APPROVED, com ordem createdAt ASC, id ASC. Preserva os filtros de elegibilidade existentes.

Cada item passa a incluir `name`: nome cadastral do estabelecimento quando ele é o responsável e null para cidadão. O endereço público usa PublicAddress, que não contém a propriedade `complement`. E-mail, telefone, CPF/CNPJ e nome do cidadão não são expostos por essa projeção. As consultas próprias e administrativas mantêm AddressView com complemento.

A projeção usa JOIN para obter nome comercial sem uma consulta extra por item. Nome atualizado no próprio perfil aparece na próxima leitura da Core; não requer reconstruir pontos.

## Erros, publicação e validação

- 400: corpo vazio de perfil, valores inválidos, campos protegidos/desconhecidos, par/precisão inválidos.
- 401: JWT ausente/expirado, sessão revogada ou conta inativa na validação da identidade.
- 403: perfil proibido, incluindo cidadão/ADMIN tentando editar endereço comercial pelo recurso de estabelecimento.
- 404: recurso próprio não cadastrado.
- 409: mudança comercial com visita em andamento.
- Falha de banco: tratamento global e rollback; logs sanitizados, sem payload de dados pessoais.

Nenhum script SQL, migration ou privilégio foi alterado. As permissões atuais foram verificadas com os scripts em `02_Banco/01_DB_Segundo/PostgreSQL`; scripts relevantes são idênticos ao snapshot de testes. Nenhum banco real foi usado para executar a validação. CORS passa a permitir PUT para o novo endpoint, mantendo a lista de origens explícitas.

A integração Web/Mobile deve enviar somente os campos permitidos, avisar que mudar PEV aprovado exige nova análise e tratar 409 de endereço comercial. Homologação dessas interfaces e CI/Sonar remotos dependem de integração/publicação; não presumir aprovação pelos testes locais.

Validação em 05/11/2026: `mvnw -o verify` finalizou com BUILD SUCCESS; 676 testes, zero falhas, zero erros e zero ignorados. A classe nova possui 43 cenários. A suíte usa PostgreSQL 16.15 descartável e a role restrita da Core; não comprova deploy nem homologação das interfaces. O Surefire precisou encerrar o processo de testes após o prazo de saída; isso não alterou o resultado dos testes, mas o encerramento dos contextos/pools da suíte merece acompanhamento separado.
