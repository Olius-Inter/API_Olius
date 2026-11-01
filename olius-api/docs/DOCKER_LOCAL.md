# Docker local

Execute os comandos abaixo na pasta `olius-api`, com Docker Desktop iniciado.

## Criar a imagem (não exige .env)

```powershell
docker build --platform linux/amd64 -t olius-core:v1 .
docker run --rm --entrypoint java olius-core:v1 -version
```

O build compila a aplicação em um estágio separado. A imagem final contém o JRE
21 e o JAR e executa com usuário sem privilégios. Os testes devem ser executados
antes do build com `.\mvnw.cmd -B verify`; não são executados durante o build.
O `.dockerignore` exclui `.env`, chaves e arquivos locais do contexto.

## Executar com o banco já preparado

Preencha o `.env` antes de executar. Para PostgreSQL no Windows, substitua
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

O arquivo `compose.api.yaml` executa apenas a Core. Ele e independente do
`compose.yaml` do banco e nao cria nem reinicia PostgreSQL.

Adicione ao `.env` a URL acessivel pelo container, preservando a URL usada para
executar a API diretamente no Windows:

```properties
DOCKER_CORE_DB_URL=jdbc:postgresql://host.docker.internal:5433/olius_dev
```

O endereco acima e um exemplo: ajuste host, porta e banco. Preencha tambem
`CONTAINER_DEV_USER` e `CONTAINER_DEV_PASSWORD`. O Compose exige os tres campos
antes de iniciar. Ele injeta somente a conexao e as origens CORS; as chaves
temporarias e os cookies HTTP continuam sendo definidos pelo perfil local.

```powershell
docker compose -f compose.api.yaml config --quiet
docker compose -f compose.api.yaml up -d --build
docker compose -f compose.api.yaml logs --tail 100 core
curl.exe -i http://localhost:8080/api/v1/establishment-types
docker compose -f compose.api.yaml down
```

Use `-f compose.api.yaml` em todos esses comandos. O `down` acima remove somente
os recursos deste projeto da API. Nao use o arquivo do banco para esta etapa.
Nao execute a alternativa `docker run` simultaneamente: ambos usam a porta 8080.
Nao publique a saida de `config` sem `--quiet`, pois ela pode conter senhas.
Configuracoes de SMTP e pagamentos nao sao fornecidas por este Compose basico.

## AWS posteriormente

Use o perfil `prod`, as variáveis `DB_URL`, `DB_USER` e `DB_PASSWORD` e monte as
chaves JWT por Secret Kubernetes, configurando seus caminhos com
`OLIUS_JWT_PRIVATE_KEY_PATH` e `OLIUS_JWT_PUBLIC_KEY_PATH`.
Não copie o `.env` nem as chaves para a imagem.
