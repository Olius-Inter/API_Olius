# Docker local

Execute os comandos abaixo na pasta `olius-api`, com Docker Desktop iniciado.

## Criar a imagem (não exige .env)

```powershell
docker build --platform linux/amd64 -t olius-core:v1 .
docker run --rm --entrypoint java olius-core:v1 -version
```

O build compila a aplicação em um estágio separado. A imagem final contém o JRE
21 e o JAR. A aplicação executa com usuário sem privilégios. Os testes devem ser executados
antes do build com `.\mvnw.cmd -B verify`; não são executados durante o build.
O `.dockerignore` exclui `.env`, chaves e arquivos locais do contexto.

## Executar com o banco já preparado

Copie `.env.docker.example` para `.env.docker` e preencha estas três variáveis,
com valores literais e sem aspas:

```properties
CONTAINER_DEV_URL=jdbc:postgresql://host.docker.internal:5433/olius_dev
CONTAINER_DEV_USER=olius_core_api
CONTAINER_DEV_PASSWORD=p\ass$parte
```

Os valores acima são exemplos. `DOCKER_CORE_DB_URL` não é convertido pelo
`docker run`: essa conversão é feita somente pelo Compose.
Não utilize `.env.compose` neste comando nem no perfil local do Spring:
as aspas simples podem ser interpretadas como parte do valor nesses consumidores.
Não use também o `.env` do Spring neste comando: ele tem outra codificação.

### Formatos dos arquivos de ambiente

Os exemplos de senha abaixo são fictícios. Para a mesma senha literal
`p\ass$parte`, cada consumidor exige seu próprio arquivo:

| Consumidor | Arquivo | Atribuição |
|---|---|---|
| Spring local, leitura como `.properties` | `.env` | `CONTAINER_DEV_PASSWORD=p\\ass$parte` |
| Docker, leitura literal | `.env.docker` | `CONTAINER_DEV_PASSWORD=p\ass$parte` |
| Compose, leitura com interpolação | `.env.compose` | `CONTAINER_DEV_PASSWORD='p\ass$parte'` |

O Spring interpreta escapes de `.properties`: uma barra simples antes de `a`
é descartada. O `docker run --env-file` preserva a barra. Por isso, não copie os
arquivos preenchidos entre consumidores. Todos os arquivos reais estão ignorados
pelo Git e pelo contexto de build; somente os modelos são versionados.

Para PostgreSQL no Windows, substitua
`localhost` por `host.docker.internal` na URL usada pelo container, mantendo a
porta publicada pelo banco. Exemplo: `jdbc:postgresql://host.docker.internal:5433/olius_dev`.
Se o banco for remoto, utilize seu endereço real. Não execute scripts de
inicialização nem recrie o volume do banco existente.

```powershell
docker run --rm --name olius-core-local -p 127.0.0.1:8080:8080 --env-file .env.docker -e SPRING_PROFILES_ACTIVE=local olius-core:v1
```

O perfil `local` usa chaves JWT temporárias e cookies HTTP para desenvolvimento.
Ele não monta nem usa automaticamente as chaves persistentes da pasta `secrets`.
O `.env.docker` fornece variáveis em tempo de execução, sem ser incluído na imagem.

Em outro terminal, aguarde a mensagem `Started OliusApiApplication` nos logs do
container antes de consultar. Se houver falha na inicialização, corrija a causa:

```powershell
docker logs --follow --tail 100 olius-core-local
```

Após a mensagem de inicialização, pressione Ctrl+C para sair apenas do
acompanhamento dos logs. Então execute:

```powershell
curl.exe -i http://localhost:8080/api/v1/establishment-types
docker stop olius-core-local
```

Uma resposta 200 na consulta pública e os logs de inicialização ajudam a validar
a conexão. Cadastro, login e operações de escrita exigem autorização separada.
Sem credenciais, a aplicação não fica funcional: `java -version` verifica somente
o runtime do container, não a conexão com o banco.

## Alternativa: Compose somente da API

O arquivo `compose.api.yaml` executa apenas a Core. Ele é independente do
`compose.yaml` do banco e não cria nem reinicia PostgreSQL.

Copie `.env.compose.example` para `.env.compose` e preencha a conexão nesse
arquivo exclusivo do Compose, preservando `.env.docker` para execução direta
e `.env` para o perfil local do Spring. Use aspas simples na senha para manter
`$` literal, evitando que o Compose tente interpolar partes do segredo:

```properties
DOCKER_CORE_DB_URL=jdbc:postgresql://host.docker.internal:5433/olius_dev
CONTAINER_DEV_USER=olius_core_api
CONTAINER_DEV_PASSWORD='segredo$parte'
```

Os valores acima são exemplos: ajuste host, porta, banco, usuário e senha.
O Compose exige os três campos antes de iniciar. Ele converte
`DOCKER_CORE_DB_URL` em `CONTAINER_DEV_URL` e injeta somente a conexão e as
origens CORS; as chaves temporárias e os cookies HTTP continuam sendo definidos
pelo perfil local. Não versione `.env.compose`; ele está ignorado pelo Git e
pelo Docker. Variáveis exportadas no terminal têm precedência sobre o arquivo:
remova valores antigos do ambiente antes de executar.

```powershell
docker compose --env-file .env.compose -f compose.api.yaml config --quiet
docker compose --env-file .env.compose -f compose.api.yaml up -d --build
docker compose --env-file .env.compose -f compose.api.yaml logs --follow --tail 100 core
```

`up -d` inicia em segundo plano, mas não aguarda o Spring ficar pronto. Espere
`Started OliusApiApplication` nos logs; depois pressione Ctrl+C, sem encerrar o
container, e só então consulte:

```powershell
curl.exe -i http://localhost:8080/api/v1/establishment-types
docker compose --env-file .env.compose -f compose.api.yaml down
```

Use `--env-file .env.compose -f compose.api.yaml` em todos esses comandos.
O `down` acima remove somente os recursos deste projeto da API. Não use o
arquivo do banco para esta etapa. Não execute a alternativa `docker run`
simultaneamente: ambos usam a porta 8080. Não publique a saída de `config`
sem `--quiet`, pois ela pode conter senhas. Configurações de SMTP e pagamentos
não são fornecidas por este Compose básico.

### Senha administrativa do banco de desenvolvimento

O `compose.yaml` do PostgreSQL usa `.env` por padrão. Nesse caminho, não use `$`
em `CONTAINER_DEV_ADMIN_PASSWORD`: a interpolação pode alterar parte da senha,
e `:?` não identifica a alteração. Não adicione aspas ao `.env` do Spring.

Se precisar de `$` na senha administrativa para inicializar um volume novo,
adicione ao arquivo exclusivo `.env.compose` as variáveis abaixo, sem repetir
chaves já existentes:

```properties
CONTAINER_DEV_NAME=olius_dev
CONTAINER_DEV_ADMIN_USER=olius_dev_admin
CONTAINER_DEV_ADMIN_PASSWORD='p\ass$parte'
```

Use explicitamente `docker compose --env-file .env.compose -f compose.yaml`
nos comandos do banco. Antes de iniciar, valide com esse mesmo comando seguido
de `config --quiet`. Este fluxo é só para inicialização planejada de banco:
não execute `up` nem remova volumes do banco já preparado para testar senhas.
Alterar o arquivo não troca a senha de um volume existente.

## Teste reproduzível da imagem com banco descartável

Na pasta `olius-api`, execute com PowerShell 7 e Docker Desktop iniciado:

```powershell
pwsh -NoProfile -File scripts/Test-DockerSmoke.ps1
```

O script constrói uma imagem própria do teste, cria uma rede e um PostgreSQL
descartável, aplica os scripts oficiais de `src/test/resources/db/olius` na
mesma ordem do fixture `OfficialPostgres` e cria um login exclusivo com o grupo
`olius_api`, sem privilégios administrativos. A API usa esse login no perfil
local e uma porta aleatória publicada apenas no localhost. O teste aguarda
`GET /api/v1/establishment-types` retornar HTTP 200 e confirma a mensagem de
inicialização do Spring. Não utiliza `.env` ou bancos reais.

As credenciais são geradas em memória e os containers, a rede, o volume anônimo
do PostgreSQL e a imagem exclusiva são removidos no encerramento normal ou
em erro pelo bloco `finally`. Se o processo for terminado à força, confira os
recursos com prefixo `olius-smoke-` antes de removê-los manualmente.

Para repetir em outra versão da matriz de compatibilidade:

```powershell
pwsh -NoProfile -File scripts/Test-DockerSmoke.ps1 -PostgresImage postgres:18.6
```

Esse teste complementa a suíte Java: build da imagem, inicialização real no
container, validação do schema pelo Hibernate e consulta pública pelo usuário
limitado. Ele não substitui os testes Java nem valida a configuração de produção.

## AWS posteriormente

Use o perfil `prod`, as variáveis `DB_URL`, `DB_USER` e `DB_PASSWORD` e monte as
chaves JWT por Secret Kubernetes, configurando seus caminhos com
`OLIUS_JWT_PRIVATE_KEY_PATH` e `OLIUS_JWT_PUBLIC_KEY_PATH`.
Não copie o `.env` nem as chaves para a imagem.
