/*
===============================================================================
PROJETO.............: ÓLEO AMIGO — OLIUS
BANCO DE DADOS......: PostgreSQL 16.15 (alvo)
SCRIPT..............: 07 - View Normal de Ranking Anual com Window Function
===============================================================================
Executar após 06_ctes.sql, que cria public.vw_annual_scores. Esta view expõe
profile TEXT, ranking_year INTEGER, participant_id UUID, annual_points NUMERIC
e ranking_position BIGINT. Uma linha por perfil, ano corrente e participante.

Herda as regras de pontuação e participação do 06 sem copiar suas CTEs.
DENSE_RANK() mantém empates (1, 1, 2), separados por perfil e ano. O ID não
entra na janela: ordena somente a apresentação. Não exige REFRESH.

Interface comum para leitura. O Data Mart materializará sua própria fotografia
do ranking junto às fatos/dimensões; esse adaptador não é criado neste script.
Redis e carga/atualização dos dashboards também pertencem à integração.
*/
CREATE OR REPLACE VIEW public.vw_annual_ranking AS
SELECT
    a.profile,
    a.ranking_year,
    a.participant_id,
    a.annual_points,
    DENSE_RANK() OVER (
        PARTITION BY a.profile, a.ranking_year
        ORDER BY a.annual_points DESC
    ) AS ranking_position
FROM public.vw_annual_scores AS a;

COMMENT ON VIEW public.vw_annual_ranking IS
'Ranking do ano corrente por perfil com DENSE_RANK; empates compartilham posição sem saltos. Fonte: public.vw_annual_scores.';

-- SELECT * FROM public.vw_annual_ranking
-- ORDER BY profile, ranking_year, ranking_position, participant_id;
