# OLIUS — Retomada da sprint administrativa

Atualizado em: 25/10/2026.
**Estado: INCOMPLETA — aguardando retomada por decisão do responsável pelo projeto.**
Branch de trabalho: `feat/admin-invitations`.
Raiz do repositório: `API_Olius`; módulo da Core: `olius-api`.

## 1. Por que este registro existe

O desenvolvimento desta sprint foi interrompido para trabalhar em outra demanda.
Não marcar a sprint como concluída nem presumir que envio real de e-mail,
frontend ou primeiro administrador já foram homologados. Este arquivo é um
registro de continuidade, não substitui a documentação de segurança ou as regras
funcionais aprovadas. Ao retomar, conferir código, Git e ambiente novamente.

## 2. Escopo e entregas existentes

A funcionalidade foi dividida em duas partes: núcleo administrativo e entrega
real dos convites. A primeira foi implementada; a segunda está parcialmente pronta.

### Parte 1 — núcleo implementado

- Integração com as rotinas PostgreSQL de convites e proteção do último ADMIN ativo.
- Emissão/reenvio, listagem, consulta e revogação de convites por ADMIN autorizado.
- Aceite público com token e definição da senha; sem login automático.
- Validade de 24 horas; nova emissão invalida o segredo do convite anterior.
- Banco recebe somente o hash do token; segredo não aparece em listagens/respostas.
- Criação direta de ADMIN pela API e bootstrap automático anterior removidos.
- Testes de permissões, concorrência, autoria, aceite e último administrador.

Commits preservados na branch administrativa e já publicados anteriormente:

- `a2a3ad7` — test(database): atualiza fixture para o contrato administrativo
- `30e6c10` — feat(admin): implementa convites e integra proteção do último administrador
- `496052d` — test(admin): valida convites, permissões e concorrência

A existência desses commits não comprova merge na main. Na conferência desta
retomada, a branch administrativa local continua em 496052d.

### Parte 2 — canal SMTP implementado, sem homologação real

- Dependência Spring Boot Mail no pom.xml.
- `AdminInvitationEmailProperties`: configurações do canal e da página de aceite.
- `AdminInvitationEmailConfiguration`: habilitação explícita e validação da configuração.
- `SmtpAdminInvitationDelivery`: mensagem de texto e envio por JavaMailSender.
- Configuração padrão para Gmail SMTP, porta 587, autenticação, STARTTLS obrigatório,
  verificação do hostname TLS e timeouts de 5000 ms.
- Canal desativado por padrão; não testa conexão com Gmail na inicialização.
- Sem canal disponível, emissão retorna 503 antes de persistir o convite.
- Com configuração habilitada e incompleta, a aplicação falha na inicialização.
- Envio ocorre após commit; falha externa não desfaz o convite e retorna erro
  ADMIN_INVITATION_DELIVERY_FAILED. Não existe retry oculto.
- Mensagem usa link da página do frontend com fragmento `#token=<segredo>`.
- Atualizados `.env.example`, application.properties e a documentação de segurança.

Nenhum e-mail real foi enviado nesta etapa. O .env real não foi alterado e as
credenciais Gmail não foram lidas ou verificadas. Não presumir que elas estejam
preenchidas, que a senha de app já funcione ou que o destinatário recebeu mensagem.

## 3. Validação já realizada

Em 25/10/2026, `mvnw verify` passou em uma cópia isolada do módulo:
**144 testes, zero falhas, zero erros e zero ignorados**.

Novos testes de e-mail (20 casos):

- `AdminInvitationEmailConfigurationTest`: canal desativado, campos obrigatórios,
  remetente e URLs inválidas, HTTP local fora de produção.
- `SmtpAdminInvitationDeliveryTest`: conteúdo/link, impedimento de envio dentro de
  transação e propagação de falha sem retry.
- `SmtpInvitationTransportTest`: transporte real do cliente SMTP para servidor
  mínimo de teste em loopback; sem conexão ao Gmail ou destinatários reais.

Os testes administrativos existentes continuam cobrindo commit antes da entrega,
falha após commit, convites, autorização e proteção do último ADMIN.

Esses resultados não comprovam implantação ou homologação no Gmail, frontend ou
Aiven. Os testes usaram PostgreSQL descartável, não o banco de desenvolvimento.

## 4. Configuração pendente

No `.env` local ignorado pelo Git, conferir/preencher sem compartilhar valores:

```properties
OLIUS_ADMIN_INVITATION_EMAIL_ENABLED=false
OLIUS_MAIL_HOST=smtp.gmail.com
OLIUS_MAIL_PORT=587
OLIUS_MAIL_USERNAME=
OLIUS_MAIL_PASSWORD=
OLIUS_MAIL_FROM=
OLIUS_ADMIN_INVITATION_ACCEPTANCE_URL=
```

- USERNAME: endereço completo da conta Gmail do OLIUS.
- PASSWORD: senha de app do Google, não a senha normal da conta; sem espaços de apresentação.
- FROM: inicialmente, o mesmo endereço autenticado.
- ACCEPTANCE_URL: página do frontend, NÃO o endpoint POST da Core.
- Manter ENABLED=false até a configuração estar pronta; habilitar explicitamente
  para a homologação autorizada. Nunca preencher segredos no .env.example.
- Produção recebe variáveis pela hospedagem; o perfil prod não importa o .env local.

A URL final **ainda não foi definida**. Deve ser absoluta, HTTPS e sem credenciais,
query ou fragmento. Para desenvolvimento, HTTP em loopback é aceito fora de prod.
O backend acrescenta o fragmento com o token. Não usar URL fictícia como evidência
de integração concluída.

## 5. Contrato a fechar com o frontend

- Implementar a página pública de aceite na URL acordada.
- Ler o segredo de `#token=...`, removê-lo da barra/histórico após captura e não
  enviá-lo a logs, analytics ou armazenamento persistente.
- Mostrar senha e confirmação; verificar igualdade no frontend.
- Chamar `POST /api/v1/auth/admin-invitations/acceptance` com somente token e password.
- Em 204, encaminhar ao login; aceite não abre sessão automaticamente.
- Tratar erro de convite inválido/expirado/revogado/consumido conforme Problem Details.
- Alinhar o formato de fragmento antes de homologar: não presumir frontend implementado.

O modal e a gestão de convites são telas administrativas; revogar um convite não
inativa uma conta já criada. Conta administrativa existente é tratada pela gestão
de status, respeitando o último ADMIN ativo.

## 6. Checklist para concluir a sprint

- [ ] Conferir o estado atual da branch, dos scripts oficiais e dos ambientes.
- [ ] Fechar URL e contrato da página de aceite com o frontend.
- [ ] Implementar/homologar a página de aceite e suas respostas.
- [ ] Configurar Gmail e senha de app no ambiente adequado, sem versionar segredos.
- [ ] Concluir o procedimento operacional seguro de emissão do convite do primeiro ADMIN.
- [ ] Validar a criação inicial e o aceite sem endpoint público de bootstrap.
- [ ] Homologar emissão → recebimento do e-mail → aceite → login.
- [ ] Homologar reenvio, revogação, expiração, token consumido e falha de entrega.
- [ ] Confirmar proteção do último ADMIN ativo no ambiente homologado.
- [ ] Atualizar evidências/documentação, revisar, commitar e integrar a PR.

O bootstrap deve usar o mecanismo oficial restrito do PostgreSQL com a conta de
implantação autorizada. Não liberar a função de bootstrap à Core, não criar senha
provisória por atalho e não restaurar criação direta de ADMIN por endpoint.
Se já houver administradores, respeitar as condições oficiais de bootstrap e
recuperação; não contorná-las criando outra conta sem análise.

## 7. Estado do Git no momento do registro

O código SMTP da segunda parte foi preservado no commit `6096881`
(`feat(admin): implementa entrega de convites por SMTP`). A documentação e este
registro acompanham o commit documental seguinte. **Nenhum desses dois commits foi
publicado nesta etapa.** O conjunto de arquivos da segunda parte inclui:

```text
olius-api/pom.xml
olius-api/.env.example
olius-api/src/main/resources/application.properties
olius-api/src/main/java/br/com/olius/olius_core_api/service/AdminInvitationDelivery.java
olius-api/src/main/java/br/com/olius/olius_core_api/config/AdminInvitationEmailProperties.java
olius-api/src/main/java/br/com/olius/olius_core_api/config/AdminInvitationEmailConfiguration.java
olius-api/src/main/java/br/com/olius/olius_core_api/service/SmtpAdminInvitationDelivery.java
olius-api/src/test/java/br/com/olius/olius_core_api/AdminInvitationEmailConfigurationTest.java
olius-api/src/test/java/br/com/olius/olius_core_api/SmtpAdminInvitationDeliveryTest.java
olius-api/src/test/java/br/com/olius/olius_core_api/SmtpInvitationTransportTest.java
olius-api/docs/identidade-seguranca.md
RETOMADA_SPRINT_ADMIN.md
```

O .env.example já tinha alterações anteriores da Stripe; não atribuir essas linhas
à sprint administrativa nem incluí-las inadvertidamente no mesmo commit.

O trabalho administrativo foi preservado em dois commits locais: implementação e
documentação. Antes de mudar de branch, conferir git status: as linhas da Stripe
no .env.example permanecem fora desses commits e precisam de preservação própria.
O registro não publica código nem marca a sprint como concluída.

A main já recebeu a correção de auditoria e os contratos B2C em outras PRs. A branch
administrativa consultada ainda tem sua base anterior: antes de publicar a segunda
parte, conciliar as atualizações da main, especialmente fixture SQL, manifesto,
testes e documentação. Não reintroduzir as funções antigas de sessão nem descartar
as decisões B2C ao resolver conflitos.

## 8. Primeiro passo ao retomar

Ler este arquivo e `olius-api/docs/identidade-seguranca.md`, conferir `git status`
e os commits atuais. Então retomar o fechamento do frontend/configuração e o
procedimento do primeiro administrador; não reimplementar o núcleo já existente.

Este registro não autoriza envio de mensagens reais sem definir destinatário e
ambiente de homologação, nem marca a sprint como entregue.
