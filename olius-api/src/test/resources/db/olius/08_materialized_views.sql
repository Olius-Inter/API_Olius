/*
===============================================================================
PROJETO.............: ÓLEO AMIGO — OLIUS
AMBIENTE............: POSTGRESQL 16 — NÃO EXECUTAR NO DATABRICKS
SCRIPT..............: 08 - Views Materializadas do Data Mart
===============================================================================
Instalação única após 01 a 07. Ranking reutiliza public.vw_annual_ranking.
Dimensões tipo 1: cadastro atual, sem reconstrução histórica de endereços.
UUIDs convertidos para TEXT para facilitar o transporte sem renumerar chaves.
Não inclui CPF, CNPJ, contato, credenciais, QR ou textos livres de operações.
*/
BEGIN ISOLATION LEVEL REPEATABLE READ;
CREATE SCHEMA IF NOT EXISTS dm;

CREATE MATERIALIZED VIEW dm.dim_estabelecimento AS
SELECT e.id::TEXT AS estabelecimento_id, u.name AS nome,
       et.name AS tipo_estabelecimento, a.state::TEXT AS estado,
       a.city AS cidade, a.neighborhood AS bairro, a.latitude, a.longitude,
       e.is_pev, u.status::TEXT AS status
FROM public.establishment e
JOIN public.users u ON u.id = e.id
JOIN public.establishment_type et ON et.id = e.type_id
JOIN public.addresses a ON a.id = e.address_id;

CREATE MATERIALIZED VIEW dm.dim_cidadao AS
SELECT c.id::TEXT AS cidadao_id, u.name AS nome, u.status::TEXT AS status
FROM public.citizens c JOIN public.users u ON u.id = c.id;

CREATE MATERIALIZED VIEW dm.dim_motorista AS
SELECT id::TEXT AS motorista_id, name AS nome, status::TEXT AS status
FROM public.driver;

CREATE MATERIALIZED VIEW dm.dim_pev AS
SELECT p.id::TEXT AS pev_id, p.status::TEXT AS status,
       a.state::TEXT AS estado, a.city AS cidade, a.neighborhood AS bairro,
       a.latitude, a.longitude
FROM public.pev p JOIN public.addresses a ON a.id = p.address_id;

CREATE MATERIALIZED VIEW dm.dim_classificacao_coleta AS
SELECT r::TEXT || '|' || o::TEXT || '|' || h::TEXT AS classificacao_id,
       r::TEXT AS resultado, o::TEXT AS condicao_oleo,
       h AS ocorrencia_comprometedora
FROM unnest(enum_range(NULL::public.collection_result_t)) r
CROSS JOIN unnest(enum_range(NULL::public.oil_condition_t)) o
CROSS JOIN (VALUES (TRUE), (FALSE)) ocorrencia(h);

CREATE MATERIALIZED VIEW dm.dim_status_solicitacao AS
SELECT s::TEXT AS status_id
FROM unnest(enum_range(NULL::public.request_status_t)) s;

CREATE MATERIALIZED VIEW dm.dim_motivo_falha AS
SELECT id::TEXT AS motivo_id, code AS codigo, name AS nome, description AS descricao
FROM public.collection_failure_reason;

-- Calendário contínuo cobrindo anos inteiros, mesmo sem operações.
CREATE MATERIALIZED VIEW dm.dim_tempo AS
WITH datas AS (
    SELECT (collection_date AT TIME ZONE 'America/Sao_Paulo')::DATE AS data
    FROM public.collection
    UNION ALL
    SELECT (delivery_date AT TIME ZONE 'America/Sao_Paulo')::DATE FROM public.delivery_pev
    UNION ALL
    SELECT (request_at AT TIME ZONE 'America/Sao_Paulo')::DATE FROM public.collection_request
    UNION ALL
    SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'America/Sao_Paulo')::DATE
), limites AS (
    SELECT date_trunc('year', MIN(data)::TIMESTAMP)::DATE AS inicio,
           (date_trunc('year', MAX(data)::TIMESTAMP) + INTERVAL '1 year'
              - INTERVAL '1 day')::DATE AS fim
    FROM datas
)
SELECT inicio + n AS data_id,
       EXTRACT(YEAR FROM inicio + n)::INTEGER AS ano,
       EXTRACT(QUARTER FROM inicio + n)::INTEGER AS trimestre,
       EXTRACT(MONTH FROM inicio + n)::INTEGER AS mes,
       EXTRACT(DAY FROM inicio + n)::INTEGER AS dia,
       EXTRACT(ISODOW FROM inicio + n)::INTEGER AS dia_semana
FROM limites CROSS JOIN LATERAL generate_series(0, fim - inicio) dias(n);

-- Uma linha por visita RECORDED. Inclui malsucedidas e zero litros.
-- Cálculo ausente fica NULL, identificado pelo booleano: não inventar pontos.
CREATE MATERIALIZED VIEW dm.fato_coleta AS
SELECT c.id::TEXT AS coleta_id,
       (c.collection_date AT TIME ZONE 'America/Sao_Paulo')::DATE AS data_id,
       c.establishment_id::TEXT AS estabelecimento_id, c.driver_id::TEXT AS motorista_id,
       c.result::TEXT || '|' || c.oil_condition::TEXT || '|' ||
           c.has_compromising_occurrence::TEXT AS classificacao_id,
       c.collection_request_id::TEXT AS solicitacao_id,
       c.collected_volume_liters AS volume_coletado_litros,
       c.presented_volume_liters AS volume_apresentado_litros,
       cr.estimated_volume_liters AS volume_estimado_litros,
       pc.points_total AS pontos_nominais, pc.id IS NOT NULL AS calculo_vigente_disponivel,
       1::INTEGER AS quantidade_coletas
FROM public.collection c
JOIN public.collection_request cr ON cr.id = c.collection_request_id
LEFT JOIN public.point_calculation pc
    ON pc.collection_id = c.id AND pc.user_id = c.establishment_id AND pc.is_current
WHERE c.record_status = 'RECORDED'::public.record_status_t;

CREATE MATERIALIZED VIEW dm.fato_entrega_pev AS
SELECT d.id::TEXT AS entrega_id,
       (d.delivery_date AT TIME ZONE 'America/Sao_Paulo')::DATE AS data_id,
       d.citizen_id::TEXT AS cidadao_id, d.pev_id::TEXT AS pev_id,
       d.oil_volume_liters AS volume_entregue_litros,
       pc.points_total AS pontos_nominais, pc.id IS NOT NULL AS calculo_vigente_disponivel,
       1::INTEGER AS quantidade_entregas
FROM public.delivery_pev d
LEFT JOIN public.point_calculation pc
    ON pc.delivery_pev_id = d.id AND pc.user_id = d.citizen_id AND pc.is_current
WHERE d.record_status = 'RECORDED'::public.record_status_t;

-- Snapshot acumulativo: a mesma solicitação muda conforme o atendimento avança.
-- Agendamento inicial não conta como reagendamento; agregar antes de juntar.
-- c.record_status no ON mantém pedidos sem coleta ou com visita anulada.
CREATE MATERIALIZED VIEW dm.fato_solicitacao AS
WITH reagendamentos AS (
    SELECT collection_request_id, COUNT(*)::BIGINT AS quantidade
    FROM public.collection_schedule_history
    WHERE previous_scheduled_at IS NOT NULL
      AND previous_scheduled_at IS DISTINCT FROM new_scheduled_at
    GROUP BY collection_request_id
)
SELECT cr.id::TEXT AS solicitacao_id,
       (cr.request_at AT TIME ZONE 'America/Sao_Paulo')::DATE AS data_id,
       cr.establishment_id::TEXT AS estabelecimento_id, cr.status::TEXT AS status_id,
       cr.request_at AS solicitada_em, cr.scheduled_at AS agendada_em,
       cr.arrived_at AS chegada_em, c.collection_date AS coletada_em,
       cr.cancelled_at AS cancelada_em,
       cr.estimated_volume_liters AS volume_estimado_litros,
       cr.forfeited_volume_liters AS volume_franquia_perdida_litros,
       cr.forfeited_collection_slots::INTEGER AS vagas_perdidas,
       1::INTEGER AS quantidade_solicitacoes,
       COALESCE(r.quantidade, 0::BIGINT) AS quantidade_reagendamentos,
       (EXTRACT(EPOCH FROM (c.collection_date - cr.request_at)) / 3600)::NUMERIC(18,4)
           AS tempo_ate_coleta_horas,
       (EXTRACT(EPOCH FROM (cr.arrived_at - cr.scheduled_at)) / 60)::NUMERIC(18,4)
           AS desvio_chegada_minutos,
       cr.cancellation_initiative::TEXT AS iniciativa_cancelamento,
       cr.cancellation_policy::TEXT AS politica_cancelamento
FROM public.collection_request cr
LEFT JOIN public.collection c ON c.collection_request_id = cr.id
    AND c.record_status = 'RECORDED'::public.record_status_t
LEFT JOIN reagendamentos r ON r.collection_request_id = cr.id;

CREATE MATERIALIZED VIEW dm.ponte_coleta_motivo AS
SELECT cf.collection_id::TEXT AS coleta_id, cf.failure_reason_id::TEXT AS motivo_id
FROM public.collection_failure cf
JOIN dm.fato_coleta f ON f.coleta_id = cf.collection_id::TEXT;

-- Fotografia do ranking aprovado: somente ano corrente, sem recalcular janela.
CREATE MATERIALIZED VIEW dm.ranking_anual AS
SELECT profile AS perfil, ranking_year AS ano,
       participant_id::TEXT AS participante_id, annual_points AS pontos_anuais,
       ranking_position AS posicao
FROM public.vw_annual_ranking;

CREATE UNIQUE INDEX ux_dm_dim_tempo ON dm.dim_tempo (data_id);
CREATE UNIQUE INDEX ux_dm_dim_estabelecimento ON dm.dim_estabelecimento (estabelecimento_id);
CREATE UNIQUE INDEX ux_dm_dim_cidadao ON dm.dim_cidadao (cidadao_id);
CREATE UNIQUE INDEX ux_dm_dim_motorista ON dm.dim_motorista (motorista_id);
CREATE UNIQUE INDEX ux_dm_dim_pev ON dm.dim_pev (pev_id);
CREATE UNIQUE INDEX ux_dm_dim_classificacao_coleta ON dm.dim_classificacao_coleta (classificacao_id);
CREATE UNIQUE INDEX ux_dm_dim_status_solicitacao ON dm.dim_status_solicitacao (status_id);
CREATE UNIQUE INDEX ux_dm_dim_motivo_falha ON dm.dim_motivo_falha (motivo_id);
CREATE UNIQUE INDEX ux_dm_fato_coleta ON dm.fato_coleta (coleta_id);
CREATE UNIQUE INDEX ux_dm_fato_entrega_pev ON dm.fato_entrega_pev (entrega_id);
CREATE UNIQUE INDEX ux_dm_fato_solicitacao ON dm.fato_solicitacao (solicitacao_id);
CREATE UNIQUE INDEX ux_dm_ponte_coleta_motivo ON dm.ponte_coleta_motivo (coleta_id, motivo_id);
CREATE UNIQUE INDEX ux_dm_ranking_anual ON dm.ranking_anual (perfil, ano, participante_id);

-- Metadado operacional, não é uma dimensão do negócio.
CREATE TABLE dm.refresh_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    snapshot_id UUID NOT NULL,
    refreshed_at TIMESTAMPTZ NOT NULL
);
INSERT INTO dm.refresh_state VALUES (TRUE, gen_random_uuid(), clock_timestamp());

CREATE OR REPLACE PROCEDURE dm.refresh_data_mart()
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, dm, pg_temp
AS $$
BEGIN
    IF current_setting('transaction_isolation') NOT IN ('repeatable read', 'serializable') THEN
        RAISE EXCEPTION 'Refresh exige transação REPEATABLE READ';
    END IF;
    -- Coordena chamadas concorrentes de refresh (Job e manutenção manual).
    IF NOT pg_try_advisory_xact_lock(71007, 1) THEN
        RAISE EXCEPTION 'Data Mart em atualização; tentar depois';
    END IF;
    REFRESH MATERIALIZED VIEW dm.dim_tempo;
    REFRESH MATERIALIZED VIEW dm.dim_estabelecimento;
    REFRESH MATERIALIZED VIEW dm.dim_cidadao;
    REFRESH MATERIALIZED VIEW dm.dim_motorista;
    REFRESH MATERIALIZED VIEW dm.dim_pev;
    REFRESH MATERIALIZED VIEW dm.dim_classificacao_coleta;
    REFRESH MATERIALIZED VIEW dm.dim_status_solicitacao;
    REFRESH MATERIALIZED VIEW dm.dim_motivo_falha;
    REFRESH MATERIALIZED VIEW dm.fato_coleta;
    REFRESH MATERIALIZED VIEW dm.fato_entrega_pev;
    REFRESH MATERIALIZED VIEW dm.fato_solicitacao;
    REFRESH MATERIALIZED VIEW dm.ponte_coleta_motivo;
    REFRESH MATERIALIZED VIEW dm.ranking_anual;
    UPDATE dm.refresh_state
    SET snapshot_id = pg_catalog.gen_random_uuid(), refreshed_at = clock_timestamp()
    WHERE singleton;
END;
$$;
REVOKE ALL ON PROCEDURE dm.refresh_data_mart() FROM PUBLIC;
COMMENT ON PROCEDURE dm.refresh_data_mart() IS
'Refresh integral consistente. Autorizar EXECUTE só ao papel de atualização. Não atualizar views individualmente.';
COMMIT;

