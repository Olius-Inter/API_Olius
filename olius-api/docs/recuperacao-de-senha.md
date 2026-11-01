# Recuperação de senha

Este fluxo permite que cidadãos, estabelecimentos e administradores ativos escolham uma nova senha quando não conseguem entrar. Motoristas não possuem login. A solicitação não muda a senha nem encerra sessões; somente a conclusão com um link válido faz isso.

## Uso pelo frontend e pelo Mobile

1. A tela “Esqueci minha senha” chama `POST /api/v1/auth/password-reset-requests` com `{ "email": "Pessoa@example.com" }`.
2. A Core responde `202` com a mesma mensagem para conta disponível, inexistente, inativa ou com limite por conta atingido: “Se a conta estiver disponível, enviaremos as instruções de recuperação.” Esse retorno confirma a entrada na fila, não a entrega do e-mail.
3. O destinatário recebe um link para a página configurada: `https://frontend/redefinir-senha#token=<segredo>`.
4. A página lê o fragmento, mantém o token apenas em memória e o remove da barra de endereço com `history.replaceState`. Não registrar em analytics, logs, armazenamento persistente ou URLs de requisições. Usar `Referrer-Policy: no-referrer` e evitar recursos de terceiros nessa página.
5. O usuário informa e confirma a nova senha. A confirmação é validada na interface; a Core recebe somente `{ "token": "<segredo>", "password": "<nova senha>" }` em `POST /api/v1/auth/password-resets`.
6. O sucesso retorna `204`, sem JWT ou cookie de login. A interface encaminha ao login. Todas as sessões anteriores, inclusive de outros dispositivos, deixam de autenticar.

Ambos os endpoints são públicos, sem Bearer, refresh cookie ou CSRF. A conclusão usa o segredo recebido por e-mail, não cookies de autenticação. Somente POST é liberado nessas rotas; as demais proteções da aplicação continuam vigentes. Usar HTTPS na hospedagem. Mobile pode abrir a mesma página pública; um retorno automático ao aplicativo não foi implementado.

## Validação e respostas

E-mail tem espaços externos removidos; a parte local preserva maiúsculas/minúsculas e somente o domínio é normalizado. A nova senha segue a política existente: 10–128 caracteres, uma maiúscula, um número e um símbolo; espaços não são símbolo. Campos desconhecidos são rejeitados. Corpos sensíveis têm `toString` redigido.

| Situação | HTTP / código |
| --- | --- |
| Solicitação admitida na fila | 202, mensagem genérica |
| Conclusão confirmada | 204, sem corpo |
| Entrada estrutural inválida ou senha fora da política | 400 / VALIDATION_ERROR ou MALFORMED_REQUEST |
| Token malformado, inexistente, expirado, usado, invalidado ou conta inativa | 400 / PASSWORD_RESET_INVALID |
| Limite técnico do IP | 429 / RATE_LIMITED, Retry-After: 900 |
| Canal desabilitado ou fila indisponível/cheia | 503 / SERVICE_UNAVAILABLE |
| Falha inesperada de banco na conclusão | Erro técnico padronizado; rollback integral |

Uma falha técnica ocorrida depois do 202 não altera retroativamente a resposta: o processamento é assíncrono. O diagnóstico interno registra apenas evento e tipo da exceção, sem endereço, senha, token, hash, link ou mensagem SMTP/SQL. Não há retry SMTP automático. Falha SMTP depois do commit mantém a emissão registrada; o usuário pode solicitar novamente respeitando os limites.

## Divisão de responsabilidades

`PasswordResetController` valida DTOs e define respostas. `PasswordResetService` verifica habilitação, admite a solicitação na fila e valida o formato do segredo antes de concluir. A mesma fila recebe todas as solicitações válidas antes de consultar a conta, reduzindo diferenças de tempo observáveis.

`PasswordResetWorker` gera 32 bytes aleatórios com `SecretTokens`, calcula SHA-256 e chama `PasswordResetTransactions`. O banco recebe somente o hash de 32 bytes. O token original existe apenas em memória e no e-mail. Após retornar da transação de emissão já confirmada, `SmtpPasswordResetDelivery` envia o link pelo SMTP existente, em canal independente dos convites administrativos.

`PasswordResetTransactions` usa transações novas e curtas em READ COMMITTED. `PasswordResetRepository` chama somente as funções oficiais:

- `issue_password_reset(text,bytea,interval)`: padrão de 30 minutos, três emissões por conta em 15 minutos e intervalo mínimo de 60 segundos, garantidos pelo banco entre instâncias. Nova emissão não invalida links anteriores.
- `complete_password_reset(bytea,text)`: recebe hash Argon2id produzido pelo PasswordEncoder; troca a senha, consome o token, invalida os demais pendentes e revoga as sessões com PASSWORD_RESET, atomicamente. Não chamar logout separado nem atualizar a senha diretamente nesse fluxo.

A Core não lê nem modifica diretamente as tabelas de recuperação ou seus logs. As observações diretas feitas pelos testes usam exclusivamente a conta proprietária dos bancos descartáveis. Convites e proteção do último administrador continuam independentes.

## Login e sessões

O login chama `open_auth_session(uuid,bytea,text)`. O terceiro argumento é o hash exato lido e validado no PasswordEncoder, guardado antes de abrir a sessão. Não reler uma credencial posterior. Se o banco retornar CREDENTIAL_CHANGED, a Core responde 401 genérico e não assina JWT nem emite cookie.

Isso fecha a corrida entre login com senha antiga e recuperação. `SessionJwtConverter` já consulta a sessão no PostgreSQL em cada requisição protegida; por isso, JWTs antigos deixam de autenticar após a recuperação mesmo antes de expirar. Refresh tokens também são rejeitados pela sessão revogada.

## Configuração

| Variável | Finalidade |
| --- | --- |
| OLIUS_PASSWORD_RESET_ENABLED | Padrão false. Habilitar somente após migração, SMTP e página pública configurados. |
| OLIUS_PASSWORD_RESET_URL | URL confiável da página, sem query, fragmento ou credenciais. HTTPS; HTTP apenas loopback fora de prod. |
| OLIUS_PASSWORD_RESET_TTL | Padrão 30m; duração positiva configurável. |
| OLIUS_MAIL_FROM | Remetente, compartilhado com o SMTP existente. |
| OLIUS_MAIL_HOST / PORT / USERNAME / PASSWORD | Configuração SMTP existente, com autenticação, STARTTLS e verificação do servidor. Senha de app Gmail, nunca senha normal. |

OLIUS_ADMIN_INVITATION_EMAIL_ENABLED não controla este fluxo. Recuperação habilitada com configurações obrigatórias ausentes impede a inicialização. A aplicação não testa a conexão Gmail na inicialização. No perfil local o .env é importado; prod recebe variáveis da hospedagem. Nenhuma credencial existente foi alterada.

Os endpoints têm cotas independentes de 10 chamadas por IP por janela de 15 minutos, por instância, usando o endereço observado no servidor. X-Forwarded-For enviado pelo cliente é ignorado. Ao hospedar atrás de proxy, alinhar o encaminhamento confiável do IP; sem isso vários clientes podem compartilhar a cota do proxy. A memória das cotas tem capacidade limitada. Isso complementa o limite por conta aplicado globalmente pelo PostgreSQL.

O limitador de recuperação comporta 10.000 chaves, contando separadamente solicitação e conclusão. Ao atingir a capacidade, remove janelas vencidas; se todas ainda forem válidas, novas chaves recebem 429, enquanto chaves já conhecidas mantêm sua cota restante. Isso pode impedir temporariamente novos clientes em uma carga distribuída — é uma limitação real de disponibilidade do MVP.

Não descartamos cotas ativas para admitir outra chave: alternar endereços poderia expulsar uma chave com limite esgotado e permitir seu retorno com a cota zerada. Essa política também protege login e cadastro, que reutilizam AuthRateLimiter e não contam com o limite por conta da recuperação. Na hospedagem, complementar com controle de tráfego na borda e monitorar 429; essa infraestrutura não foi implementada nesta revisão. Uma evolução deve preservar cotas ativas e memória limitada, sem confiar em cabeçalhos enviados pelo cliente.

A fila tem dois workers e capacidade de 100 solicitações pendentes por instância. Não é durável: reiniciar/parar a aplicação pode perder solicitações ainda não processadas. Segredos e tarefas não são gravados em disco, Redis ou tabelas extras. O MVP aceita uma nova solicitação do usuário nessas situações.

## Implantação e verificações

Referência do snapshot: DB_Segundo, branch feat/password-reset, commit 7e6ed3c5942b37f7fb9aa88e0d07124374b87351. Os scripts em src/test/resources/db/olius destinam-se a instalações descartáveis; não são uma migração automática do banco real.

Coordenar banco e Core: preparar aplicação compatível, manutenção da autenticação, backup, migração oficial PostgreSQL/migrations/20261101_password_reset.sql, publicação da Core, verificação de permissões/login/recuperação e liberação do tráfego. Executar a migração inteira fora de transação externa e sem --single-transaction; o ENUM confirma antes da transação principal. Não recriar a assinatura antiga de dois parâmetros nem reaplicar o patch histórico scripts/sql/update-auth-audit-context.sql após essa migração: ele pertence ao contrato anterior e deve recusar a versão nova.

Os testes automatizados usam PostgreSQL descartável e entrega de e-mail simulada; não enviam mensagens reais. A sprint ainda exige homologação de entrega real, página pública, troca de senha, novo login e rejeição dos tokens anteriores, sem expor segredos.

## Pendência explicitamente aprovada

O aviso por e-mail de “senha alterada” permanece pendente. complete_password_reset retorna somente um código e a Core não tem acesso ao dono do token. Não usar destinatário informado pelo cliente nem ampliar SELECT sobre os tokens para contornar isso. Um eventual contrato restrito deve retornar o destinatário somente após conclusão autorizada. A pendência não impede o e-mail com o link nem a troca segura de senha.

## Validação local — 01/11/2026

A suíte completa da Core passou em PostgreSQL 16.15: 594 testes, sem falhas, erros ou ignorados. A verificação direcionada de recuperação, autenticação, instalação e auditoria também passou em PostgreSQL 18.6: 77 testes, sem falhas, erros ou ignorados. A cobertura JaCoCo de linhas das novas classes de recuperação foi de 113/116 (97,41%); isso não equivale à métrica ou aprovação remota do Sonar.

Foram testados os três perfis, tokens inválidos/expirados/usados, revogação de JWT e refresh, concorrência entre duas conclusões Spring, rollback, limites, configuração, falha SMTP depois do commit, fila indisponível e proteção dos logs. E-mail real e página pública ainda exigem homologação. Nenhum banco real foi migrado por esta implementação.