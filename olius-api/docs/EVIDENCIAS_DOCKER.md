# Evidências da execução Docker

Validação local realizada em 01/11/2026 com
`pwsh -NoProfile -File scripts/Test-DockerSmoke.ps1`.

## Resultado observado

- Build da imagem da branch concluído com sucesso para `linux/amd64`.
- PostgreSQL 16.15 descartável, isolado em rede exclusiva.
- Instalação dos scripts oficiais do snapshot em `src/test/resources/db/olius`,
  na ordem utilizada pelo fixture `OfficialPostgres`, incluindo catálogo e roles.
- Login `olius_smoke_api` com membership em `olius_api`, sem SUPERUSER,
  CREATEDB, CREATEROLE, REPLICATION ou BYPASSRLS.
- Inicialização da imagem com perfil `local` e validação do schema pelo Hibernate.
- Mensagem `Started OliusApiApplication` confirmada nos logs.
- Consulta HTTP pública executada após a inicialização e retornando 200.
- Remoção dos recursos exclusivos do teste ao final. Nenhum banco real utilizado.

Saída final, sem credenciais:

```text
SMOKE OK: postgres:16.15; esquema oficial; usuário limitado; Spring iniciado; GET /api/v1/establishment-types HTTP 200.
```

## Alcance

Este teste verifica a imagem em execução, não apenas a compilação ou os testes
Java. Não comprova o deploy no EKS, TLS, chaves persistentes ou perfil `prod`.
A matriz do workflow Java contempla PostgreSQL 16.15 e 18.6. O resultado de
599 testes aprovados em cada versão foi informado na revisão da PR; essa suíte
não foi reexecutada nesta validação Docker. A evidência local acima corresponde
ao smoke test com PostgreSQL 16.15.
