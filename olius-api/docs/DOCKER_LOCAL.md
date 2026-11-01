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

Para `docker run --env-file .env`, preencha estas três variáveis no `.env`,
com valores sem aspas:

```properties
CONTAINER_DEV_URL=jdbc:postgresql://host.docker.internal:5433/olius_dev
CONTAINER_DEV_USER=olius_core_api
CONTAINER_DEV_PASSWORD=segredo$parte
```

Os valores acima são exemplos. `DOCKER_CORE_DB_URL` não é convertido pelo
`docker run`: essa conversão é feita somente pelo Compose.
Não utilize `.env.compose` neste comando nem no perfil local do Spring:
as aspas simples podem ser interpretadas como parte do valor nesses consumidores.

Para PostgreSQL no Windows, substitua
`localhost` por `host.docker.internal` na URL usada pelo container, mantendo a
porta publicada pelo banco. Exemplo: `jdbc:postgresql://host.docker.internal:5433/olius_dev`.
Se o banco for remoto, utilize seu endereço real. Não execute scripts de
inicialização nem recrie o volume do banco existente.

```powershell
docker run --rm --name olius-core-local -p 127.0.0.1:8080:8080 --env-file .env -e SPRING_PROFILES_ACTIVE=local olius-core:v1
```

O perfil `local` usa chaves JWT temporárias e cookies HTTP para desenvolvimento.
Ele não monta nem usa automaticamente as chaves persistentes da pasta `secrets`.
O `.env` é fornecido em tempo de execução, sem ser incluído na imagem.

Em outro terminal:

```powershell
curl.exe -i http://localhost:8080/api/v1/establishment-types
docker logs --tail 100 olius-core-local
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
arquivo exclusivo do Compose, preservando o `.env` sem aspas para execução
direta e para o perfil local do Spring. Use aspas simples na senha para manter
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
docker compose --env-file .env.compose -f compose.api.yaml logs --tail 100 core
curl.exe -i http://localhost:8080/api/v1/establishment-types
docker compose --env-file .env.compose -f compose.api.yaml down
```

Use `--env-file .env.compose -f compose.api.yaml` em todos esses comandos.
O `down` acima remove somente os recursos deste projeto da API. Não use o
arquivo do banco para esta etapa. Não execute a alternativa `docker run`
simultaneamente: ambos usam a porta 8080. Não publique a saída de `config`
sem `--quiet`, pois ela pode conter senhas. Configurações de SMTP e pagamentos
não são fornecidas por este Compose básico.

## AWS posteriormente

Use o perfil `prod`, as variáveis `DB_URL`, `DB_USER` e `DB_PASSWORD` e monte as
chaves JWT por Secret Kubernetes, configurando seus caminhos com
`OLIUS_JWT_PRIVATE_KEY_PATH` e `OLIUS_JWT_PUBLIC_KEY_PATH`.
Não copie o `.env` nem as chaves para a imagem.
