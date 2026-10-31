# Sprint 11 — Certificados e consultas finais

## Finalidade

O certificado ambiental reconhece uma meta acumulada de óleo realmente coletado de um estabelecimento. Não é comprovante de pagamento, pontuação do ranking ou documento por coleta. A Core permite consultar os certificados e acompanhar a distância até cada meta. Não há geração de PDF.

O PostgreSQL é a fonte de verdade. As rotinas existentes de registro, correção, anulação e restauração de coletas conciliam os certificados na mesma transação da operação. A API desta sprint somente consulta: nenhuma requisição GET emite, reativa ou revoga certificados.

## Regras e ciclo de vida

- O volume elegível é a soma de `collection.collected_volume_liters` dos registros `RECORDED` do estabelecimento, inclusive coletas malsucedidas que efetivamente recolheram óleo.
- Não entram entregas B2C, volume apresentado, reserva de franquia, perdas por cancelamento ou pontos. O acumulado não reinicia a cada ano ou ciclo de assinatura.
- Os níveis vêm do catálogo oficial `certificate_level`, ordenados por meta, nome e ID. Não há metas criadas pela aplicação nem endpoint para editar níveis.
- Alcançar uma meta permite a emissão pelas rotinas existentes. Uma correção ou anulação pode reduzir o volume e revogar o certificado. Se a meta voltar a ser alcançada, a rotina reativa o mesmo certificado, preservando ID, código e data original de emissão.
- `status` define o estado atual. `revokedAt` pode continuar preenchido após uma reativação; não deve ser usado isoladamente para determinar validade.
- Nome, descrição, meta e imagem do nível são os metadados atuais do catálogo, não um snapshot histórico do momento da emissão.
- Sem níveis cadastrados, o catálogo e os níveis do progresso ficam vazios, mas o progresso continua informando o volume real acumulado.
- Carregar o catálogo depois de já existirem coletas não concilia automaticamente os registros anteriores. A resposta pode mostrar `reached=true` e `certificateId=null`. Isso não é um certificado emitido. Uma eventual conciliação retroativa deve ser conduzida pelo responsável do banco com suas rotinas existentes; a Core não recebe novos privilégios para isso.

## Autorização

Todas as rotas exigem autenticação e conta ativa. O estabelecimento consulta seus próprios dados; ADMIN pode consultar todos os estabelecimentos. Outro estabelecimento recebe 404 ao tentar consultar um certificado alheio. Cidadãos não recebem acesso a estas rotas B2B. Motoristas não possuem login.

A consulta independe de assinatura vigente. O fim da assinatura não apaga certificados nem impede sua consulta por uma conta ativa. Isso não libera novas solicitações ou benefícios comerciais: essas regras continuam nos respectivos serviços.

## Endpoints GET

| Rota | Quem pode usar | Resultado |
|---|---|---|
| `/api/v1/certificate-levels` | ESTABLISHMENT ou ADMIN | Lista do catálogo atual |
| `/api/v1/certificates` | ESTABLISHMENT | Página dos próprios certificados |
| `/api/v1/certificates/{id}` | Titular ou ADMIN | Certificado e nível atuais |
| `/api/v1/establishments/me/certificate-progress` | ESTABLISHMENT | Volume acumulado e progresso por nível |
| `/api/v1/admin/certificates` | ADMIN | Página administrativa; filtro opcional `establishmentId` |
| `/api/v1/admin/establishments/{id}/certificate-progress` | ADMIN | Progresso de um estabelecimento; inexistente retorna 404 |
| `/api/v1/establishments/me/environmental-summary` | ESTABLISHMENT | Indicadores das próprias coletas |
| `/api/v1/admin/environmental-summary` | ADMIN | Indicadores B2B e B2C separados |

### Filtros e paginação

Listas de certificados aceitam `status` (`ACTIVE` ou `REVOKED`), `levelId`, `issuedFrom`, `issuedUntil`, `page` e `size`. O padrão é página 0 com 20 itens; tamanho permitido de 1 a 100. Sem filtro de estado, certificados revogados também aparecem, identificados pelo estado. Ordem estável: `issuedAt DESC, id DESC`.

O intervalo de emissão é `[issuedFrom, issuedUntil)`: início incluído, fim excluído. Esses filtros usam a emissão original, não a data da última reativação. Intervalo invertido, enum desconhecido ou paginação inválida retornam 400. Página além do final retorna lista vazia e o total correspondente.

### Consultas ambientais

Aceitam `from` e `until` no formato ISO 8601 com fuso. Usam intervalo `[from, until)` na data física da coleta ou entrega; sem limites consultam todo o período. Consideram somente registros atualmente `RECORDED`; após correções os resultados podem mudar. Não representam um arquivo histórico imutável.

`collections` contém quantidade de coletas, sucessos, falhas, litros reais e quantidade de estabelecimentos atendidos. Na consulta do próprio estabelecimento, `deliveries` é `null`: ser responsável por PEV não torna as entregas dos cidadãos parte do seu volume B2B.

Na consulta administrativa, `deliveries` informa quantidade de entregas, litros aceitos, cidadãos distintos e PEVs distintos. **Não some os volumes B2B e B2C para representar um volume ambiental único**: um mesmo óleo pode passar pelos dois fluxos. Não são calculadas equivalências de CO₂, água ou árvores.

## Organização técnica

Controllers definem rotas, validação estrutural e perfis. Services verificam o ator ativo e restringem o titular; não aceitam uma identidade de proprietário enviada pelo estabelecimento. Repositories executam consultas parametrizadas com a conexão e o papel restrito já existentes da Core.

As consultas usam transações somente de leitura com `REPEATABLE_READ`. Assim, contagem, página, volume e metas de uma mesma resposta pertencem ao mesmo retrato do banco, mesmo se outra transação alterar uma coleta durante a consulta. O progresso usa `get_certificate_progress`; a soma equivalente preserva o volume quando não há níveis. Não há leitura dos logs de auditoria nem chamada direta da conciliação interna.

A paginação das entregas por PEV também passa a usar esse isolamento, corrigindo a pendência de consistência entre itens e contagem da revisão B2C anterior. O limite B2C de 999,99 litros permanece igual.

## Escopo final do MVP

A decisão aprovada para esta sprint substitui as menções anteriores a relatórios e comprovantes comerciais: não são funcionalidades implementadas. Permanecem fora do MVP:

1. Histórico administrativo completo dos eventos de certificados.
2. Emissão e persistência de relatórios ou comprovantes comerciais.
3. Arquivo e recuperação de documentos comerciais após fim da assinatura.
4. Geração de benefícios documentais condicionada a plano pago.
5. Rastreamento de documentos comerciais afetados por correções.

Os logs existentes continuam preservados. Não foram alterados scripts, permissões, metas, schema ou bancos reais. Não há novas credenciais nem integração externa nesta sprint. JSON de consulta e informações de uma coleta não devem ser apresentados como um documento comercial formal emitido.

## Validação

`CertificateIntegrationTest` executa o schema oficial em PostgreSQL descartável e conecta a aplicação com `OfficialPostgres.API_USER`, sem superusuário. Cobre metas, volume malsucedido, catálogo ausente/carregado depois, ausência de efeitos de GET, revogação/reativação, titularidade, ADMIN, conta inativa, assinatura inativa, filtros, paginação, período físico, separação B2B/B2C, privilégios restritos, concorrência real entre leitura e escrita e documentação OpenAPI. O resultado da execução é informado no fechamento da entrega.

### Resultado em 31/10/2026

- `mvnw.cmd -B verify`: 516 testes, zero falhas, zero erros e nenhum ignorado; `BUILD SUCCESS`, com PostgreSQL 16.15 nos testes de integração.
- `mvnw.cmd -B -Dtest=CertificateIntegrationTest -Dolius.test.postgres-image=postgres:18.6 verify`: 10 testes, zero falhas, zero erros e nenhum ignorado; `BUILD SUCCESS`.
- Os dois testes concorrentes confirmaram a preservação do retrato da resposta durante emissão de certificado e durante uma nova entrega entre a página e sua contagem.
- A suíte completa emitiu um aviso de encerramento forçado do processo de testes após 30 segundos. O diagnóstico mostrou a finalização dos pools Hikari, o mesmo comportamento já registrado no dump de 30/10, anterior a esta sprint. Todos os testes terminaram antes do aviso e o Maven retornou código 0. A execução focada no PostgreSQL 18.6 encerrou normalmente. Esse comportamento do encerramento da suíte permanece uma observação da infraestrutura de testes; não foram aumentados limites nem ocultados erros para contorná-lo.

A validação foi local em bancos descartáveis. Não substitui a revisão da PR e a execução do CI no GitHub. Não foram realizados commits ou publicação automática.
