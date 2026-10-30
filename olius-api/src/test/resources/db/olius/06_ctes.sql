/*
===============================================================================
PROJETO.............: ÓLEO AMIGO — OLIUS
BANCO DE DADOS......: PostgreSQL 16.15 (alvo)
SCRIPT..............: 06 - CTEs de Pontuação Anual
===============================================================================

Cria public.vw_annual_scores, uma view normal de pontuação do ano corrente.
Executar após os scripts 01 a 05. As CTEs são compartilhadas por ambos os
perfis e ficam dentro da definição da view, sem duplicar o cálculo no 07.
Consultar a view executa o cálculo sobre os dados visíveis na transação;
não armazena resultados, não exige REFRESH e não publica no Redis.
Não altera o saldo geral, os certificados ou as revisões de pontuação.

O ano corrente é determinado no fuso America/Sao_Paulo, independentemente
do TimeZone da sessão. A ampliação para todos os anos não faz parte deste
script. Correções e anulações são refletidas na próxima consulta conforme
o isolamento da transação e os cálculos vigentes mantidos pelo Script 04.

Participação: pelo menos uma operação RECORDED no ano consultado, com cálculo
vigente. B2B considera collection; B2C considera delivery_pev. Solicitações
sem coleta não habilitam participação. ANNULLED fica fora dos dois perfis.
No B2B, SUCCESSFUL e UNSUCCESSFUL são resultados válidos: a coleta malsucedida
participa com a penalidade vigente. Score zero não exclui quem tem operação
válida. Operações de outros anos não habilitam participação no ano consultado.
Os Sorted Sets do Redis recebem participant_id e annual_points, separados
por perfil e ano. A publicação e a remoção de membros que deixem de ser
elegíveis após anulações cabem à integração, após o commit no PostgreSQL.
O Script 07 consulta esta view para calcular posições empatadas (1, 1, 2).
*/

-- =============================================================================
-- 1. AMBOS OS PERFIS — parâmetros e limites anuais
-- =============================================================================
-- parameters e year_context são compartilhados pelos dois perfis.
-- current_calculations também é comum: somente a revisão vigente de cada
-- evento. Não relacionar point_transaction aqui: os componentes poderiam
-- multiplicar points_total. balance_after representa saldo geral, não anual.
-- O ano usa o fuso oficial, independentemente do TimeZone da sessão.
-- Intervalo [year_start, next_year_start): inclui o início, exclui o fim.

CREATE OR REPLACE VIEW public.vw_annual_scores AS
WITH parameters AS (
    SELECT
        EXTRACT(YEAR FROM CURRENT_TIMESTAMP AT TIME ZONE 'America/Sao_Paulo')::INTEGER AS ranking_year,
        'America/Sao_Paulo'::TEXT AS ranking_timezone
),
year_context AS (
    SELECT
        p.ranking_year,
        p.ranking_timezone,
        make_timestamptz(p.ranking_year, 1, 1, 0, 0, 0, p.ranking_timezone) AS year_start,
        make_timestamptz(p.ranking_year + 1, 1, 1, 0, 0, 0, p.ranking_timezone) AS next_year_start
    FROM parameters AS p
),
current_calculations AS (
    SELECT pc.user_id, pc.collection_id, pc.delivery_pev_id, pc.points_total
    FROM public.point_calculation AS pc
    WHERE pc.is_current = TRUE
),

-- =============================================================================
-- 2. SOMENTE B2B — piso zero sequencial por estabelecimento
-- =============================================================================
-- A data da coleta determina o ano; processing_order determina a sequência.
-- Coletas malsucedidas RECORDED participam com sua penalidade vigente.
-- O bônus de recorrência já está em points_total e considera o histórico
-- geral: não recalcular a recorrência usando apenas as coletas deste ano.

valid_collections AS (
    SELECT
        yc.ranking_year,
        c.establishment_id,
        c.id AS collection_id,
        c.processing_order,
        pc.points_total
    FROM public.collection AS c
    JOIN current_calculations AS pc
        ON pc.collection_id = c.id AND pc.user_id = c.establishment_id
    CROSS JOIN year_context AS yc
    WHERE c.record_status = 'RECORDED'::public.record_status_t
      AND c.collection_date >= yc.year_start
      AND c.collection_date < yc.next_year_start
),
running_totals AS (
    SELECT
        vc.*,
        SUM(vc.points_total) OVER (
            PARTITION BY vc.establishment_id, vc.ranking_year
            ORDER BY vc.processing_order
            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
        ) AS running_total
    FROM valid_collections AS vc
),
running_minimums AS (
    -- Uma segunda CTE permite aplicar MIN ao resultado da primeira janela.
    SELECT
        rt.*,
        MIN(rt.running_total) OVER (
            PARTITION BY rt.establishment_id, rt.ranking_year
            ORDER BY rt.processing_order
            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
        ) AS running_minimum
    FROM running_totals AS rt
),
annual_balances AS (
    SELECT
        rm.*,
        rm.running_total - LEAST(0::NUMERIC, rm.running_minimum) AS annual_balance
    FROM running_minimums AS rm
),
last_operations AS (
    SELECT
        ab.*,
        ROW_NUMBER() OVER (
            PARTITION BY ab.establishment_id, ab.ranking_year
            ORDER BY ab.processing_order DESC
        ) AS operation_position
    FROM annual_balances AS ab
),
b2b_annual_scores AS (
    -- O saldo final é o da última operação, não o maior saldo do ano.
    SELECT
        lo.ranking_year,
        lo.establishment_id AS participant_id,
        lo.annual_balance AS annual_points
    FROM last_operations AS lo
    WHERE lo.operation_position = 1
),

-- =============================================================================
-- 3. SOMENTE B2C — soma anual por cidadão beneficiário
-- =============================================================================
-- delivery_date determina o ano, não calculated_at nem created_at.
-- Os pontos pertencem ao cidadão beneficiário, não ao responsável pelo PEV.
-- B2C não precisa de janelas de piso zero: a soma por cidadão é suficiente.

valid_deliveries AS (
    SELECT
        yc.ranking_year,
        d.citizen_id,
        d.id AS delivery_pev_id,
        pc.points_total
    FROM public.delivery_pev AS d
    JOIN current_calculations AS pc
        ON pc.delivery_pev_id = d.id AND pc.user_id = d.citizen_id
    CROSS JOIN year_context AS yc
    WHERE d.record_status = 'RECORDED'::public.record_status_t
      AND d.delivery_date >= yc.year_start
      AND d.delivery_date < yc.next_year_start
),
b2c_annual_scores AS (
    SELECT
        vd.ranking_year,
        vd.citizen_id AS participant_id,
        SUM(vd.points_total) AS annual_points
    FROM valid_deliveries AS vd
    GROUP BY vd.ranking_year, vd.citizen_id
),

-- =============================================================================
-- 4. AMBOS OS PERFIS — resultado para publicação nos Sorted Sets separados
-- =============================================================================

annual_scores AS (
    SELECT 'B2B'::TEXT AS profile, b.*
    FROM b2b_annual_scores AS b
    UNION ALL
    SELECT 'B2C'::TEXT AS profile, c.*
    FROM b2c_annual_scores AS c
)
SELECT profile, ranking_year, participant_id, annual_points
FROM annual_scores;

COMMENT ON VIEW public.vw_annual_scores IS
'Pontuação do ano corrente em America/Sao_Paulo: cálculos vigentes, operações RECORDED, piso zero sequencial B2B e soma B2C.';

-- A ordenação pertence à consulta consumidora, não ao contrato da view.
-- SELECT * FROM public.vw_annual_scores ORDER BY profile, annual_points DESC, participant_id;
