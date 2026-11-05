# Chat da Core com a OlivIA

O mobile/web chama a Core com seu access token. A Core valida o JWT e a sessão
atual antes de encaminhar o mesmo Bearer à IA. A IA valida a identidade pela
rota existente GET /api/v1/auth/session da Core. Refresh tokens não são aceitos.
O cliente não informa user_id: a titularidade da conversa continua sendo
verificada pela IA a partir da identidade autenticada.

## Contrato público

Todas as operações exigem Authorization: Bearer ACCESS_TOKEN.

| Operação na Core | Operação interna na IA |
|---|---|
| POST /api/v1/chat | POST /api/v1/chat |
| POST /api/v1/chat/sessions/{sessionId}/iniciar | POST /sessions/{sessionId}/iniciar |
| POST /api/v1/chat/sessions/{sessionId}/encerrar | POST /sessions/{sessionId}/encerrar |

O cliente gera um UUID para a conversa e inicia a sessão antes da primeira mensagem.
Esse UUID não é o identificador da sessão de login. Reutilize o UUID da conversa
nas mensagens seguintes. Iniciar/encerrar não exige corpo JSON.

Chat recebe:

```json
{"session_id":"734437d2-7cad-451f-bd35-21dc1f090a86","pergunta":"Como armazenar óleo usado?"}
```

Chat devolve HTTP 200:

```json
{"resposta":"Resposta da OlivIA"}
```

Iniciar/encerrar devolve HTTP 200 com session_id e resumo (string ou null).
Perguntas devem ter de 1 a 4000 caracteres e não podem conter apenas espaços.
Campos desconhecidos são rejeitados pela configuração JSON da Core.
Respostas de sucesso usam Cache-Control: no-store.

## Configuração

| Variável | Padrão | Finalidade |
|---|---|---|
| OLIUS_IA_ENABLED | false | Habilita chamadas à IA; desabilitada retorna 503 |
| OLIUS_IA_BASE_URL | http://ia | Origem fixa da IA, sem caminho, credenciais, query ou fragmento |
| OLIUS_IA_CONNECT_TIMEOUT | 3s | Prazo para conectar |
| OLIUS_IA_REQUEST_TIMEOUT | 25s | Prazo total da chamada, incluindo leitura do corpo |

Timeout de conexão mínimo: 100ms. Prazo total deve ser maior ou igual ao de
conexão, até 120s. Esse teto de configuração não aumenta o limite do Gateway.
Em desenvolvimento, a URL pode ser http://localhost:8000 com port-forward.
No EKS, configure explicitamente enabled=true e base-url=http://ia no
ConfigMap ativo da Core. Reinicie somente a Core após atualizar sua imagem e
configuração. Não aplique manifests antigos da Core sobre o ambiente atual.
Nenhuma chave de LLM precisa ser adicionada à Core.

O transporte HTTP interno segue a arquitetura existente do cluster. ClusterIP
não restringe a origem exclusivamente à Core. NetworkPolicy e TLS interno
exigem configuração própria, que não é criada nesta alteração.

## Falhas e prazo externo

Erros seguem application/problem+json da Core, com code e X-Request-Id.
Corpos e cabeçalhos arbitrários da IA não são repassados; tokens, perguntas e
respostas não são registrados pelo cliente HTTP desta integração.

| Situação | HTTP / código |
|---|---|
| Corpo inválido na Core | 400 / VALIDATION_ERROR ou MALFORMED_REQUEST |
| IA recusa autenticação | 401 / AUTHENTICATION_REQUIRED |
| IA recusa permissão | 403 / ACCESS_DENIED |
| Conversa não encontrada | 404 / RESOURCE_NOT_FOUND |
| Conflito na IA | 409 / RESOURCE_CONFLICT |
| IA rejeita operação (400/422) | 422 / BUSINESS_RULE_VIOLATION |
| Cota excedida | 429 / RATE_LIMITED |
| Desabilitada, conexão falha ou IA retorna 503 | 503 / IA_UNAVAILABLE |
| Prazo excedido ou IA retorna 504 | 504 / IA_TIMEOUT |
| Resposta inesperada, inválida ou acima de 64 KiB | 502 / IA_INVALID_RESPONSE |

Não há retry de aplicação nem redirecionamento HTTP. Cada chamada mantém seu
próprio token, sem credenciais globais compartilhadas entre usuários.
Timeout ou cancelamento na Core não garante cancelamento do processamento
remoto: não repita mensagens automaticamente, pois pode duplicar turnos/custos.

A AWS documenta máximo de 30s, não ajustável, para integração de HTTP API:
https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-quotas.html
Confirme o timeout efetivo da integração, do cliente e do Load Balancer.
O padrão de 25s deixa margem, mas a autenticação da Core e a rede também consomem
tempo. A IA pode executar por 60s; fluxos acima do prazo externo precisam de
mudança para processamento assíncrono ou outro canal, fora desta implementação.

## Validação e publicação

Os testes usam servidor HTTP local e banco descartável, sem LLM ou dados reais.
Após merge e publicação manual de uma nova imagem da Core, testar pelo Gateway:
iniciar conversa, enviar mensagens com o mesmo UUID e encerrar. Confirmar 401
sem Bearer e ausência de acesso a conversas de outro usuário. Testes reais de
chat consomem cota dos provedores. A IA permanece Service ClusterIP.
