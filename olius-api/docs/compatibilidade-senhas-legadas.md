# Compatibilidade de senhas migradas — contrato Core / PostgreSQL / ETL

**Data:** 02/11/2026. **Branch:** `feat/legacy-password-upgrade`.  
**Situação:** implementação local; publicar somente após revisão/CI e homologação coordenada da carga. A carga ETL não foi implementada nem executada nesta entrega.

## Resultado para o usuário

A pessoa importada pode usar a mesma senha. No primeiro login correto, a Core valida o hash PBKDF2 original, gera Argon2id a partir da senha digitada e substitui o hash ao abrir a sessão. A senha não é decodificada: hashes são verificadores, não criptografia reversível. Não existe conversão periódica nem operação que transforme um hash antigo em hash novo sem a senha digitada.

## Diagnóstico verificado

- Encoder anterior: `Argon2PasswordEncoder(16,32,1,19456,2)`; salt 16 bytes, resultado 32 bytes, paralelismo 1, memória 19456 KiB e duas iterações. Novos cadastros, convites e redefinições continuam gerando exatamente esse formato.
- `AuthenticationService.login` lê usuário por e-mail normalizado, verifica senha/status e chama `SessionTransactions`; a emissão do JWT ocorre depois do commit, como no fluxo anterior.
- `open_auth_session(UUID,BYTEA,TEXT)` bloqueia `users`, verifica ACTIVE e compara o hash esperado antes de inserir sessão/refresh. `CREDENTIAL_CHANGED` continua protegido; não foi removido nem contornado.
- `olius_api` já tem SELECT e UPDATE da coluna password_hash de users. O guard de ADMIN não proíbe a simples atualização desse campo: seus casos sensíveis de UPDATE são mudança de tipo/status. Não foi necessário ampliar permissões.
- Auditoria existente de users exclui valores de password_hash dos snapshots. O contexto do rehash é USER com o titular autenticado e motivo técnico seguro.
- Banco consultado: branch local `feat/legacy-rpa-etl`, revisão `5a9ff74`; scripts presentes na árvore de trabalho. Nenhuma instalação real foi presumida a partir dessa revisão.
- Scripts locais atuais 01–08, índices, triggers, roles e catálogo foram inventariados e pesquisados quanto aos contratos envolvidos. As definições completas de open_auth_session, rotate_auth_refresh_token, issue_password_reset e complete_password_reset foram comparadas com o snapshot da Core e são idênticas. Isso não afirma identidade de todos os arquivos nem verifica implantação nos bancos reais.
- Legado consultado diretamente: [SenhaUtil.java](https://github.com/Olius-Inter/Olius_Backend_Primeiro/blob/main/src/main/java/Organizacao/Utilitarios/SenhaUtil.java), blob `2f1ff5e6a543e11f99f20daa2ee71ade64591d38`.

## Formatos explicitamente aceitos

### Legado

`pbkdf2$120000$<salt Base64 padrão>$<hash Base64 padrão>`

Contrato exato: prefixo minúsculo, iterações decimais `120000` (sem sinal, espaços ou zeros adicionais), salt de 16 bytes e resultado de 32 bytes. Base64 padrão canônico com padding; salt tem 24 caracteres e hash tem 44. Total: 83 caracteres. Não aceitar Base64 URL, prefixos Spring `{pbkdf2}`, outros algoritmos ou parâmetros não confirmados.

A verificação replica `PBEKeySpec(password.toCharArray(),salt,120000,256)` e `PBKDF2WithHmacSHA256` da classe original. Não aplica trim, lowercase, normalização Unicode ou reinterpretação do texto. Comparação usa `MessageDigest.isEqual`. Caracteres acentuados, espaços e caracteres fora do plano básico foram verificados com vetores fictícios independentes e a implementação Java original reproduzida em fixture.

Só o parâmetro conhecido de 120000 é aceito, evitando executar custos arbitrários vindos do banco. Estrutura, tamanho e Base64 são verificados antes do cálculo. Formato desconhecido, malformado ou em texto puro não autentica e não gera rehash.

### Atual

`$argon2id$v=19$m=19456,t=2,p=1$<salt sem padding>$<hash sem padding>`

A compatibilidade aceita o formato exato utilizado pela configuração atual, salt de 16 bytes e hash de 32. Não infere suporte a todos os hashes Argon2 externos. Se houver contas com parâmetros distintos, inventariá-las e aprovar suporte explícito antes da carga, sem reduzir a proteção ou aceitar parâmetros ilimitados.

A política de criação/redefinição não é reaplicada ao login: uma senha legada válida pode não atender à política atual e ainda autenticar. Os limites do DTO de login continuam os existentes, de 1 a 256 caracteres Java; senhas que ultrapassem o limite já existente dependem de uma decisão específica, sem truncamento.

## Fluxo transacional implementado

1. Buscar usuário e capturar uma única vez o hash observado.
2. Validar a senha pelo encoder compatível; usuário ausente usa hash fictício atual, preservando o mecanismo anterior.
3. Recusar senha inválida ou conta inativa com a resposta pública genérica existente.
4. Se o hash já for atual, seguir com a chamada anterior de abertura de sessão usando o hash validado.
5. Se for legado válido, gerar o novo Argon2id fora da transação de escrita, sem manter bloqueio durante o cálculo.
6. Entrar em `openWithCredentialUpgrade`, `REQUIRES_NEW` e `READ_COMMITTED`.
7. Definir autoria de auditoria e executar UPDATE parametrizado condicionado a id, ACTIVE e **hash antigo exato**.
8. Zero linhas atualizadas resulta em rejeição genérica; não reler um hash arbitrário para fingir validação.
9. Com uma linha atualizada, chamar open_auth_session na mesma transação, passando o novo hash gerado por esta autenticação como expected_password_hash.
10. Retorno diferente de OK lança rejeição dentro da transação: rehash e auditoria são desfeitos. Exceção SQL também desfaz tudo.
11. Commit confirma hash, sessão e refresh juntos; só então o fluxo existente gera o JWT e retorna credenciais.

Não reutiliza complete_password_reset como atalho. Não revoga sessões legítimas anteriores apenas por mudar a representação criptográfica da mesma senha. Tokens de recuperação pendentes não são consumidos por esse login; uma recuperação posterior continua podendo alterar a senha e revogar sessões normalmente.

## Disputas e falhas

| Cenário | Resultado |
|---|---|
| Redefinição confirma primeiro | UPDATE condicional não encontra hash antigo; não sobrescreve senha nem abre sessão obsoleta |
| Rehash/login confirma primeiro, depois recuperação | Recuperação atualiza senha e revoga a sessão recém-aberta conforme contrato atual |
| Dois rehashes do mesmo hash | Uma transação vence; a outra rejeita genericamente. O usuário pode repetir o login, então validando Argon2; não há retry automático baseado em credencial não validada |
| Inativação confirma primeiro | Predicado ACTIVE impede rehash e sessão |
| Inativação após login | Rotinas existentes revogam sessões; verificação de sessão continua obrigatória |
| Falha SQL ao atualizar ou inserir sessão/refresh | Rollback sem rehash parcial ou auditoria de atualização confirmada |
| open_auth_session devolve recusa | Rollback da atualização legada |
| Falha posterior ao commit ao assinar JWT ou enviar HTTP | Mantém comportamento anterior: a transação já confirmou. Não promete rollback de transação encerrada; cliente pode repetir o login normal. Não devolver/logar refresh perdido |

## Contrato para liberar a importação

A carga só pode ser liberada após publicação desta compatibilidade, conferência da função de três argumentos e validação em ambiente integrado. O ETL deve:

- Preservar exatamente o hash aprovado de cada usuário; não aplicar strip/lowercase, recodificar Base64, reduzir iterações, gerar hash do hash ou transportar senha em texto puro.
- Incluir hash apenas no caminho protegido de extração/carga, com acesso mínimo. Não expor em XCom, logs do Airflow, amostras de DataFrame, relatórios de rejeição, arquivos públicos ou artefatos versionados. Se houver staging, definir acesso, retenção e descarte.
- Validar formato e parâmetros antes de carregar. Ausência/formato inválido é pendência/rejeição controlada para o responsável da migração, nunca fallback em texto puro.
- Respeitar elegibilidade, identificadores, especializações e unicidades do banco. Suporte do encoder não aprova importação de ADMIN nem provisionamento fora de bootstrap/convites.
- **Nunca sobrescrever credencial de conta já existente** em reexecuções/upserts, especialmente após primeiro login ou recuperação. Uma conta já migrada é identificada pelo mapeamento estável da carga; nova tentativa não deve restaurar PBKDF2 sobre Argon2.
- Conflito entre conta legada e atual (e-mail, CPF/CNPJ ou identidade) exige regra de conciliação aprovada, não vinculação ou troca automática de senha.
- Confirmar transações e auditoria da carga com o responsável do banco, sem atribuir a uma conta da Core privilégios do ETL.
- Homologar com conta fictícia elegível completa: carga -> primeiro login -> hash atual -> sessão válida -> segundo login -> recuperação -> reexecução ETL sem regressão do hash.

A inspeção limitada de `PostgreSQL/legacy_rpa_etl/pipeline.py` confirmou a exclusão da coluna `senha` na seleção das colunas (linha 68), também descrita no README; esse diretório estava não versionado na cópia consultada. Não foi feita auditoria integral da pipeline nem da carga/dados reais, e nenhum arquivo do ETL foi alterado. O relato de seis cidadãos e três administradores é contexto recebido, não uma consulta aos dados atuais.

## Banco e implantação

Nenhum script, migration, role, tabela ou assinatura SQL foi alterado. Não há dependência de tabela nova. Verificar antes de publicar: usuários com password_hash VARCHAR(255), UPDATE restrito existente, auditoria instalada e open_auth_session(uuid,bytea,text) com comparação exata e bloqueio do titular.

Implantar Core e validar compatibilidade antes de habilitar extração/carga de hashes. Não voltar a uma versão que só lê Argon2 enquanto existirem contas legadas importadas sem procedimento de recuperação/compatibilidade. Não existe endpoint novo nem variável obrigatória adicional.

## Organização sugerida das tarefas

Consulta em 02/11/2026: [Sprint 2 — Identidade e segurança](https://app.clickup.com/t/86e3dh6d3) está deployed e contém autenticação/PasswordEncoder; [Sprint 12 — Estabilização e entrega](https://app.clickup.com/t/86e3dj2n0) está in development e prevê homologação integrada. Suas datas descritas ainda mencionam outubro e precisam ser replanejadas pela equipe; não foram alteradas.

Recomendação: uma tarefa complementar **Compatibilidade de credenciais legadas no login**, relacionada à identidade, com esta implementação/testes; tarefa distinta de **ETL protegido de hashes e preservação em reexecuções**, no trabalho da migração; e cenário de homologação na Sprint 12. Não é necessário recriar login, recuperação de senha ou uma sprint completa de segurança. O card de RPA consultado tem descrição genérica de coleta/transformação e não documenta este contrato; não presumir que já cobre a carga de credenciais.

Não foram criados/alterados cards, comentários ou sprints no ClickUp. A busca disponível não comprova inexistência de tarefas em outras listas; conferir com o responsável antes de cadastrar tarefas novas.

## Arquivos e responsabilidades

| Arquivo | Alteração |
|---|---|
| security/LegacyCompatiblePasswordEncoder.java | Leitura explícita dos dois formatos, validação defensiva e escrita atual |
| config/CryptographyConfiguration.java | Conecta o encoder compatível ao Argon2id já utilizado |
| service/AuthenticationService.java | Seleciona abertura normal ou atualização coordenada após validação |
| service/SessionTransactions.java | Atualização e abertura de sessão na mesma transação com rollback |
| repository/CredentialUpgradeRepository.java | UPDATE condicional parametrizado, sem alterar privilégios |
| security/LegacyCompatiblePasswordEncoderTest.java | Vetores, Unicode/espaços, formato atual e entradas inválidas |
| LegacyPasswordIntegrationTest.java | PostgreSQL descartável com role restrita, sessão válida, auditoria, falhas e concorrência |
| testfixture/LegacyPasswords.java | Reprodução isolada do algoritmo original para dados fictícios |

Documentação adicional criada/atualizada: `olius-api/docs/compatibilidade-senhas-legadas.md`, `Specs/05-compatibilidade-senhas-legadas/SPEC-AUTH-001-compatibilidade-senhas-legadas.md`, `Specs/README.md` e `Specs/DECISOES_E_PENDENCIAS.md`. Os dois arquivos locais anteriores de primeiro ADMIN foram preservados, sem alterações, e não fazem parte desta entrega.

Caminhos Java relativos a src/main/java/br/com/olius/olius_core_api ou src/test/java/br/com/olius/olius_core_api, conforme o arquivo.

## Evidências de validação

A primeira execução dirigida passou com 32 testes. Após acrescentar os cenários de falha real de constraint e retorno inválido real da função, a suíte completa `mvnw -o verify` terminou com **BUILD SUCCESS: 632 testes, zero falhas, zero erros e zero ignorados**, incluindo 18 casos do encoder e 15 de integração da nova feature. JAR e relatório JaCoCo gerados. PostgreSQL descartável 16.15 com conexão da Core sem superusuário, Java 21; nenhuma conexão com bancos reais nesta homologação. A execução durou 9min31s. Os avisos de conexões de contextos de teste cujos containers já encerraram não resultaram em falhas; não foi alterada a infraestrutura existente para silenciá-los.

CI/Sonar remotos, publicação da Core, carga ETL e homologação com dados migrados ainda não estão comprovados por essa execução local.
