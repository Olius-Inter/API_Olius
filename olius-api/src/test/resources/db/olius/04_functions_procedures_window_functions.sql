/*
===============================================================================
PROJETO.............: ÓLEO AMIGO — OLIUS
BANCO DE DADOS......: PostgreSQL 16.15 (alvo)
SCRIPT..............: 04 - Functions, Procedures e Window Functions de Negócio
SEÇÃO................: 01 - Functions
===============================================================================

As functions desta seção são exclusivamente de consulta e cálculo. A criação
de reservas, a persistência de pontos e a concessão de certificados pertencem
às procedures do Script 04.
*/

BEGIN;

-- =============================================================================
-- 1. Disponibilidade do ciclo
-- =============================================================================

CREATE OR REPLACE FUNCTION public.get_cycle_availability(
    p_subscription_cycle_id UUID
)
RETURNS TABLE (
    cycle_id UUID,
    establishment_id UUID,
    volume_limit_liters NUMERIC,
    collection_limit INTEGER,
    reserved_volume_liters NUMERIC,
    reserved_collection_slots BIGINT,
    consumed_volume_liters NUMERIC,
    consumed_collection_slots BIGINT,
    forfeited_volume_liters NUMERIC,
    forfeited_collection_slots BIGINT,
    available_volume_liters NUMERIC,
    available_collection_slots BIGINT,
    is_subscription_active BOOLEAN,
    is_cycle_current BOOLEAN,
    can_accept_new_request BOOLEAN
)
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    c_subscription_active CONSTANT public.subscription_status_t := 'ACTIVE';
    c_request_pending CONSTANT public.request_status_t := 'PENDING';
    c_request_approved CONSTANT public.request_status_t := 'APPROVED';
    c_request_cancelled CONSTANT public.request_status_t := 'CANCELLED';
    c_recorded CONSTANT public.record_status_t := 'RECORDED';
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.subscription_cycle AS sc
        WHERE sc.id = p_subscription_cycle_id
    ) THEN
        RAISE EXCEPTION 'O ciclo de assinatura % não existe.', p_subscription_cycle_id
            USING ERRCODE = 'P0002';
    END IF;

    RETURN QUERY
    WITH cycle_context AS (
        SELECT
            sc.id AS cycle_id,
            sc.establishment_id,
            sc.volume_limit_liters,
            sc.collection_limit,
            (es.status = c_subscription_active) AS is_subscription_active,
            (CURRENT_TIMESTAMP >= sc.starts_at AND CURRENT_TIMESTAMP < sc.ends_at) AS is_cycle_current
        FROM public.subscription_cycle AS sc
        JOIN public.establishment_subscription AS es
            ON es.id = sc.subscription_id
           AND es.establishment_id = sc.establishment_id
        WHERE sc.id = p_subscription_cycle_id
    ),
    reserved AS (
        -- A reserva começa em PENDING e persiste em APPROVED. Depois que uma
        -- collection existe, ela deixa de ser reserva: o consumo é calculado
        -- exclusivamente no CTE consumed, evitando dupla contagem.
        SELECT
            cr.subscription_cycle_id AS cycle_id,
            COALESCE(SUM(cr.estimated_volume_liters), 0::NUMERIC) AS volume_liters,
            COUNT(*)::BIGINT AS collection_slots
        FROM public.collection_request AS cr
        WHERE cr.subscription_cycle_id = p_subscription_cycle_id
          AND cr.status IN (c_request_pending, c_request_approved)
          AND NOT EXISTS (
              SELECT 1
              FROM public.collection AS c
              WHERE c.collection_request_id = cr.id
          )
        GROUP BY cr.subscription_cycle_id
    ),
    consumed AS (
        -- Uma visita registrada, inclusive malsucedida, consome o volume
        -- efetivamente recolhido e uma vaga do ciclo de origem da solicitação.
        SELECT
            cr.subscription_cycle_id AS cycle_id,
            COALESCE(SUM(c.collected_volume_liters), 0::NUMERIC) AS volume_liters,
            COUNT(*)::BIGINT AS collection_slots
        FROM public.collection AS c
        JOIN public.collection_request AS cr
            ON cr.id = c.collection_request_id
        WHERE cr.subscription_cycle_id = p_subscription_cycle_id
          AND c.record_status = c_recorded
        GROUP BY cr.subscription_cycle_id
    ),
    forfeited AS (
        -- Cancelamentos tardios não geram collection. Seus campos próprios
        -- representam a franquia perdida e não são volume ambiental.
        SELECT
            cr.subscription_cycle_id AS cycle_id,
            COALESCE(SUM(cr.forfeited_volume_liters), 0::NUMERIC) AS volume_liters,
            COALESCE(SUM(cr.forfeited_collection_slots), 0)::BIGINT AS collection_slots
        FROM public.collection_request AS cr
        WHERE cr.subscription_cycle_id = p_subscription_cycle_id
          AND cr.status = c_request_cancelled
        GROUP BY cr.subscription_cycle_id
    ),
    availability AS (
        SELECT
            cc.*,
            COALESCE(r.volume_liters, 0::NUMERIC) AS reserved_volume_liters,
            COALESCE(r.collection_slots, 0::BIGINT) AS reserved_collection_slots,
            COALESCE(c.volume_liters, 0::NUMERIC) AS consumed_volume_liters,
            COALESCE(c.collection_slots, 0::BIGINT) AS consumed_collection_slots,
            COALESCE(f.volume_liters, 0::NUMERIC) AS forfeited_volume_liters,
            COALESCE(f.collection_slots, 0::BIGINT) AS forfeited_collection_slots
        FROM cycle_context AS cc
        LEFT JOIN reserved AS r ON r.cycle_id = cc.cycle_id
        LEFT JOIN consumed AS c ON c.cycle_id = cc.cycle_id
        LEFT JOIN forfeited AS f ON f.cycle_id = cc.cycle_id
    )
    SELECT
        a.cycle_id,
        a.establishment_id,
        a.volume_limit_liters,
        a.collection_limit,
        a.reserved_volume_liters,
        a.reserved_collection_slots,
        a.consumed_volume_liters,
        a.consumed_collection_slots,
        a.forfeited_volume_liters,
        a.forfeited_collection_slots,
        GREATEST(
            a.volume_limit_liters
                - a.reserved_volume_liters
                - a.consumed_volume_liters
                - a.forfeited_volume_liters,
            0::NUMERIC
        ) AS available_volume_liters,
        GREATEST(
            a.collection_limit::BIGINT
                - a.reserved_collection_slots
                - a.consumed_collection_slots
                - a.forfeited_collection_slots,
            0::BIGINT
        ) AS available_collection_slots,
        a.is_subscription_active,
        a.is_cycle_current,
        (
            a.is_subscription_active
            AND a.is_cycle_current
            AND a.volume_limit_liters
                    - a.reserved_volume_liters
                    - a.consumed_volume_liters
                    - a.forfeited_volume_liters > 0::NUMERIC
            AND a.collection_limit::BIGINT
                    - a.reserved_collection_slots
                    - a.consumed_collection_slots
                    - a.forfeited_collection_slots > 0::BIGINT
        ) AS can_accept_new_request
    FROM availability AS a;
END;
$$;

COMMENT ON FUNCTION public.get_cycle_availability(UUID) IS
'Consulta a disponibilidade B2B de um ciclo, separando reservas, consumo e perdas por cancelamento tardio, sem criar reservas.';

-- =============================================================================
-- 2. Cálculo da pontuação de uma coleta
-- =============================================================================

CREATE OR REPLACE FUNCTION public.calculate_collection_score(
    p_collection_id UUID
)
RETURNS TABLE (
    collection_id UUID,
    establishment_id UUID,
    successful_collections_count BIGINT,
    volume_points BIGINT,
    success_bonus_points BIGINT,
    recurrence_bonus_points BIGINT,
    failure_penalty_points BIGINT,
    nominal_points_total BIGINT
)
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    c_failure_penalty CONSTANT BIGINT := -50;
    c_success_bonus CONSTANT BIGINT := 50;
    c_recurrence_cap CONSTANT BIGINT := 100;
    c_recurrence_interval CONSTANT BIGINT := 10;
    c_unsuccessful CONSTANT public.collection_result_t := 'UNSUCCESSFUL';
    c_successful CONSTANT public.collection_result_t := 'SUCCESSFUL';
    c_annulled CONSTANT public.record_status_t := 'ANNULLED';
    c_recorded CONSTANT public.record_status_t := 'RECORDED';
    v_collection public.collection%ROWTYPE;
    v_successful_collections_count BIGINT;
BEGIN
    SELECT c.*
    INTO v_collection
    FROM public.collection AS c
    WHERE c.id = p_collection_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'A coleta % não existe.', p_collection_id
            USING ERRCODE = 'P0002';
    END IF;

    -- processing_order é a ordem lógica e imutável da reconstrução. Falhas
    -- ficam fora da contagem, portanto não reiniciam os sucessos acumulados.
    -- ANNULLED também fica fora, inclusive quando a anulação é posterior.
    SELECT COUNT(*)::BIGINT
    INTO v_successful_collections_count
    FROM public.collection AS c
    WHERE c.establishment_id = v_collection.establishment_id
      AND c.processing_order <= v_collection.processing_order
      AND c.record_status = c_recorded
      AND c.result = c_successful;

    RETURN QUERY
    SELECT
        v_collection.id,
        v_collection.establishment_id,
        v_successful_collections_count,
        CASE
            WHEN v_collection.record_status = c_recorded
             AND v_collection.result = c_successful
                THEN FLOOR(v_collection.collected_volume_liters)::BIGINT
            ELSE 0::BIGINT
        END AS volume_points,
        CASE
            WHEN v_collection.record_status = c_recorded
             AND v_collection.result = c_successful
                THEN c_success_bonus
            ELSE 0::BIGINT
        END AS success_bonus_points,
        CASE
            WHEN v_collection.record_status = c_recorded
             AND v_collection.result = c_successful
             AND v_successful_collections_count % c_recurrence_interval = 0
                THEN LEAST(v_successful_collections_count, c_recurrence_cap)
            ELSE 0::BIGINT
        END AS recurrence_bonus_points,
        CASE
            WHEN v_collection.record_status = c_recorded
             AND v_collection.result = c_unsuccessful
                THEN c_failure_penalty
            ELSE 0::BIGINT
        END AS failure_penalty_points,
        CASE
            WHEN v_collection.record_status = c_annulled THEN 0::BIGINT
            WHEN v_collection.result = c_unsuccessful THEN c_failure_penalty
            ELSE FLOOR(v_collection.collected_volume_liters)::BIGINT
                + c_success_bonus
                + CASE
                    WHEN v_successful_collections_count % c_recurrence_interval = 0
                        THEN LEAST(v_successful_collections_count, c_recurrence_cap)
                    ELSE 0::BIGINT
                END
        END AS nominal_points_total;
END;
$$;

COMMENT ON FUNCTION public.calculate_collection_score(UUID) IS
'Calcula os componentes nominais de pontos de uma coleta B2B; o piso zero do saldo é aplicado somente na reconstrução persistida.';

-- =============================================================================
-- 3. Progresso ambiental dos certificados
-- =============================================================================

CREATE OR REPLACE FUNCTION public.get_certificate_progress(
    p_establishment_id UUID
)
RETURNS TABLE (
    establishment_id UUID,
    eligible_volume_liters NUMERIC,
    certificate_level_id UUID,
    level_name VARCHAR,
    level_description TEXT,
    required_liters NUMERIC,
    is_level_reached BOOLEAN,
    remaining_liters NUMERIC,
    certificate_id UUID,
    certificate_status public.certificate_status_t
)
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    c_recorded CONSTANT public.record_status_t := 'RECORDED';
    v_eligible_volume_liters NUMERIC;
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.establishment AS e
        WHERE e.id = p_establishment_id
    ) THEN
        RAISE EXCEPTION 'O estabelecimento % não existe.', p_establishment_id
            USING ERRCODE = 'P0002';
    END IF;

    -- O volume ambiental oficial conta toda collection RECORDED, inclusive
    -- UNSUCCESSFUL. ANNULLED deixa de contribuir, e não entram reservas,
    -- perdas por cancelamento, pontos ou entregas B2C em PEV.
    SELECT COALESCE(SUM(c.collected_volume_liters), 0::NUMERIC)
    INTO v_eligible_volume_liters
    FROM public.collection AS c
    WHERE c.establishment_id = p_establishment_id
      AND c.record_status = c_recorded;

    -- Sem certificate_level cadastrado, o SELECT retorna zero linhas: não é
    -- criado um nível artificial apenas para sinalizar falta de configuração.
    RETURN QUERY
    SELECT
        p_establishment_id,
        v_eligible_volume_liters,
        cl.id,
        cl.name,
        cl.description,
        cl.required_liters,
        v_eligible_volume_liters >= cl.required_liters AS is_level_reached,
        GREATEST(cl.required_liters - v_eligible_volume_liters, 0::NUMERIC) AS remaining_liters,
        cert.id,
        cert.status
    FROM public.certificate_level AS cl
    LEFT JOIN public.certificate AS cert
        ON cert.certificate_level_id = cl.id
       AND cert.establishment_id = p_establishment_id
    ORDER BY cl.required_liters, cl.name, cl.id;
END;
$$;

COMMENT ON FUNCTION public.get_certificate_progress(UUID) IS
'Consulta o volume ambiental elegível e o progresso por nível de certificado, sem emitir, revogar ou reativar concessões.';

COMMIT;

/*
===============================================================================
PROJETO.............: ÓLEO AMIGO — OLIUS
BANCO DE DADOS......: PostgreSQL 16.15 (alvo)
SCRIPT..............: 04 - Functions e Procedures e Window Functions de Negócio
SEÇÃO................: 02 - Procedures
===============================================================================

INSTALAÇÃO/EXECUÇÃO
As dez procedures não abrem nem confirmam transações próprias. A API deve
executá-las em transação e obter os operadores da autenticação. Antes de usar
as operações, instalar também o Script 05: a idempotência compara snapshots
INSERT/AFTER dos logs tipados, sem idempotency_payload. Histórico ausente gera
erro explícito, nunca aceitação silenciosa. Dados anteriores sem esse snapshot
exigem análise/migração; não recriar um original fictício a partir do estado atual.
Litros aceitam no máximo duas casas decimais, conforme DECIMAL(8,2), para não
arredondar silenciosamente os dados usados na idempotência e na pontuação.
*/

BEGIN;

-- =============================================================================
-- 1. Reconstrução interna da pontuação
-- =============================================================================

CREATE OR REPLACE PROCEDURE public.rebuild_user_points(
    IN p_user_id UUID,
    IN p_recalculated_by UUID DEFAULT NULL,
    IN p_recalculation_reason TEXT DEFAULT NULL
)
LANGUAGE plpgsql
AS $$
DECLARE
    c_active CONSTANT public.active_status_t := 'ACTIVE';
    c_recorded CONSTANT public.record_status_t := 'RECORDED';
    c_admin CONSTANT public.user_type_t := 'ADMIN';
    c_volume_component CONSTANT public.point_component_t := 'VOLUME';
    c_err_parameter CONSTANT TEXT := '22023';
    c_err_authorization CONSTANT TEXT := '42501';
    c_msg_admin CONSTANT TEXT := 'O administrador informado não existe ou não está ativo.';
    v_is_establishment BOOLEAN;
    v_event RECORD;
    v_collection_score RECORD;
    v_current_calculation RECORD;
    v_volume_points BIGINT;
    v_success_bonus BIGINT;
    v_recurrence_bonus BIGINT;
    v_failure_penalty BIGINT;
    v_points_total BIGINT;
    v_success_count BIGINT;
    v_balance_before BIGINT := 0;
    v_balance_after BIGINT;
    v_old_volume BIGINT;
    v_old_success BIGINT;
    v_old_recurrence BIGINT;
    v_old_failure BIGINT;
    v_revision INTEGER;
    v_calculation_id UUID;
BEGIN
    IF (p_recalculated_by IS NULL) <> (p_recalculation_reason IS NULL)
       OR (p_recalculation_reason IS NOT NULL AND BTRIM(p_recalculation_reason) = '') THEN
        RAISE EXCEPTION 'Autor e justificativa da reconstrução devem ser informados juntos e a justificativa não pode ser vazia.'
            USING ERRCODE = c_err_parameter;
    END IF;

    IF p_recalculated_by IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM public.users AS u
        WHERE u.id = p_recalculated_by
          AND u.user_type = c_admin
          AND u.status = c_active
    ) THEN
        RAISE EXCEPTION '%', c_msg_admin
            USING ERRCODE = c_err_authorization;
    END IF;

    SELECT EXISTS (
        SELECT 1 FROM public.establishment AS e WHERE e.id = p_user_id
    ) INTO v_is_establishment;

    IF NOT v_is_establishment AND NOT EXISTS (
        SELECT 1 FROM public.citizens AS c WHERE c.id = p_user_id
    ) THEN
        RAISE EXCEPTION 'O participante % não existe.', p_user_id
            USING ERRCODE = 'P0002';
    END IF;

    -- A linha do perfil serializa reconstruções concorrentes para o mesmo
    -- participante. Cada evento é processado na sua ordem oficial.
    IF v_is_establishment THEN
        PERFORM 1 FROM public.establishment AS e WHERE e.id = p_user_id FOR UPDATE;
    ELSE
        PERFORM 1 FROM public.citizens AS c WHERE c.id = p_user_id FOR UPDATE;
    END IF;

    IF v_is_establishment THEN
        FOR v_event IN
            SELECT c.id, c.record_status
            FROM public.collection AS c
            WHERE c.establishment_id = p_user_id
            ORDER BY c.processing_order, c.id
        LOOP
            SELECT f.*
            INTO v_collection_score
            FROM public.calculate_collection_score(v_event.id) AS f;

            v_volume_points := v_collection_score.volume_points;
            v_success_bonus := v_collection_score.success_bonus_points;
            v_recurrence_bonus := v_collection_score.recurrence_bonus_points;
            v_failure_penalty := v_collection_score.failure_penalty_points;
            v_points_total := v_collection_score.nominal_points_total;
            v_success_count := v_collection_score.successful_collections_count;
            v_balance_after := GREATEST(
                0::NUMERIC,
                v_balance_before::NUMERIC + v_points_total::NUMERIC
            )::BIGINT;

            SELECT pc.id, pc.revision, pc.points_total, pc.balance_before,
                   pc.balance_after, pc.successful_collections_count
            INTO v_current_calculation
            FROM public.point_calculation AS pc
            WHERE pc.collection_id = v_event.id
              AND pc.is_current
            FOR UPDATE;

            SELECT
                COALESCE(SUM(pt.points) FILTER (WHERE pt.component = c_volume_component), 0),
                COALESCE(SUM(pt.points) FILTER (WHERE pt.component = 'SUCCESS_BONUS'::public.point_component_t), 0),
                COALESCE(SUM(pt.points) FILTER (WHERE pt.component = 'RECURRENCE_BONUS'::public.point_component_t), 0),
                COALESCE(SUM(pt.points) FILTER (WHERE pt.component = 'FAILURE_PENALTY'::public.point_component_t), 0)
            INTO v_old_volume, v_old_success, v_old_recurrence, v_old_failure
            FROM public.point_transaction AS pt
            WHERE pt.point_calculation_id = v_current_calculation.id;

            IF v_current_calculation.id IS NULL
               OR v_current_calculation.points_total IS DISTINCT FROM v_points_total
               OR v_current_calculation.balance_before IS DISTINCT FROM v_balance_before
               OR v_current_calculation.balance_after IS DISTINCT FROM v_balance_after
               OR v_current_calculation.successful_collections_count IS DISTINCT FROM v_success_count
               OR v_old_volume IS DISTINCT FROM v_volume_points
               OR v_old_success IS DISTINCT FROM v_success_bonus
               OR v_old_recurrence IS DISTINCT FROM v_recurrence_bonus
               OR v_old_failure IS DISTINCT FROM v_failure_penalty THEN

                IF v_current_calculation.id IS NOT NULL
                   AND p_recalculated_by IS NULL THEN
                    RAISE EXCEPTION 'A reconstrução alteraria um cálculo anterior do participante % e exige administrador e justificativa.', p_user_id
                        USING ERRCODE = c_err_authorization;
                END IF;

                UPDATE public.point_calculation AS pc
                SET is_current = FALSE
                WHERE pc.collection_id = v_event.id
                  AND pc.is_current;

                SELECT (COALESCE(MAX(pc.revision), 0) + 1)::INTEGER
                INTO v_revision
                FROM public.point_calculation AS pc
                WHERE pc.collection_id = v_event.id;

                INSERT INTO public.point_calculation (
                    user_id, collection_id, revision, is_current, points_total,
                    balance_before, balance_after, successful_collections_count,
                    calculated_at, recalculated_by, recalculation_reason
                ) VALUES (
                    p_user_id, v_event.id, v_revision, TRUE, v_points_total,
                    v_balance_before, v_balance_after, v_success_count,
                    clock_timestamp(),
                    CASE WHEN v_revision = 1 THEN NULL ELSE p_recalculated_by END,
                    CASE WHEN v_revision = 1 THEN NULL ELSE p_recalculation_reason END
                )
                RETURNING id INTO v_calculation_id;

                IF v_volume_points <> 0 THEN
                    INSERT INTO public.point_transaction (point_calculation_id, component, points)
                    VALUES (v_calculation_id, c_volume_component, v_volume_points);
                END IF;
                IF v_success_bonus <> 0 THEN
                    INSERT INTO public.point_transaction (point_calculation_id, component, points)
                    VALUES (v_calculation_id, 'SUCCESS_BONUS'::public.point_component_t, v_success_bonus);
                END IF;
                IF v_recurrence_bonus <> 0 THEN
                    INSERT INTO public.point_transaction (point_calculation_id, component, points)
                    VALUES (v_calculation_id, 'RECURRENCE_BONUS'::public.point_component_t, v_recurrence_bonus);
                END IF;
                IF v_failure_penalty <> 0 THEN
                    INSERT INTO public.point_transaction (point_calculation_id, component, points)
                    VALUES (v_calculation_id, 'FAILURE_PENALTY'::public.point_component_t, v_failure_penalty);
                END IF;
            END IF;

            UPDATE public.collection AS c
            SET points_earned = v_points_total
            WHERE c.id = v_event.id
              AND c.points_earned IS DISTINCT FROM v_points_total;

            v_balance_before := v_balance_after;
        END LOOP;

        UPDATE public.establishment AS e
        SET points = v_balance_before
        WHERE e.id = p_user_id
          AND e.points IS DISTINCT FROM v_balance_before;
    ELSE
        FOR v_event IN
            SELECT d.id, d.record_status, d.oil_volume_liters
            FROM public.delivery_pev AS d
            WHERE d.citizen_id = p_user_id
            ORDER BY d.processing_order, d.id
        LOOP
            v_volume_points := CASE
                WHEN v_event.record_status = c_recorded
                    THEN FLOOR(v_event.oil_volume_liters)::BIGINT
                ELSE 0::BIGINT
            END;
            v_success_bonus := 0;
            v_recurrence_bonus := 0;
            v_failure_penalty := 0;
            v_points_total := v_volume_points;
            v_success_count := NULL;
            v_balance_after := GREATEST(
                0::NUMERIC,
                v_balance_before::NUMERIC + v_points_total::NUMERIC
            )::BIGINT;

            SELECT pc.id, pc.revision, pc.points_total, pc.balance_before,
                   pc.balance_after, pc.successful_collections_count
            INTO v_current_calculation
            FROM public.point_calculation AS pc
            WHERE pc.delivery_pev_id = v_event.id
              AND pc.is_current
            FOR UPDATE;

            SELECT COALESCE(SUM(pt.points) FILTER (WHERE pt.component = c_volume_component), 0)
            INTO v_old_volume
            FROM public.point_transaction AS pt
            WHERE pt.point_calculation_id = v_current_calculation.id;
            v_old_success := 0;
            v_old_recurrence := 0;
            v_old_failure := 0;

            IF v_current_calculation.id IS NULL
               OR v_current_calculation.points_total IS DISTINCT FROM v_points_total
               OR v_current_calculation.balance_before IS DISTINCT FROM v_balance_before
               OR v_current_calculation.balance_after IS DISTINCT FROM v_balance_after
               OR v_current_calculation.successful_collections_count IS DISTINCT FROM NULL
               OR v_old_volume IS DISTINCT FROM v_volume_points THEN

                IF v_current_calculation.id IS NOT NULL
                   AND p_recalculated_by IS NULL THEN
                    RAISE EXCEPTION 'A reconstrução alteraria um cálculo anterior do participante % e exige administrador e justificativa.', p_user_id
                        USING ERRCODE = c_err_authorization;
                END IF;

                UPDATE public.point_calculation AS pc
                SET is_current = FALSE
                WHERE pc.delivery_pev_id = v_event.id
                  AND pc.is_current;

                SELECT (COALESCE(MAX(pc.revision), 0) + 1)::INTEGER
                INTO v_revision
                FROM public.point_calculation AS pc
                WHERE pc.delivery_pev_id = v_event.id;

                INSERT INTO public.point_calculation (
                    user_id, delivery_pev_id, revision, is_current, points_total,
                    balance_before, balance_after, successful_collections_count,
                    calculated_at, recalculated_by, recalculation_reason
                ) VALUES (
                    p_user_id, v_event.id, v_revision, TRUE, v_points_total,
                    v_balance_before, v_balance_after, NULL, clock_timestamp(),
                    CASE WHEN v_revision = 1 THEN NULL ELSE p_recalculated_by END,
                    CASE WHEN v_revision = 1 THEN NULL ELSE p_recalculation_reason END
                )
                RETURNING id INTO v_calculation_id;

                IF v_volume_points <> 0 THEN
                    INSERT INTO public.point_transaction (point_calculation_id, component, points)
                    VALUES (v_calculation_id, c_volume_component, v_volume_points);
                END IF;
            END IF;

            UPDATE public.delivery_pev AS d
            SET points_earned = v_points_total
            WHERE d.id = v_event.id
              AND d.points_earned IS DISTINCT FROM v_points_total;

            v_balance_before := v_balance_after;
        END LOOP;

        UPDATE public.citizens AS c
        SET points = v_balance_before
        WHERE c.id = p_user_id
          AND c.points IS DISTINCT FROM v_balance_before;
    END IF;
END;
$$;

COMMENT ON PROCEDURE public.rebuild_user_points(UUID, UUID, TEXT) IS
'Reconstrói pontos B2B/B2C em ordem oficial, preserva revisões e aplica o piso zero após cada evento.';

-- =============================================================================
-- 2. Reconciliação interna dos certificados
-- =============================================================================

CREATE OR REPLACE PROCEDURE public.reconcile_establishment_certificates(
    IN p_establishment_id UUID
)
LANGUAGE plpgsql
AS $$
DECLARE
    c_certificate_active CONSTANT public.certificate_status_t := 'ACTIVE';
    c_revoked CONSTANT public.certificate_status_t := 'REVOKED';
    v_progress RECORD;
    v_now TIMESTAMPTZ;
BEGIN
    PERFORM 1
    FROM public.establishment AS e
    WHERE e.id = p_establishment_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'O estabelecimento % não existe.', p_establishment_id
            USING ERRCODE = 'P0002';
    END IF;

    FOR v_progress IN
        SELECT * FROM public.get_certificate_progress(p_establishment_id)
    LOOP
        IF v_progress.is_level_reached AND v_progress.certificate_id IS NULL THEN
            v_now := clock_timestamp();
            INSERT INTO public.certificate (
                certificate_code, issued_at, created_at, updated_at,
                certificate_level_id, establishment_id, status
            ) VALUES (
                gen_random_uuid()::TEXT, v_now, v_now, v_now,
                v_progress.certificate_level_id, p_establishment_id,
                c_certificate_active
            );
        ELSIF v_progress.is_level_reached
          AND v_progress.certificate_status = c_revoked THEN
            v_now := clock_timestamp();
            UPDATE public.certificate AS cert
            SET status = c_certificate_active,
                reactivated_at = v_now,
                status_reason = NULL,
                updated_at = v_now
            WHERE cert.id = v_progress.certificate_id
              AND cert.status = c_revoked;
        ELSIF NOT v_progress.is_level_reached
          AND v_progress.certificate_status = c_certificate_active THEN
            v_now := clock_timestamp();
            UPDATE public.certificate AS cert
            SET status = c_revoked,
                revoked_at = v_now,
                status_reason = 'O volume ambiental elegível ficou abaixo da meta do nível.',
                updated_at = v_now
            WHERE cert.id = v_progress.certificate_id
              AND cert.status = c_certificate_active;
        END IF;
    END LOOP;
END;
$$;

COMMENT ON PROCEDURE public.reconcile_establishment_certificates(UUID) IS
'Concede, revoga ou reativa a mesma concessão conforme o volume elegível e os níveis cadastrados.';

-- =============================================================================
-- 3. Aplicação do pagamento verificado
-- =============================================================================

CREATE OR REPLACE PROCEDURE public.apply_verified_payment(
    IN p_payment_id UUID,
    OUT p_payment_application_id UUID,
    OUT p_cycle_id UUID,
    OUT p_refund_id UUID
)
LANGUAGE plpgsql
AS $$
DECLARE
    c_subscription_active CONSTANT public.subscription_status_t := 'ACTIVE';
    c_err_state CONSTANT TEXT := '55000';
    c_err_constraint CONSTANT TEXT := '23514';
    v_payment public.payment%ROWTYPE;
    v_charge public.billing_charge%ROWTYPE;
    v_order public.billing_order%ROWTYPE;
    v_subscription public.establishment_subscription%ROWTYPE;
    v_previous_cycle public.subscription_cycle%ROWTYPE;
    v_cycle public.subscription_cycle%ROWTYPE;
    v_existing_application public.payment_application%ROWTYPE;
    v_cycle_start TIMESTAMPTZ;
    v_cycle_end TIMESTAMPTZ;
    v_applied_at TIMESTAMPTZ;
    v_local_start TIMESTAMP WITHOUT TIME ZONE;
    v_next_month TIMESTAMP WITHOUT TIME ZONE;
    v_next_month_last_day INTEGER;
    v_anchor_day SMALLINT;
    v_anchor_local_time TIME;
    v_anchor_timezone VARCHAR(100) := 'America/Sao_Paulo';
    v_cycle_number INTEGER;
    v_refund_reason public.refund_reason_t;
BEGIN
    SELECT p.* INTO v_payment
    FROM public.payment AS p
    WHERE p.id = p_payment_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'O pagamento % não existe.', p_payment_id
            USING ERRCODE = 'P0002';
    END IF;
    IF v_payment.verified_at IS NULL OR v_payment.verified_at > clock_timestamp() THEN
        RAISE EXCEPTION 'O pagamento ainda não foi verificado.'
            USING ERRCODE = c_err_state;
    END IF;

    -- Reexecução do mesmo pagamento retorna o benefício anteriormente aplicado.
    SELECT pa.* INTO v_existing_application
    FROM public.payment_application AS pa
    WHERE pa.payment_id = p_payment_id;
    IF FOUND THEN
        p_payment_application_id := v_existing_application.id;
        p_cycle_id := v_existing_application.cycle_id;
        SELECT pr.id INTO p_refund_id
        FROM public.payment_refund AS pr
        WHERE pr.payment_id = p_payment_id;
        RETURN;
    END IF;

    SELECT bc.* INTO v_charge
    FROM public.billing_charge AS bc
    WHERE bc.id = v_payment.billing_charge_id
    FOR UPDATE;
    SELECT bo.* INTO v_order
    FROM public.billing_order AS bo
    WHERE bo.id = v_payment.billing_order_id
    FOR UPDATE;
    SELECT es.* INTO v_subscription
    FROM public.establishment_subscription AS es
    WHERE es.id = v_order.subscription_id
      AND es.establishment_id = v_order.establishment_id
    FOR UPDATE;

    IF v_payment.establishment_id <> v_order.establishment_id
       OR v_charge.establishment_id <> v_order.establishment_id
       OR v_charge.billing_order_id <> v_order.id
       OR v_charge.purpose <> v_order.purpose
       OR v_payment.amount <> v_charge.amount
       OR v_payment.currency <> 'BRL' THEN
        RAISE EXCEPTION 'Os dados do pagamento, da cobrança e do pedido não são compatíveis.'
            USING ERRCODE = c_err_constraint;
    END IF;
    -- Outro pagamento para o mesmo pedido representa benefício duplicado.
    SELECT pa.* INTO v_existing_application
    FROM public.payment_application AS pa
    WHERE pa.billing_order_id = v_order.id
    FOR UPDATE;
    IF FOUND THEN
        INSERT INTO public.payment_refund (
            payment_id, reason, amount, provider, requested_at
        ) VALUES (
            p_payment_id, 'DUPLICATE_BENEFIT'::public.refund_reason_t,
            v_payment.amount, v_payment.provider, clock_timestamp()
        )
        ON CONFLICT (payment_id) DO NOTHING
        RETURNING id INTO p_refund_id;
        IF p_refund_id IS NULL THEN
            SELECT pr.id INTO p_refund_id
            FROM public.payment_refund AS pr
            WHERE pr.payment_id = p_payment_id;
        END IF;
        p_payment_application_id := v_existing_application.id;
        p_cycle_id := v_existing_application.cycle_id;
        UPDATE public.billing_charge AS bc
        SET status = 'PAID'::public.charge_status_t,
            closed_at = COALESCE(bc.closed_at, clock_timestamp()),
            updated_at = clock_timestamp()
        WHERE bc.id = v_charge.id
          AND bc.status <> 'PAID'::public.charge_status_t;
        RETURN;
    END IF;

    v_applied_at := clock_timestamp();

    IF v_order.purpose = 'UPGRADE'::public.billing_purpose_t THEN
        SELECT sc.* INTO v_cycle
        FROM public.subscription_cycle AS sc
        WHERE sc.id = v_charge.target_cycle_id
          AND sc.establishment_id = v_order.establishment_id
        FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'O ciclo de destino do upgrade não existe.'
                USING ERRCODE = 'P0002';
        END IF;

        IF v_applied_at < v_cycle.starts_at OR v_applied_at >= v_cycle.ends_at THEN
            INSERT INTO public.payment_refund (
                payment_id, reason, amount, provider, requested_at
            ) VALUES (
                p_payment_id, 'EXPIRED_UPGRADE'::public.refund_reason_t,
                v_payment.amount, v_payment.provider, v_applied_at
            )
            ON CONFLICT (payment_id) DO NOTHING
            RETURNING id INTO p_refund_id;
            IF p_refund_id IS NULL THEN
                SELECT pr.id INTO p_refund_id
                FROM public.payment_refund AS pr
                WHERE pr.payment_id = p_payment_id;
            END IF;
            UPDATE public.billing_charge AS bc
            SET status = 'PAID'::public.charge_status_t,
                closed_at = COALESCE(bc.closed_at, v_applied_at),
                updated_at = v_applied_at
            WHERE bc.id = v_charge.id
              AND bc.status <> 'PAID'::public.charge_status_t;
            RETURN;
        END IF;

        -- Dinheiro recebido e concessão do benefício são decisões distintas.
        -- Os reembolsos previstos acima continuam acessíveis em cobranças encerradas.
        IF v_charge.status IN ('CANCELLED'::public.charge_status_t, 'EXPIRED'::public.charge_status_t) THEN
            RAISE EXCEPTION 'Pagamento confirmado em cobrança encerrada sem hipótese de reembolso prevista. Encaminhe para análise administrativa; nenhum benefício foi concedido.'
                USING ERRCODE = c_err_state;
        END IF;

        IF v_charge.quoted_monthly_price <= v_cycle.monthly_price
           OR v_charge.quoted_volume_limit_liters <= v_cycle.volume_limit_liters
           OR v_charge.quoted_collection_limit <= v_cycle.collection_limit THEN
            RAISE EXCEPTION 'O upgrade deve aumentar o preço e os dois limites do ciclo.'
                USING ERRCODE = c_err_constraint;
        END IF;

        INSERT INTO public.payment_application (
            payment_id, billing_order_id, establishment_id, purpose,
            cycle_id, applied_at, created_at
        ) VALUES (
            p_payment_id, v_order.id, v_order.establishment_id,
            v_order.purpose, v_cycle.id, v_applied_at, v_applied_at
        ) RETURNING id INTO p_payment_application_id;

        INSERT INTO public.subscription_cycle_change (
            cycle_id, payment_application_id,
            from_plan_id, to_plan_id,
            from_monthly_price, to_monthly_price,
            from_volume_limit_liters, to_volume_limit_liters,
            from_collection_limit, to_collection_limit, applied_at, created_at
        ) VALUES (
            v_cycle.id, p_payment_application_id,
            v_cycle.plan_id, v_charge.plan_id,
            v_cycle.monthly_price, v_charge.quoted_monthly_price,
            v_cycle.volume_limit_liters, v_charge.quoted_volume_limit_liters,
            v_cycle.collection_limit, v_charge.quoted_collection_limit,
            v_applied_at, v_applied_at
        );

        UPDATE public.subscription_cycle AS sc
        SET plan_id = v_charge.plan_id,
            plan_name = v_charge.plan_name,
            plan_description = v_charge.plan_description,
            monthly_price = v_charge.quoted_monthly_price,
            volume_limit_liters = v_charge.quoted_volume_limit_liters,
            collection_limit = v_charge.quoted_collection_limit,
            updated_at = v_applied_at
        WHERE sc.id = v_cycle.id;
        p_cycle_id := v_cycle.id;

    ELSE
        IF v_charge.status IN ('CANCELLED'::public.charge_status_t, 'EXPIRED'::public.charge_status_t) THEN
            RAISE EXCEPTION 'Pagamento confirmado em cobrança encerrada sem hipótese de reembolso prevista. Encaminhe para análise administrativa; nenhum benefício foi concedido.'
                USING ERRCODE = c_err_state;
        END IF;

        IF v_order.purpose = 'INITIAL'::public.billing_purpose_t THEN
            IF v_subscription.status = c_subscription_active THEN
                RAISE EXCEPTION 'A assinatura já está ativa; não é possível aplicar um pagamento inicial novamente.'
                    USING ERRCODE = c_err_state;
            END IF;
            IF EXISTS (
                SELECT 1 FROM public.establishment_subscription AS es
                WHERE es.establishment_id = v_order.establishment_id
                  AND es.status = c_subscription_active
                  AND es.id <> v_subscription.id
            ) THEN
                RAISE EXCEPTION 'O estabelecimento já possui outra assinatura ativa.'
                    USING ERRCODE = '23505';
            END IF;
            v_cycle_start := v_applied_at;
            v_local_start := v_cycle_start AT TIME ZONE v_anchor_timezone;
            v_anchor_day := EXTRACT(DAY FROM v_local_start)::SMALLINT;
            v_anchor_local_time := v_local_start::TIME;
        ELSE
            IF v_order.previous_cycle_id IS NULL THEN
                RAISE EXCEPTION 'A renovação não informa o ciclo anterior.'
                    USING ERRCODE = c_err_constraint;
            END IF;
            SELECT sc.* INTO v_previous_cycle
            FROM public.subscription_cycle AS sc
            WHERE sc.id = v_order.previous_cycle_id
              AND sc.subscription_id = v_subscription.id
              AND sc.establishment_id = v_order.establishment_id
            FOR UPDATE;
            IF NOT FOUND THEN
                RAISE EXCEPTION 'O ciclo anterior da renovação não existe ou pertence a outra assinatura.'
                    USING ERRCODE = 'P0002';
            END IF;
            IF v_previous_cycle.id <> (
                SELECT sc2.id FROM public.subscription_cycle AS sc2
                WHERE sc2.subscription_id = v_subscription.id
                ORDER BY sc2.cycle_number DESC LIMIT 1
            ) THEN
                RAISE EXCEPTION 'A renovação deve partir do ciclo mais recente da assinatura.'
                    USING ERRCODE = c_err_state;
            END IF;

            -- Somente o próximo período pode ser antecipado. A assinatura e
            -- o ciclo estão bloqueados: um ciclo futuro não origina outro.
            IF v_previous_cycle.starts_at > v_applied_at THEN
                RAISE EXCEPTION 'Não é possível renovar um ciclo que ainda não começou. Somente o próximo período pode ser antecipado.'
                    USING ERRCODE = c_err_state;
            END IF;

            IF v_applied_at <= v_previous_cycle.ends_at THEN
                v_cycle_start := v_previous_cycle.ends_at;
                v_anchor_day := v_previous_cycle.anchor_day;
                v_anchor_local_time := v_previous_cycle.anchor_local_time;
                v_anchor_timezone := v_previous_cycle.anchor_timezone;
            ELSE
                -- Após interrupção, o novo ciclo começa na liberação atual e
                -- redefine a referência local sem cobrar período retroativo.
                v_cycle_start := v_applied_at;
                v_anchor_timezone := 'America/Sao_Paulo';
                v_local_start := v_cycle_start AT TIME ZONE v_anchor_timezone;
                v_anchor_day := EXTRACT(DAY FROM v_local_start)::SMALLINT;
                v_anchor_local_time := v_local_start::TIME;
            END IF;
        END IF;

        v_local_start := v_cycle_start AT TIME ZONE v_anchor_timezone;
        v_next_month := date_trunc('month', v_local_start) + INTERVAL '1 month';
        v_next_month_last_day := EXTRACT(
            DAY FROM (v_next_month + INTERVAL '1 month' - INTERVAL '1 day')
        )::INTEGER;
        v_cycle_end := (
            v_next_month::DATE
            + (LEAST(v_anchor_day, v_next_month_last_day) - 1)
            + v_anchor_local_time
        ) AT TIME ZONE v_anchor_timezone;

        SELECT (COALESCE(MAX(sc.cycle_number), 0) + 1)::INTEGER
        INTO v_cycle_number
        FROM public.subscription_cycle AS sc
        WHERE sc.subscription_id = v_subscription.id;

        INSERT INTO public.subscription_cycle (
            subscription_id, establishment_id, cycle_number,
            starts_at, ends_at, anchor_day, anchor_local_time, anchor_timezone,
            plan_id, plan_name, plan_description, monthly_price,
            volume_limit_liters, collection_limit
        ) VALUES (
            v_subscription.id, v_order.establishment_id, v_cycle_number,
            v_cycle_start, v_cycle_end, v_anchor_day, v_anchor_local_time,
            v_anchor_timezone, v_charge.plan_id, v_charge.plan_name,
            v_charge.plan_description, v_charge.quoted_monthly_price,
            v_charge.quoted_volume_limit_liters, v_charge.quoted_collection_limit
        ) RETURNING id INTO p_cycle_id;

        INSERT INTO public.payment_application (
            payment_id, billing_order_id, establishment_id, purpose,
            cycle_id, applied_at, created_at
        ) VALUES (
            p_payment_id, v_order.id, v_order.establishment_id,
            v_order.purpose, p_cycle_id, v_applied_at, v_applied_at
        ) RETURNING id INTO p_payment_application_id;

        UPDATE public.establishment_subscription AS es
        SET status = c_subscription_active,
            activated_at = COALESCE(es.activated_at, v_cycle_start),
            inactivated_at = NULL,
            updated_at = v_applied_at
        WHERE es.id = v_subscription.id;
    END IF;

    UPDATE public.billing_charge AS bc
    SET status = 'PAID'::public.charge_status_t,
        closed_at = COALESCE(bc.closed_at, v_applied_at),
        updated_at = v_applied_at
    WHERE bc.id = v_charge.id
      AND bc.status <> 'PAID'::public.charge_status_t;
END;
$$;

COMMENT ON PROCEDURE public.apply_verified_payment(UUID) IS
'Aplica uma única vez o pagamento verificado; inicia/renova ciclos, registra upgrades ou solicita o reembolso previsto.';

-- =============================================================================
-- 4. Criação idempotente de solicitação B2B
-- =============================================================================

CREATE OR REPLACE PROCEDURE public.create_collection_request(
    IN p_establishment_id UUID,
    IN p_estimated_volume_liters NUMERIC,
    IN p_observation TEXT,
    IN p_idempotency_key UUID,
    OUT p_collection_request_id UUID
)
LANGUAGE plpgsql
AS $$
DECLARE
    c_active CONSTANT public.active_status_t := 'ACTIVE';
    c_subscription_active CONSTANT public.subscription_status_t := 'ACTIVE';
    c_establishment CONSTANT public.user_type_t := 'ESTABLISHMENT';
    c_err_parameter CONSTANT TEXT := '22023';
    c_err_authorization CONSTANT TEXT := '42501';
    c_err_state CONSTANT TEXT := '55000';
    c_err_constraint CONSTANT TEXT := '23514';
    c_err_idempotency CONSTANT TEXT := '22000';
    v_existing public.collection_request%ROWTYPE;
    v_original public.collection_request%ROWTYPE;
    v_subscription_id UUID;
    v_cycle_id UUID;
    v_available RECORD;
BEGIN
    IF p_idempotency_key IS NULL THEN
        RAISE EXCEPTION 'A chave de idempotência é obrigatória.'
            USING ERRCODE = c_err_parameter;
    END IF;
    -- Serializa inclusive duas primeiras tentativas concorrentes com a mesma
    -- chave, antes de consultar ou inserir a solicitação.
    PERFORM pg_advisory_xact_lock(hashtextextended(p_idempotency_key::TEXT, 0));
    IF NOT EXISTS (
        SELECT 1 FROM public.users AS u
        WHERE u.id = p_establishment_id
          AND u.user_type = c_establishment
          AND u.status = c_active
    ) THEN
        RAISE EXCEPTION 'O estabelecimento não existe ou não está ativo.' USING ERRCODE = c_err_authorization;
    END IF;

    SELECT cr.* INTO v_existing
    FROM public.collection_request AS cr
    WHERE cr.establishment_id = p_establishment_id
      AND cr.idempotency_key = p_idempotency_key;
    IF FOUND THEN
        -- O snapshot INSERT preserva os dados enviados originalmente, mesmo
        -- quando o estado atual já foi agendado, cancelado ou corrigido.
        SELECT (jsonb_populate_record(NULL::public.collection_request, to_jsonb(l))).*
        INTO v_original
        FROM public.collection_request_log AS l
        WHERE l.id = v_existing.id AND l.operation = 'INSERT' AND l.snapshot_kind = 'AFTER'
        ORDER BY l.performed_at, l.log_id LIMIT 1;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'O histórico original da solicitação não está disponível para validar o reenvio.'
                USING ERRCODE = c_err_state;
        END IF;
        IF v_original.estimated_volume_liters IS DISTINCT FROM p_estimated_volume_liters
           OR v_original.observation IS DISTINCT FROM p_observation THEN
            RAISE EXCEPTION 'Conflito de idempotência: a chave já foi utilizada com dados diferentes.'
                USING ERRCODE = c_err_idempotency;
        END IF;
        p_collection_request_id := v_existing.id;
        RETURN;
    END IF;

    IF p_estimated_volume_liters IS NULL OR p_estimated_volume_liters <= 0
       OR p_estimated_volume_liters IN ('NaN'::NUMERIC, 'Infinity'::NUMERIC, '-Infinity'::NUMERIC)
       OR p_estimated_volume_liters <> ROUND(p_estimated_volume_liters, 2) THEN
        RAISE EXCEPTION 'O volume estimado deve ser positivo, finito e ter no máximo duas casas decimais.'
            USING ERRCODE = c_err_parameter;
    END IF;
    IF p_observation IS NOT NULL AND BTRIM(p_observation) = '' THEN
        RAISE EXCEPTION 'A observação deve ser omitida ou conter texto.'
            USING ERRCODE = c_err_parameter;
    END IF;
    -- O bloqueio do perfil serializa solicitações e protege a leitura de saldo.
    PERFORM 1 FROM public.establishment AS e
    WHERE e.id = p_establishment_id FOR UPDATE;
    SELECT es.id INTO v_subscription_id
    FROM public.establishment_subscription AS es
    WHERE es.establishment_id = p_establishment_id
      AND es.status = c_subscription_active
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'O estabelecimento não possui assinatura ativa.'
            USING ERRCODE = c_err_state;
    END IF;

    SELECT sc.id INTO v_cycle_id
    FROM public.subscription_cycle AS sc
    WHERE sc.subscription_id = v_subscription_id
      AND sc.establishment_id = p_establishment_id
      AND CURRENT_TIMESTAMP >= sc.starts_at
      AND CURRENT_TIMESTAMP < sc.ends_at
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Não existe ciclo pago vigente para a assinatura.'
            USING ERRCODE = c_err_state;
    END IF;

    SELECT a.* INTO v_available
    FROM public.get_cycle_availability(v_cycle_id) AS a;
    IF NOT v_available.can_accept_new_request
       OR v_available.available_volume_liters < p_estimated_volume_liters
       OR v_available.available_collection_slots < 1 THEN
        RAISE EXCEPTION 'O ciclo não possui litros e vagas suficientes para esta solicitação.'
            USING ERRCODE = c_err_constraint;
    END IF;

    INSERT INTO public.collection_request (
        estimated_volume_liters, observation, establishment_id,
        subscription_cycle_id, idempotency_key
    ) VALUES (
        p_estimated_volume_liters, p_observation, p_establishment_id,
        v_cycle_id, p_idempotency_key
    ) RETURNING id INTO p_collection_request_id;
END;
$$;

COMMENT ON PROCEDURE public.create_collection_request(UUID, NUMERIC, TEXT, UUID) IS
'Cria uma solicitação PENDING e reserva litros/vaga no ciclo vigente, serializando solicitações e respeitando a chave idempotente.';

-- =============================================================================
-- 5. Agendamento e reagendamento
-- =============================================================================

CREATE OR REPLACE PROCEDURE public.schedule_collection_request(
    IN p_collection_request_id UUID,
    IN p_scheduled_at TIMESTAMPTZ,
    IN p_agreed_at TIMESTAMPTZ,
    IN p_admin_id UUID,
    IN p_reason TEXT
)
LANGUAGE plpgsql
AS $$
DECLARE
    c_active CONSTANT public.active_status_t := 'ACTIVE';
    c_request_pending CONSTANT public.request_status_t := 'PENDING';
    c_request_approved CONSTANT public.request_status_t := 'APPROVED';
    c_admin CONSTANT public.user_type_t := 'ADMIN';
    c_err_parameter CONSTANT TEXT := '22023';
    c_err_authorization CONSTANT TEXT := '42501';
    c_err_state CONSTANT TEXT := '55000';
    c_msg_admin CONSTANT TEXT := 'O administrador informado não existe ou não está ativo.';
    v_request public.collection_request%ROWTYPE;
    v_now TIMESTAMPTZ;
BEGIN
    IF p_scheduled_at IS NULL OR NOT isfinite(p_scheduled_at)
       OR p_agreed_at IS NULL OR NOT isfinite(p_agreed_at)
       OR p_reason IS NULL OR BTRIM(p_reason) = '' THEN
        RAISE EXCEPTION 'Horário agendado, horário do acordo e motivo não podem estar vazios.'
            USING ERRCODE = c_err_parameter;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.users AS u
        WHERE u.id = p_admin_id
          AND u.user_type = c_admin
          AND u.status = c_active
    ) THEN
        RAISE EXCEPTION '%', c_msg_admin
            USING ERRCODE = c_err_authorization;
    END IF;

    SELECT cr.* INTO v_request
    FROM public.collection_request AS cr
    WHERE cr.id = p_collection_request_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'A solicitação % não existe.', p_collection_request_id
            USING ERRCODE = 'P0002';
    END IF;
    IF v_request.status NOT IN (
        c_request_pending,
        c_request_approved
    ) OR EXISTS (
        SELECT 1 FROM public.collection AS c
        WHERE c.collection_request_id = v_request.id
    ) THEN
        RAISE EXCEPTION 'A solicitação não está disponível para agendamento.'
            USING ERRCODE = c_err_state;
    END IF;
    IF v_request.arrived_at IS NOT NULL THEN
        RAISE EXCEPTION 'Não é possível agendar ou reagendar após a chegada registrada.'
            USING ERRCODE = c_err_state;
    END IF;
    IF p_agreed_at > clock_timestamp() THEN
        RAISE EXCEPTION 'O instante do acordo não pode estar no futuro.'
            USING ERRCODE = c_err_parameter;
    END IF;

    -- Reenvio do mesmo horário é inofensivo e não cria uma linha de histórico.
    IF v_request.status = c_request_approved
       AND v_request.scheduled_at = p_scheduled_at THEN
        RETURN;
    END IF;

    v_now := clock_timestamp();
    INSERT INTO public.collection_schedule_history (
        collection_request_id, previous_scheduled_at, new_scheduled_at,
        agreed_at, changed_at, changed_by, reason
    ) VALUES (
        v_request.id, v_request.scheduled_at, p_scheduled_at,
        p_agreed_at, v_now, p_admin_id, BTRIM(p_reason)
    );

    UPDATE public.collection_request AS cr
    SET status = c_request_approved,
        scheduled_at = p_scheduled_at,
        approved_by = COALESCE(cr.approved_by, p_admin_id),
        approved_at = COALESCE(cr.approved_at, v_now)
    WHERE cr.id = v_request.id;
END;
$$;

COMMENT ON PROCEDURE public.schedule_collection_request(UUID, TIMESTAMPTZ, TIMESTAMPTZ, UUID, TEXT) IS
'Agenda ou reagenda pedido não atendido, registra o acordo informado e preserva cada alteração no histórico.';

-- =============================================================================
-- 6. Cancelamento de solicitação
-- =============================================================================

CREATE OR REPLACE PROCEDURE public.cancel_collection_request(
    IN p_collection_request_id UUID,
    IN p_cancelled_by UUID,
    IN p_cancellation_initiative public.cancellation_initiative_t,
    IN p_cancellation_reason TEXT
)
LANGUAGE plpgsql AS $$
DECLARE
    c_active CONSTANT public.active_status_t := 'ACTIVE';
    c_request_pending CONSTANT public.request_status_t := 'PENDING';
    c_request_approved CONSTANT public.request_status_t := 'APPROVED';
    c_request_cancelled CONSTANT public.request_status_t := 'CANCELLED';
    c_admin CONSTANT public.user_type_t := 'ADMIN';
    c_establishment CONSTANT public.user_type_t := 'ESTABLISHMENT';
    c_err_parameter CONSTANT TEXT := '22023';
    c_err_authorization CONSTANT TEXT := '42501';
    c_err_state CONSTANT TEXT := '55000';
    v_request public.collection_request%ROWTYPE;
    v_user_type public.user_type_t;
    v_now TIMESTAMPTZ;
    v_policy public.cancellation_policy_t;
    v_forfeited_volume NUMERIC := 0;
    v_forfeited_slots INTEGER := 0;
    v_on_time_arrival BOOLEAN;
BEGIN
    IF p_cancellation_reason IS NULL OR BTRIM(p_cancellation_reason) = '' THEN
        RAISE EXCEPTION 'O motivo do cancelamento é obrigatório.' USING ERRCODE = c_err_parameter;
    END IF;
    SELECT u.user_type INTO v_user_type FROM public.users u
    WHERE u.id = p_cancelled_by AND u.status = c_active FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'O responsável pelo cancelamento não existe ou não está ativo.' USING ERRCODE = c_err_authorization; END IF;
    SELECT cr.* INTO v_request FROM public.collection_request cr
    WHERE cr.id = p_collection_request_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'A solicitação % não existe.', p_collection_request_id USING ERRCODE = 'P0002'; END IF;
    IF v_request.status = c_request_cancelled THEN RETURN; END IF;
    IF v_request.status NOT IN (c_request_pending, c_request_approved)
       OR EXISTS (SELECT 1 FROM public.collection c WHERE c.collection_request_id = v_request.id) THEN
        RAISE EXCEPTION 'Somente uma solicitação ainda não atendida pode ser cancelada.' USING ERRCODE = c_err_state;
    END IF;
    v_now := clock_timestamp();
    IF p_cancellation_initiative = 'ESTABLISHMENT'::public.cancellation_initiative_t THEN
        IF v_user_type <> c_establishment OR p_cancelled_by <> v_request.establishment_id THEN
            RAISE EXCEPTION 'O cancelamento pelo estabelecimento exige a identidade do próprio estabelecimento.' USING ERRCODE = c_err_authorization;
        END IF;
    ELSIF p_cancellation_initiative = 'OPERATION'::public.cancellation_initiative_t THEN
        IF v_user_type <> c_admin THEN RAISE EXCEPTION 'O cancelamento operacional exige administrador ativo.' USING ERRCODE = c_err_authorization; END IF;
    ELSE
        RAISE EXCEPTION 'A iniciativa de cancelamento é inválida.' USING ERRCODE = c_err_parameter;
    END IF;
    IF v_request.service_accepted_at IS NOT NULL THEN
        RAISE EXCEPTION 'Não é possível cancelar após o aceite do serviço.' USING ERRCODE = c_err_state;
    END IF;
    v_on_time_arrival := v_request.arrived_at IS NOT NULL AND v_request.scheduled_at IS NOT NULL
        AND v_request.arrived_at < v_request.scheduled_at + INTERVAL '1 hour'
        AND v_request.arrival_recorded_at <= v_now;
    IF v_request.scheduled_at IS NULL
       OR v_now <= v_request.scheduled_at - INTERVAL '4 hours'
       OR (v_now >= v_request.scheduled_at + INTERVAL '1 hour' AND NOT COALESCE(v_on_time_arrival, FALSE)) THEN
        v_policy := 'FREE'::public.cancellation_policy_t;
    ELSIF p_cancellation_initiative = 'ESTABLISHMENT'::public.cancellation_initiative_t THEN
        v_policy := 'LATE_FORFEITURE'::public.cancellation_policy_t;
        v_forfeited_volume := v_request.estimated_volume_liters;
        v_forfeited_slots := 1;
    ELSE
        v_policy := 'FREE'::public.cancellation_policy_t;
    END IF;
    UPDATE public.collection_request cr
    SET status = c_request_cancelled,
        cancelled_at = v_now, cancellation_recorded_at = v_now,
        cancelled_by = p_cancelled_by, cancellation_initiative = p_cancellation_initiative,
        cancellation_policy = v_policy, cancellation_reason = BTRIM(p_cancellation_reason),
        forfeited_volume_liters = v_forfeited_volume,
        forfeited_collection_slots = v_forfeited_slots
    WHERE cr.id = v_request.id;
END;
$$;

COMMENT ON PROCEDURE public.cancel_collection_request(UUID, UUID, public.cancellation_initiative_t, TEXT) IS
'Cancela pedido não atendido, valida o iniciador e aplica a política de perda por cancelamento tardio.';

-- =============================================================================
-- 7. Registro de coleta B2B e motivos de insucesso
-- =============================================================================

CREATE OR REPLACE PROCEDURE public.record_collection(
    IN p_collection_request_id UUID,
    IN p_driver_id UUID,
    IN p_collected_volume_liters NUMERIC,
    IN p_presented_volume_liters NUMERIC,
    IN p_oil_condition public.oil_condition_t,
    IN p_has_compromising_occurrence BOOLEAN,
    IN p_failure_reason_codes TEXT[],
    IN p_observation TEXT,
    IN p_collection_date TIMESTAMPTZ,
    OUT p_collection_id UUID
)
LANGUAGE plpgsql AS $$
DECLARE
    c_volume_lower CONSTANT NUMERIC := 0.90;
    c_volume_upper CONSTANT NUMERIC := 1.10;
    c_failure_penalty CONSTANT BIGINT := -50;
    c_success_bonus CONSTANT BIGINT := 50;
    c_recurrence_cap CONSTANT BIGINT := 100;
    c_recurrence_interval CONSTANT BIGINT := 10;
    c_active CONSTANT public.active_status_t := 'ACTIVE';
    c_request_approved CONSTANT public.request_status_t := 'APPROVED';
    c_unsuccessful CONSTANT public.collection_result_t := 'UNSUCCESSFUL';
    c_successful CONSTANT public.collection_result_t := 'SUCCESSFUL';
    c_recorded CONSTANT public.record_status_t := 'RECORDED';
    c_not_assessed CONSTANT public.oil_condition_t := 'NOT_ASSESSED';
    c_unacceptable CONSTANT public.oil_condition_t := 'UNACCEPTABLE';
    c_acceptable CONSTANT public.oil_condition_t := 'ACCEPTABLE';
    c_err_parameter CONSTANT TEXT := '22023';
    c_err_authorization CONSTANT TEXT := '42501';
    c_err_state CONSTANT TEXT := '55000';
    c_err_constraint CONSTANT TEXT := '23514';
    c_err_idempotency CONSTANT TEXT := '22000';
    c_reason_volume CONSTANT TEXT := 'VOLUME_OUT_OF_TOLERANCE';
    c_reason_oil CONSTANT TEXT := 'OIL_UNACCEPTABLE';
    c_reason_occurrence CONSTANT TEXT := 'COMPROMISING_OCCURRENCE';
    v_request public.collection_request%ROWTYPE;
    v_driver public.driver%ROWTYPE;
    v_original public.collection%ROWTYPE;
    v_original_codes TEXT[];
    v_result public.collection_result_t;
    v_volume_out BOOLEAN;
    v_codes TEXT[] := COALESCE(p_failure_reason_codes, ARRAY[]::TEXT[]);
    v_order BIGINT;
    v_found_count INTEGER;
    v_success_count BIGINT;
    v_points BIGINT;
BEGIN
    -- Não usar ANY/COUNT antes de rejeitar NULLs: SQL usa lógica de três valores.
    IF EXISTS (SELECT 1 FROM unnest(v_codes) AS r(code) WHERE code IS NULL OR BTRIM(code) = '') THEN
        RAISE EXCEPTION 'A lista de motivos não pode conter elementos nulos ou vazios.' USING ERRCODE = c_err_parameter;
    END IF;
    SELECT COALESCE(array_agg(DISTINCT code ORDER BY code), ARRAY[]::TEXT[])
    INTO v_codes FROM unnest(v_codes) AS r(code);
    IF p_collected_volume_liters IS NULL OR p_collected_volume_liters < 0
       OR p_collected_volume_liters IN ('NaN'::NUMERIC, 'Infinity'::NUMERIC, '-Infinity'::NUMERIC)
       OR p_collected_volume_liters <> ROUND(p_collected_volume_liters, 2)
       OR p_presented_volume_liters IS NULL OR p_presented_volume_liters < 0
       OR p_presented_volume_liters IN ('NaN'::NUMERIC, 'Infinity'::NUMERIC, '-Infinity'::NUMERIC)
       OR p_presented_volume_liters <> ROUND(p_presented_volume_liters, 2)
       OR p_collected_volume_liters > p_presented_volume_liters
       OR p_collection_date IS NULL OR NOT isfinite(p_collection_date)
       OR p_collection_date > clock_timestamp() OR p_has_compromising_occurrence IS NULL
       OR p_oil_condition IS NULL
       OR (p_presented_volume_liters = 0 AND p_oil_condition <> c_not_assessed)
       OR (p_presented_volume_liters > 0 AND p_oil_condition = c_not_assessed)
       OR (p_observation IS NOT NULL AND BTRIM(p_observation) = '') THEN
        RAISE EXCEPTION 'Volume, data, ocorrência ou observação informados são inválidos.' USING ERRCODE = c_err_parameter;
    END IF;
    SELECT d.* INTO v_driver FROM public.driver d WHERE d.id = p_driver_id FOR SHARE;
    IF NOT FOUND OR v_driver.status <> c_active THEN
        RAISE EXCEPTION 'O motorista não existe ou não está ativo.' USING ERRCODE = c_err_authorization;
    END IF;
    -- Ordem global B2B: estabelecimento, solicitação, evento/cálculos.
    SELECT cr.* INTO v_request FROM public.collection_request cr
    WHERE cr.id = p_collection_request_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'A solicitação não existe.' USING ERRCODE = 'P0002'; END IF;
    PERFORM 1 FROM public.establishment e WHERE e.id = v_request.establishment_id FOR UPDATE;
    SELECT cr.* INTO v_request FROM public.collection_request cr
    WHERE cr.id = p_collection_request_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'A solicitação não existe.' USING ERRCODE = 'P0002'; END IF;
    SELECT c.id INTO p_collection_id FROM public.collection c WHERE c.collection_request_id = v_request.id;
    IF FOUND THEN
        SELECT (jsonb_populate_record(NULL::public.collection, to_jsonb(l))).*
        INTO v_original FROM public.collection_log AS l
        WHERE l.id = p_collection_id AND l.operation = 'INSERT' AND l.snapshot_kind = 'AFTER'
        ORDER BY l.performed_at, l.log_id LIMIT 1;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'O histórico original da coleta não está disponível para validar o reenvio.' USING ERRCODE = c_err_state;
        END IF;
        -- As associações originais são os INSERTs anteriores à primeira
        -- alteração administrativa da revisão. A correção atualiza o evento
        -- antes de substituir motivos, preservando essa fronteira no histórico.
        SELECT COALESCE(array_agg(DISTINCT cfr.code::TEXT ORDER BY cfr.code::TEXT), ARRAY[]::TEXT[])
        INTO v_original_codes
        FROM public.collection_failure_log AS fl
        JOIN public.collection_failure_reason AS cfr ON cfr.id = fl.failure_reason_id
        WHERE fl.collection_id = p_collection_id AND fl.operation = 'INSERT' AND fl.snapshot_kind = 'AFTER'
          AND fl.performed_at < LEAST(
              COALESCE((SELECT MIN(cl.performed_at) FROM public.collection_log AS cl
                        WHERE cl.id = p_collection_id AND cl.operation = 'UPDATE' AND cl.snapshot_kind = 'BEFORE'
                          AND 'revision' = ANY(cl.changed_columns)), 'infinity'::TIMESTAMPTZ),
              COALESCE((SELECT MIN(dl.performed_at) FROM public.collection_failure_log AS dl
                        WHERE dl.collection_id = p_collection_id AND dl.operation = 'DELETE'), 'infinity'::TIMESTAMPTZ)
          );
        IF v_original.result = c_successful THEN
            v_original_codes := ARRAY[]::TEXT[];
        END IF;
        IF v_original.driver_id IS DISTINCT FROM p_driver_id
           OR v_original.collected_volume_liters IS DISTINCT FROM p_collected_volume_liters
           OR v_original.presented_volume_liters IS DISTINCT FROM p_presented_volume_liters
           OR v_original.oil_condition IS DISTINCT FROM p_oil_condition
           OR v_original.has_compromising_occurrence IS DISTINCT FROM p_has_compromising_occurrence
           OR v_original.observation IS DISTINCT FROM p_observation
           OR v_original.collection_date IS DISTINCT FROM p_collection_date
           OR v_original_codes IS DISTINCT FROM v_codes THEN
            RAISE EXCEPTION 'Conflito de idempotência: a solicitação já possui coleta com dados originais diferentes.' USING ERRCODE = c_err_idempotency;
        END IF;
        RETURN;
    END IF;
    IF v_request.status <> c_request_approved OR v_request.scheduled_at IS NULL THEN
        RAISE EXCEPTION 'A solicitação precisa estar aprovada e agendada.' USING ERRCODE = c_err_state;
    END IF;
    IF v_request.arrival_driver_id IS DISTINCT FROM p_driver_id
       OR v_request.service_accepted_at IS NULL
       OR v_request.service_accepted_by IS DISTINCT FROM v_request.establishment_id THEN
        RAISE EXCEPTION 'A coleta exige motorista designado e aceite do estabelecimento.' USING ERRCODE = c_err_authorization;
    END IF;
    v_volume_out := p_collected_volume_liters < v_request.estimated_volume_liters * c_volume_lower
                 OR p_collected_volume_liters > v_request.estimated_volume_liters * c_volume_upper;
    v_result := CASE WHEN NOT v_volume_out
        AND p_oil_condition = c_acceptable
        AND NOT p_has_compromising_occurrence
        THEN c_successful
        ELSE c_unsuccessful END;
    IF v_result = c_unsuccessful THEN
        IF cardinality(v_codes) = 0 THEN RAISE EXCEPTION 'Toda coleta malsucedida deve registrar ao menos um motivo.' USING ERRCODE = c_err_constraint; END IF;
        IF v_volume_out AND NOT (c_reason_volume = ANY(v_codes))
           OR p_oil_condition = c_unacceptable AND NOT (c_reason_oil = ANY(v_codes))
           OR p_has_compromising_occurrence AND NOT (c_reason_occurrence = ANY(v_codes)) THEN
            RAISE EXCEPTION 'Os motivos devem corresponder aos fatos que tornaram a coleta malsucedida.' USING ERRCODE = c_err_constraint;
        END IF;
        IF (c_reason_volume = ANY(v_codes) AND NOT v_volume_out)
           OR (c_reason_oil = ANY(v_codes) AND p_oil_condition <> c_unacceptable)
           OR (c_reason_occurrence = ANY(v_codes) AND NOT p_has_compromising_occurrence) THEN
            RAISE EXCEPTION 'Não é permitido registrar motivos que não correspondam aos dados da coleta.' USING ERRCODE = c_err_constraint;
        END IF;
    ELSIF cardinality(v_codes) > 0 THEN
        RAISE EXCEPTION 'Uma coleta bem-sucedida não pode possuir motivos de insucesso.' USING ERRCODE = c_err_constraint;
    END IF;
    IF cardinality(v_codes) > 0 THEN
        SELECT COUNT(DISTINCT cfr.code)::INTEGER INTO v_found_count
        FROM public.collection_failure_reason cfr
        WHERE cfr.code = ANY(v_codes) AND cfr.status = c_active;
        IF v_found_count <> (SELECT COUNT(DISTINCT code) FROM unnest(v_codes) AS reason_codes(code)) THEN
            RAISE EXCEPTION 'Um ou mais motivos de insucesso não existem ou estão inativos.' USING ERRCODE = '23503';
        END IF;
    END IF;
    -- O estabelecimento já está bloqueado; anuladas conservam sua ordem.
    SELECT COALESCE(MAX(c.processing_order), 0) + 1 INTO v_order
    FROM public.collection c WHERE c.establishment_id = v_request.establishment_id;
    IF v_result = c_successful THEN
        SELECT COUNT(*) INTO v_success_count FROM public.collection c
        WHERE c.establishment_id = v_request.establishment_id
          AND c.record_status = c_recorded
          AND c.result = c_successful;
        v_success_count := v_success_count + 1;
        v_points := FLOOR(p_collected_volume_liters)::BIGINT + c_success_bonus
            + CASE WHEN v_success_count % c_recurrence_interval = 0 THEN LEAST(v_success_count, c_recurrence_cap) ELSE 0 END;
    ELSE
        v_points := c_failure_penalty;
    END IF;
    INSERT INTO public.collection (
        collected_volume_liters, presented_volume_liters, oil_condition,
        has_compromising_occurrence, result, points_earned, observation,
        collection_date, collection_request_id, establishment_id,
        processing_order, driver_id, record_status, revision, created_at
    ) VALUES (
        p_collected_volume_liters, p_presented_volume_liters, p_oil_condition,
        p_has_compromising_occurrence, v_result, v_points, p_observation,
        p_collection_date, v_request.id, v_request.establishment_id,
        v_order, p_driver_id, c_recorded, 1, clock_timestamp()
    ) RETURNING id INTO p_collection_id;
    IF cardinality(v_codes) > 0 THEN
        INSERT INTO public.collection_failure (collection_id, failure_reason_id)
        SELECT p_collection_id, cfr.id FROM public.collection_failure_reason cfr
        WHERE cfr.code = ANY(v_codes) AND cfr.status = c_active;
    END IF;
    CALL public.rebuild_user_points(v_request.establishment_id, NULL, NULL);
    CALL public.reconcile_establishment_certificates(v_request.establishment_id);
END;
$$;

COMMENT ON PROCEDURE public.record_collection(UUID, UUID, NUMERIC, NUMERIC, public.oil_condition_t, BOOLEAN, TEXT[], TEXT, TIMESTAMPTZ) IS
'Registra coleta B2B uma única vez, deriva resultado e razões de falha, reconstrói pontos e reconcilia certificados.';

-- =============================================================================
-- 8. Registro idempotente de entrega B2C em PEV
-- =============================================================================

CREATE OR REPLACE PROCEDURE public.record_pev_delivery(
    IN p_idempotency_key UUID,
    IN p_citizen_id UUID,
    IN p_pev_id UUID,
    IN p_validator_id UUID,
    IN p_oil_volume_liters NUMERIC,
    IN p_delivery_date TIMESTAMPTZ,
    OUT p_delivery_pev_id UUID
)
LANGUAGE plpgsql AS $$
DECLARE
    c_active CONSTANT public.active_status_t := 'ACTIVE';
    c_pev_approved CONSTANT public.approval_status_t := 'APPROVED';
    c_recorded CONSTANT public.record_status_t := 'RECORDED';
    c_establishment CONSTANT public.user_type_t := 'ESTABLISHMENT';
    c_citizens CONSTANT public.user_type_t := 'CITIZENS';
    c_err_parameter CONSTANT TEXT := '22023';
    c_err_authorization CONSTANT TEXT := '42501';
    c_err_state CONSTANT TEXT := '55000';
    c_err_idempotency CONSTANT TEXT := '22000';
    v_pev public.pev%ROWTYPE;
    v_original public.delivery_pev%ROWTYPE;
    v_order BIGINT;
BEGIN
    IF p_idempotency_key IS NULL THEN RAISE EXCEPTION 'A chave de idempotência é obrigatória.' USING ERRCODE = c_err_parameter; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(p_idempotency_key::TEXT, 0));
    IF NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = p_validator_id
        AND u.status = c_active
        AND u.user_type IN (c_citizens, c_establishment)) THEN
        RAISE EXCEPTION 'O validador não existe ou não está ativo.' USING ERRCODE = c_err_authorization;
    END IF;
    SELECT d.id INTO p_delivery_pev_id FROM public.delivery_pev d WHERE d.idempotency_key = p_idempotency_key;
    IF FOUND THEN
        SELECT (jsonb_populate_record(NULL::public.delivery_pev, to_jsonb(l))).*
        INTO v_original FROM public.delivery_pev_log AS l
        WHERE l.id = p_delivery_pev_id AND l.operation = 'INSERT' AND l.snapshot_kind = 'AFTER'
        ORDER BY l.performed_at, l.log_id LIMIT 1;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'O histórico original da entrega não está disponível para validar o reenvio.' USING ERRCODE = c_err_state;
        END IF;
        IF v_original.citizen_id IS DISTINCT FROM p_citizen_id
           OR v_original.validated_by IS DISTINCT FROM p_validator_id THEN
            RAISE EXCEPTION 'A chave de idempotência já pertence a outra entrega ou responsável.' USING ERRCODE = c_err_authorization;
        END IF;
        IF v_original.pev_id IS DISTINCT FROM p_pev_id
           OR v_original.oil_volume_liters IS DISTINCT FROM p_oil_volume_liters
           OR v_original.delivery_date IS DISTINCT FROM p_delivery_date THEN
            RAISE EXCEPTION 'Conflito de idempotência: a chave já foi utilizada com dados diferentes.' USING ERRCODE = c_err_idempotency;
        END IF;
        RETURN;
    END IF;
    IF p_oil_volume_liters IS NULL OR p_oil_volume_liters <= 0
       OR p_oil_volume_liters IN ('NaN'::NUMERIC, 'Infinity'::NUMERIC, '-Infinity'::NUMERIC)
       OR p_oil_volume_liters <> ROUND(p_oil_volume_liters, 2) OR p_delivery_date IS NULL
       OR NOT isfinite(p_delivery_date) OR p_delivery_date > clock_timestamp() THEN
        RAISE EXCEPTION 'O volume e a data da entrega devem ser válidos.' USING ERRCODE = c_err_parameter;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.users u JOIN public.citizens c ON c.id = u.id
        WHERE c.id = p_citizen_id AND u.user_type = c_citizens
          AND u.status = c_active) THEN
        RAISE EXCEPTION 'O cidadão beneficiário não existe ou não está ativo.' USING ERRCODE = c_err_authorization;
    END IF;
    IF p_validator_id = p_citizen_id THEN
        RAISE EXCEPTION 'O cidadão beneficiário não pode validar a própria entrega.' USING ERRCODE = c_err_authorization;
    END IF;
    -- Mesmo bloqueio usado pela reconstrução e pela correção B2C.
    -- Chamadas para o mesmo cidadão aguardam a transação anterior.
    PERFORM 1
    FROM public.citizens AS c
    WHERE c.id = p_citizen_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'O cidadão beneficiário não existe.'
            USING ERRCODE = 'P0002';
    END IF;
    SELECT p.* INTO v_pev FROM public.pev p WHERE p.id = p_pev_id FOR SHARE;
    IF NOT FOUND OR v_pev.status <> c_pev_approved THEN
        RAISE EXCEPTION 'O PEV não existe ou não está aprovado.' USING ERRCODE = c_err_authorization;
    END IF;
    -- Inclui anuladas: a identidade de processamento não é reciclada.
    SELECT COALESCE(MAX(d.processing_order), 0) + 1
    INTO v_order
    FROM public.delivery_pev AS d
    WHERE d.citizen_id = p_citizen_id;
    -- O usuário autenticado que chama a rotina é o responsável pelo PEV;
    -- o cidadão QR é apenas o beneficiário da entrega.
    IF v_pev.citizen_id IS NULL AND NOT EXISTS (
        SELECT 1 FROM public.users u WHERE u.id = v_pev.establishment_id
          AND u.user_type = c_establishment
          AND u.status = c_active
    ) THEN RAISE EXCEPTION 'O responsável pelo PEV não existe ou não está ativo.' USING ERRCODE = c_err_authorization; END IF;
    IF p_validator_id IS DISTINCT FROM COALESCE(v_pev.citizen_id, v_pev.establishment_id) THEN
        RAISE EXCEPTION 'O validador autenticado não corresponde ao responsável pelo PEV.' USING ERRCODE = c_err_authorization;
    END IF;
    IF v_pev.citizen_id IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = p_validator_id
            AND u.user_type = c_citizens AND u.status = c_active) THEN
            RAISE EXCEPTION 'O cidadão validador não existe ou não está ativo.' USING ERRCODE = c_err_authorization;
        END IF;
    ELSE
        IF NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = p_validator_id
            AND u.user_type = c_establishment AND u.status = c_active) THEN
            RAISE EXCEPTION 'O estabelecimento validador não existe ou não está ativo.' USING ERRCODE = c_err_authorization;
        END IF;
    END IF;
    INSERT INTO public.delivery_pev (
        oil_volume_liters, points_earned, delivery_date, citizen_id,
        processing_order, pev_id, validated_by, idempotency_key,
        record_status, revision, created_at
    ) VALUES (
        p_oil_volume_liters, FLOOR(p_oil_volume_liters)::BIGINT,
        p_delivery_date, p_citizen_id, v_order, p_pev_id,
        p_validator_id, p_idempotency_key,
        c_recorded, 1, clock_timestamp()
    ) RETURNING id INTO p_delivery_pev_id;
    CALL public.rebuild_user_points(p_citizen_id, NULL, NULL);
END;
$$;

COMMENT ON PROCEDURE public.record_pev_delivery(UUID, UUID, UUID, UUID, NUMERIC, TIMESTAMPTZ) IS
'Registra entrega B2C idempotente para cidadão beneficiário, validando PEV aprovado e titular responsável ativo.';

-- =============================================================================
-- 9. Correção administrativa de coleta B2B por revisão esperada
-- =============================================================================

CREATE OR REPLACE PROCEDURE public.correct_collection(
    IN p_collection_id UUID,
    IN p_expected_revision INTEGER,
    IN p_admin_id UUID,
    IN p_record_status public.record_status_t,
    IN p_collected_volume_liters NUMERIC,
    IN p_presented_volume_liters NUMERIC,
    IN p_oil_condition public.oil_condition_t,
    IN p_has_compromising_occurrence BOOLEAN,
    IN p_failure_reason_codes TEXT[],
    IN p_observation TEXT,
    IN p_correction_reason TEXT
)
LANGUAGE plpgsql AS $$
DECLARE
    c_volume_lower CONSTANT NUMERIC := 0.90;
    c_volume_upper CONSTANT NUMERIC := 1.10;
    c_failure_penalty CONSTANT BIGINT := -50;
    c_success_bonus CONSTANT BIGINT := 50;
    c_recurrence_cap CONSTANT BIGINT := 100;
    c_recurrence_interval CONSTANT BIGINT := 10;
    c_active CONSTANT public.active_status_t := 'ACTIVE';
    c_unsuccessful CONSTANT public.collection_result_t := 'UNSUCCESSFUL';
    c_successful CONSTANT public.collection_result_t := 'SUCCESSFUL';
    c_annulled CONSTANT public.record_status_t := 'ANNULLED';
    c_recorded CONSTANT public.record_status_t := 'RECORDED';
    c_admin CONSTANT public.user_type_t := 'ADMIN';
    c_not_assessed CONSTANT public.oil_condition_t := 'NOT_ASSESSED';
    c_unacceptable CONSTANT public.oil_condition_t := 'UNACCEPTABLE';
    c_acceptable CONSTANT public.oil_condition_t := 'ACCEPTABLE';
    c_err_parameter CONSTANT TEXT := '22023';
    c_err_authorization CONSTANT TEXT := '42501';
    c_err_constraint CONSTANT TEXT := '23514';
    c_reason_volume CONSTANT TEXT := 'VOLUME_OUT_OF_TOLERANCE';
    c_reason_oil CONSTANT TEXT := 'OIL_UNACCEPTABLE';
    c_reason_occurrence CONSTANT TEXT := 'COMPROMISING_OCCURRENCE';
    c_msg_admin CONSTANT TEXT := 'O administrador informado não existe ou não está ativo.';
    v_collection public.collection%ROWTYPE;
    v_request public.collection_request%ROWTYPE;
    v_codes TEXT[] := COALESCE(p_failure_reason_codes, ARRAY[]::TEXT[]);
    v_volume_out BOOLEAN;
    v_result public.collection_result_t;
    v_reason_count INTEGER;
    v_success_count BIGINT;
    v_points BIGINT;
BEGIN
    IF p_expected_revision IS NULL OR p_expected_revision < 1
       OR p_correction_reason IS NULL OR BTRIM(p_correction_reason) = '' THEN
        RAISE EXCEPTION 'A revisão esperada e a justificativa são obrigatórias.' USING ERRCODE = c_err_parameter;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = p_admin_id
        AND u.user_type = c_admin AND u.status = c_active) THEN
        RAISE EXCEPTION '%', c_msg_admin USING ERRCODE = c_err_authorization;
    END IF;
    IF EXISTS (SELECT 1 FROM unnest(v_codes) AS r(code) WHERE code IS NULL OR BTRIM(code) = '') THEN
        RAISE EXCEPTION 'A lista de motivos não pode conter elementos nulos ou vazios.' USING ERRCODE = c_err_parameter;
    END IF;
    SELECT COALESCE(array_agg(DISTINCT code ORDER BY code), ARRAY[]::TEXT[])
    INTO v_codes FROM unnest(v_codes) AS r(code);

    -- A primeira leitura identifica o dono, sem bloquear a coleta antes dele.
    SELECT c.* INTO v_collection FROM public.collection c WHERE c.id = p_collection_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'A coleta % não existe.', p_collection_id USING ERRCODE = 'P0002'; END IF;
    PERFORM 1 FROM public.establishment e WHERE e.id = v_collection.establishment_id FOR UPDATE;
    SELECT c.* INTO v_collection FROM public.collection c WHERE c.id = p_collection_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'A coleta % não existe.', p_collection_id USING ERRCODE = 'P0002'; END IF;
    IF v_collection.revision <> p_expected_revision THEN
        RAISE EXCEPTION 'A coleta mudou desde a leitura. Recarregue o registro antes de corrigir.' USING ERRCODE = '40001';
    END IF;
    SELECT cr.* INTO v_request FROM public.collection_request cr WHERE cr.id = v_collection.collection_request_id;
    IF p_record_status = c_recorded THEN
        IF p_collected_volume_liters IS NULL OR p_collected_volume_liters < 0
           OR p_collected_volume_liters IN ('NaN'::NUMERIC, 'Infinity'::NUMERIC, '-Infinity'::NUMERIC)
           OR p_collected_volume_liters <> ROUND(p_collected_volume_liters, 2) OR p_presented_volume_liters IS NULL
           OR p_presented_volume_liters < 0 OR p_presented_volume_liters IN ('NaN'::NUMERIC, 'Infinity'::NUMERIC, '-Infinity'::NUMERIC)
           OR p_presented_volume_liters <> ROUND(p_presented_volume_liters, 2)
           OR p_collected_volume_liters > p_presented_volume_liters
           OR (p_presented_volume_liters = 0 AND p_oil_condition <> c_not_assessed)
           OR (p_presented_volume_liters > 0 AND p_oil_condition = c_not_assessed)
           OR p_has_compromising_occurrence IS NULL OR p_oil_condition IS NULL
           OR (p_observation IS NOT NULL AND BTRIM(p_observation) = '') THEN
            RAISE EXCEPTION 'Os dados da coleta corrigida são inválidos.' USING ERRCODE = c_err_parameter;
        END IF;
        v_volume_out := p_collected_volume_liters < v_request.estimated_volume_liters * c_volume_lower
                     OR p_collected_volume_liters > v_request.estimated_volume_liters * c_volume_upper;
        v_result := CASE WHEN NOT v_volume_out
            AND p_oil_condition = c_acceptable
            AND NOT p_has_compromising_occurrence
            THEN c_successful ELSE c_unsuccessful END;
        IF v_result = c_unsuccessful AND cardinality(v_codes) = 0 THEN
            RAISE EXCEPTION 'Toda coleta malsucedida deve registrar ao menos um motivo.' USING ERRCODE = c_err_constraint;
        END IF;
        IF (v_volume_out AND NOT (c_reason_volume = ANY(v_codes)))
           OR (p_oil_condition = c_unacceptable AND NOT (c_reason_oil = ANY(v_codes)))
           OR (p_has_compromising_occurrence AND NOT (c_reason_occurrence = ANY(v_codes))) THEN
            RAISE EXCEPTION 'Os motivos devem corresponder aos fatos da coleta corrigida.' USING ERRCODE = c_err_constraint;
        END IF;
        IF (c_reason_volume = ANY(v_codes) AND NOT v_volume_out)
           OR (c_reason_oil = ANY(v_codes) AND p_oil_condition <> c_unacceptable)
           OR (c_reason_occurrence = ANY(v_codes) AND NOT p_has_compromising_occurrence) THEN
            RAISE EXCEPTION 'Não é permitido registrar motivos que não correspondam aos dados corrigidos.' USING ERRCODE = c_err_constraint;
        END IF;
        IF v_result = c_successful AND cardinality(v_codes) > 0 THEN
            RAISE EXCEPTION 'Uma coleta bem-sucedida não pode possuir motivos de insucesso.' USING ERRCODE = c_err_constraint;
        END IF;
    ELSIF p_record_status = c_annulled THEN
        v_result := v_collection.result;
        v_codes := ARRAY[]::TEXT[];
    ELSE
        RAISE EXCEPTION 'A situação corrigida é inválida.' USING ERRCODE = c_err_parameter;
    END IF;
    IF cardinality(v_codes) > 0 THEN
        SELECT COUNT(DISTINCT cfr.code)::INTEGER INTO v_reason_count
        FROM public.collection_failure_reason cfr
        WHERE cfr.code = ANY(v_codes) AND cfr.status = c_active;
        IF v_reason_count <> (SELECT COUNT(DISTINCT code) FROM unnest(v_codes) AS reason_codes(code)) THEN
            RAISE EXCEPTION 'Um ou mais motivos não existem ou estão inativos.' USING ERRCODE = '23503';
        END IF;
    END IF;
    IF p_record_status = c_annulled THEN
        v_points := 0;
    ELSIF v_result = c_unsuccessful THEN
        v_points := c_failure_penalty;
    ELSE
        SELECT COUNT(*) INTO v_success_count FROM public.collection c
        WHERE c.establishment_id = v_collection.establishment_id
          AND c.processing_order < v_collection.processing_order
          AND c.record_status = c_recorded
          AND c.result = c_successful;
        v_success_count := v_success_count + 1;
        v_points := FLOOR(p_collected_volume_liters)::BIGINT + c_success_bonus
            + CASE WHEN v_success_count % c_recurrence_interval = 0 THEN LEAST(v_success_count, c_recurrence_cap) ELSE 0 END;
    END IF;
    -- Registrar a nova revisão antes de substituir associações deixa uma
    -- fronteira auditável para recuperar os motivos da requisição original.
    UPDATE public.collection c
    SET record_status = p_record_status,
        collected_volume_liters = CASE WHEN p_record_status = c_recorded THEN p_collected_volume_liters ELSE c.collected_volume_liters END,
        presented_volume_liters = CASE WHEN p_record_status = c_recorded THEN p_presented_volume_liters ELSE c.presented_volume_liters END,
        oil_condition = CASE WHEN p_record_status = c_recorded THEN p_oil_condition ELSE c.oil_condition END,
        has_compromising_occurrence = CASE WHEN p_record_status = c_recorded THEN p_has_compromising_occurrence ELSE c.has_compromising_occurrence END,
        result = v_result,
        points_earned = v_points,
        observation = CASE WHEN p_record_status = c_annulled OR p_observation IS NULL THEN c.observation ELSE BTRIM(p_observation) END,
        revision = c.revision + 1, corrected_at = clock_timestamp(),
        corrected_by = p_admin_id, correction_reason = BTRIM(p_correction_reason)
    WHERE c.id = p_collection_id;
    DELETE FROM public.collection_failure WHERE collection_id = p_collection_id;
    IF cardinality(v_codes) > 0 THEN
        INSERT INTO public.collection_failure (collection_id, failure_reason_id)
        SELECT p_collection_id, cfr.id FROM public.collection_failure_reason cfr
        WHERE cfr.code = ANY(v_codes) AND cfr.status = c_active;
    END IF;
    CALL public.rebuild_user_points(v_collection.establishment_id, p_admin_id, BTRIM(p_correction_reason));
    CALL public.reconcile_establishment_certificates(v_collection.establishment_id);
END;
$$;

COMMENT ON PROCEDURE public.correct_collection(UUID, INTEGER, UUID, public.record_status_t, NUMERIC, NUMERIC, public.oil_condition_t, BOOLEAN, TEXT[], TEXT, TEXT) IS
'Corrige coleta com revisão esperada, auditoria administrativa, razões coerentes e reconstrução de pontos/certificados.';

-- =============================================================================
-- 10. Correção administrativa de entrega B2C por revisão esperada
-- =============================================================================

CREATE OR REPLACE PROCEDURE public.correct_pev_delivery(
    IN p_delivery_pev_id UUID,
    IN p_expected_revision INTEGER,
    IN p_admin_id UUID,
    IN p_record_status public.record_status_t,
    IN p_oil_volume_liters NUMERIC,
    IN p_delivery_date TIMESTAMPTZ,
    IN p_correction_reason TEXT
)
LANGUAGE plpgsql AS $$
DECLARE
    c_active CONSTANT public.active_status_t := 'ACTIVE';
    c_annulled CONSTANT public.record_status_t := 'ANNULLED';
    c_recorded CONSTANT public.record_status_t := 'RECORDED';
    c_admin CONSTANT public.user_type_t := 'ADMIN';
    c_err_parameter CONSTANT TEXT := '22023';
    c_err_authorization CONSTANT TEXT := '42501';
    c_msg_admin CONSTANT TEXT := 'O administrador informado não existe ou não está ativo.';
    v_delivery public.delivery_pev%ROWTYPE;
BEGIN
    IF p_expected_revision IS NULL OR p_expected_revision < 1
       OR p_correction_reason IS NULL OR BTRIM(p_correction_reason) = '' THEN
        RAISE EXCEPTION 'A revisão esperada e a justificativa são obrigatórias.' USING ERRCODE = c_err_parameter;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = p_admin_id
        AND u.user_type = c_admin AND u.status = c_active) THEN
        RAISE EXCEPTION '%', c_msg_admin USING ERRCODE = c_err_authorization;
    END IF;
    -- Leitura inicial apenas para localizar o participante.
    SELECT d.* INTO v_delivery
    FROM public.delivery_pev AS d
    WHERE d.id = p_delivery_pev_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'A entrega % não existe.', p_delivery_pev_id
            USING ERRCODE = 'P0002';
    END IF;

    PERFORM 1
    FROM public.citizens AS c
    WHERE c.id = v_delivery.citizen_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'O cidadão beneficiário não existe.'
            USING ERRCODE = 'P0002';
    END IF;

    -- Releitura após o bloqueio: a revisão inicial pode estar desatualizada.
    SELECT d.* INTO v_delivery
    FROM public.delivery_pev AS d
    WHERE d.id = p_delivery_pev_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'A entrega % não existe.', p_delivery_pev_id
            USING ERRCODE = 'P0002';
    END IF;

    IF v_delivery.revision <> p_expected_revision THEN
        RAISE EXCEPTION 'A entrega mudou desde a leitura. Recarregue o registro antes de corrigir.'
            USING ERRCODE = '40001';
    END IF;
    IF p_record_status = c_recorded THEN
        IF p_oil_volume_liters IS NULL OR p_oil_volume_liters <= 0
           OR p_oil_volume_liters IN ('NaN'::NUMERIC, 'Infinity'::NUMERIC, '-Infinity'::NUMERIC)
           OR p_oil_volume_liters <> ROUND(p_oil_volume_liters, 2)
           OR p_delivery_date IS NULL OR NOT isfinite(p_delivery_date)
           OR p_delivery_date > v_delivery.created_at THEN
            RAISE EXCEPTION 'O volume deve ser positivo e a data deve ser finita e não posterior ao registro original.' USING ERRCODE = c_err_parameter;
        END IF;
    ELSIF p_record_status IS DISTINCT FROM c_annulled THEN
        RAISE EXCEPTION 'A situação corrigida é inválida.' USING ERRCODE = c_err_parameter;
    END IF;

    -- A ordem e os vínculos de emissão são imutáveis; anulação preserva os
    -- dados físicos e zera somente o efeito vigente na pontuação.
    UPDATE public.delivery_pev AS d
    SET record_status = p_record_status,
        oil_volume_liters = CASE WHEN p_record_status = c_recorded THEN p_oil_volume_liters ELSE d.oil_volume_liters END,
        delivery_date = CASE WHEN p_record_status = c_recorded THEN p_delivery_date ELSE d.delivery_date END,
        points_earned = CASE WHEN p_record_status = c_annulled THEN 0 ELSE FLOOR(p_oil_volume_liters)::BIGINT END,
        revision = d.revision + 1,
        corrected_at = clock_timestamp(), corrected_by = p_admin_id,
        correction_reason = BTRIM(p_correction_reason)
    WHERE d.id = p_delivery_pev_id;
    CALL public.rebuild_user_points(v_delivery.citizen_id, p_admin_id, BTRIM(p_correction_reason));
END;
$$;

COMMENT ON PROCEDURE public.correct_pev_delivery(UUID, INTEGER, UUID, public.record_status_t, NUMERIC, TIMESTAMPTZ, TEXT) IS
'Corrige entrega B2C com revisão esperada, auditoria administrativa e reconstrução do saldo do cidadão.';

COMMIT;


/* SESSÕES — executar 01..05 antes de liberar a Core.
   Funções de escrita retornam resultados controlados, sem COMMIT interno.
   REFRESH_REUSED exige COMMIT antes do erro HTTP; rollback desfaz a revogação.
   Política estrita: reenvio após resposta perdida pode exigir novo login.
   Somente a Core autenticadora recebe EXECUTE; UUID informado não autentica.
   SET de contexto nas funções restaura o contexto anterior ao sair, inclusive
   em exceções. set_config(..., true) nunca deixa contexto no pool após COMMIT.
*/
BEGIN;

CREATE OR REPLACE FUNCTION public.auth_idle_duration(p_user_type public.user_type_t)
RETURNS INTERVAL LANGUAGE sql IMMUTABLE STRICT
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT CASE p_user_type
        WHEN 'ADMIN'::public.user_type_t THEN INTERVAL '168 hours'
        WHEN 'CITIZENS'::public.user_type_t THEN INTERVAL '360 hours'
        WHEN 'ESTABLISHMENT'::public.user_type_t THEN INTERVAL '360 hours'
    END;
$$;
COMMENT ON FUNCTION public.auth_idle_duration(public.user_type_t) IS
'Fonte única da inatividade por perfil: duração decorrida em horas, sem dias de calendário/DST.';

CREATE OR REPLACE FUNCTION public.open_auth_session(p_user_id UUID, p_token_hash BYTEA)
RETURNS TABLE (result_code TEXT, session_id UUID, expires_at TIMESTAMPTZ)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET app.audit_actor = 'SYSTEM'
SET app.current_user_id = ''
SET app.operational_driver_id = ''
SET app.audit_reason = ''
AS $$
DECLARE v_user RECORD; v_now TIMESTAMPTZ; v_expiry TIMESTAMPTZ; v_id UUID;
BEGIN
    IF p_token_hash IS NULL OR octet_length(p_token_hash) <> 32 THEN
        RETURN QUERY SELECT 'INVALID_INPUT'::TEXT, NULL::UUID, NULL::TIMESTAMPTZ; RETURN;
    END IF;
    SELECT u.id, u.status, u.user_type INTO v_user FROM public.users u
    WHERE u.id = p_user_id FOR UPDATE;
    IF NOT FOUND OR v_user.status <> 'ACTIVE'::public.active_status_t THEN
        RETURN QUERY SELECT 'USER_UNAVAILABLE'::TEXT, NULL::UUID, NULL::TIMESTAMPTZ; RETURN;
    END IF;
    v_now := clock_timestamp();
    v_expiry := v_now + public.auth_idle_duration(v_user.user_type);
    PERFORM set_config('app.audit_actor', 'USER', true);
    PERFORM set_config('app.current_user_id', p_user_id::TEXT, true);
    PERFORM set_config('app.audit_reason', 'Login autenticado pela Core', true);
    INSERT INTO public.auth_session(user_id, created_at, last_renewed_at, idle_expires_at, updated_at)
    VALUES(p_user_id, v_now, v_now, v_expiry, v_now) RETURNING id INTO v_id;
    INSERT INTO public.auth_refresh_token(session_id, token_hash, generation, issued_at, expires_at)
    VALUES(v_id, p_token_hash, 1, v_now, v_expiry);
    RETURN QUERY SELECT 'OK'::TEXT, v_id, v_expiry;
END;
$$;

CREATE OR REPLACE FUNCTION public.rotate_auth_refresh_token(p_token_hash BYTEA, p_next_token_hash BYTEA)
RETURNS TABLE (result_code TEXT, session_id UUID, user_id UUID, expires_at TIMESTAMPTZ)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET app.audit_actor = 'SYSTEM'
SET app.current_user_id = ''
SET app.operational_driver_id = ''
SET app.audit_reason = ''
AS $$
DECLARE v_uid UUID; v_sid UUID; v_tid UUID; v_user RECORD;
        v_session public.auth_session%ROWTYPE; v_token public.auth_refresh_token%ROWTYPE;
        v_now TIMESTAMPTZ; v_expiry TIMESTAMPTZ;
BEGIN
    IF p_token_hash IS NULL OR octet_length(p_token_hash) <> 32 THEN
        RETURN QUERY SELECT 'INVALID_INPUT'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; RETURN;
    END IF;
    SELECT s.user_id, s.id, t.id INTO v_uid, v_sid, v_tid
    FROM public.auth_refresh_token t JOIN public.auth_session s ON s.id = t.session_id
    WHERE t.token_hash = p_token_hash;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'INVALID_TOKEN'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; RETURN;
    END IF;
    -- Mesma hierarquia de login, logout e UPDATE de inativação: usuário → sessão → token.
    SELECT u.id, u.status, u.user_type INTO v_user FROM public.users u WHERE u.id = v_uid FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'INVALID_TOKEN'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; RETURN;
    END IF;
    SELECT s.* INTO v_session FROM public.auth_session s
    WHERE s.id = v_sid AND s.user_id = v_uid FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'INVALID_TOKEN'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; RETURN;
    END IF;
    SELECT t.* INTO v_token FROM public.auth_refresh_token t
    WHERE t.id = v_tid AND t.session_id = v_sid AND t.token_hash = p_token_hash FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'INVALID_TOKEN'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; RETURN;
    END IF;
    v_now := clock_timestamp();
    IF v_user.status <> 'ACTIVE'::public.active_status_t THEN
        RETURN QUERY SELECT 'USER_INACTIVE'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; RETURN;
    ELSIF v_session.revoked_at IS NOT NULL THEN
        RETURN QUERY SELECT 'SESSION_REVOKED'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; RETURN;
    ELSIF v_now >= v_session.idle_expires_at THEN
        RETURN QUERY SELECT 'SESSION_EXPIRED'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; RETURN;
    ELSIF v_token.consumed_at IS NOT NULL THEN
        -- Posse de segredo já consumido não comprova autoria do titular.
        PERFORM set_config('app.audit_reason', 'Reutilização de refresh token consumido', true);
        UPDATE public.auth_session SET revoked_at = v_now, revocation_reason = 'REFRESH_REUSE'
        WHERE id = v_sid;
        RETURN QUERY SELECT 'REFRESH_REUSED'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; RETURN;
    ELSIF v_now >= v_token.expires_at THEN
        RETURN QUERY SELECT 'TOKEN_EXPIRED'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; RETURN;
    END IF;
    -- Mesmo sucessor inválido não pode mascarar reutilização de token consumido.
    IF p_next_token_hash IS NULL OR octet_length(p_next_token_hash) <> 32
       OR p_token_hash = p_next_token_hash THEN
        RETURN QUERY SELECT 'INVALID_INPUT'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; RETURN;
    END IF;
    v_expiry := v_now + public.auth_idle_duration(v_user.user_type);
    PERFORM set_config('app.audit_actor', 'USER', true);
    PERFORM set_config('app.current_user_id', v_uid::TEXT, true);
    PERFORM set_config('app.audit_reason', 'Rotação válida de refresh token', true);
    UPDATE public.auth_refresh_token SET consumed_at = v_now WHERE id = v_tid;
    INSERT INTO public.auth_refresh_token(session_id, token_hash, generation, issued_at, expires_at)
    VALUES(v_sid, p_next_token_hash, v_token.generation + 1, v_now, v_expiry);
    UPDATE public.auth_session SET last_renewed_at = v_now, idle_expires_at = v_expiry WHERE id = v_sid;
    RETURN QUERY SELECT 'OK'::TEXT, v_sid, v_uid, v_expiry;
END;
$$;

CREATE OR REPLACE FUNCTION public.revoke_auth_session(p_user_id UUID, p_session_id UUID)
RETURNS TEXT
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET app.audit_actor = 'SYSTEM'
SET app.current_user_id = ''
SET app.operational_driver_id = ''
SET app.audit_reason = ''
AS $$
DECLARE v_session public.auth_session%ROWTYPE;
BEGIN
    PERFORM 1 FROM public.users u WHERE u.id = p_user_id FOR UPDATE;
    IF NOT FOUND THEN RETURN 'INVALID_SESSION'; END IF;
    SELECT s.* INTO v_session FROM public.auth_session s
    WHERE s.id = p_session_id AND s.user_id = p_user_id FOR UPDATE;
    IF NOT FOUND THEN RETURN 'INVALID_SESSION'; END IF;
    IF v_session.revoked_at IS NOT NULL THEN RETURN 'ALREADY_REVOKED'; END IF;
    PERFORM set_config('app.audit_actor', 'USER', true);
    PERFORM set_config('app.current_user_id', p_user_id::TEXT, true);
    PERFORM set_config('app.audit_reason', 'Logout autenticado pela Core', true);
    UPDATE public.auth_session SET revoked_at = clock_timestamp(), revocation_reason = 'LOGOUT'
    WHERE id = p_session_id;
    RETURN 'OK';
END;
$$;

CREATE OR REPLACE FUNCTION public.revoke_user_auth_sessions(p_user_id UUID)
RETURNS TABLE (result_code TEXT, revoked_count BIGINT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET app.audit_actor = 'SYSTEM'
SET app.current_user_id = ''
SET app.operational_driver_id = ''
SET app.audit_reason = ''
AS $$
DECLARE v_count BIGINT;
BEGIN
    PERFORM 1 FROM public.users u WHERE u.id = p_user_id FOR UPDATE;
    IF NOT FOUND THEN RETURN QUERY SELECT 'USER_UNAVAILABLE'::TEXT, 0::BIGINT; RETURN; END IF;
    PERFORM set_config('app.audit_actor', 'USER', true);
    PERFORM set_config('app.current_user_id', p_user_id::TEXT, true);
    PERFORM set_config('app.audit_reason', 'Logout das sessões do titular autenticado', true);
    UPDATE public.auth_session SET revoked_at = clock_timestamp(), revocation_reason = 'LOGOUT'
    WHERE user_id = p_user_id AND revoked_at IS NULL;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN QUERY SELECT 'OK'::TEXT, v_count;
END;
$$;

CREATE OR REPLACE FUNCTION public.get_auth_session_status(p_user_id UUID, p_session_id UUID)
RETURNS TABLE (is_valid BOOLEAN, user_status public.active_status_t,
              user_type public.user_type_t, idle_expires_at TIMESTAMPTZ)
LANGUAGE sql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT COALESCE(u.status = 'ACTIVE'::public.active_status_t
           AND s.revoked_at IS NULL AND s.idle_expires_at > clock_timestamp(), FALSE),
           u.status, u.user_type, s.idle_expires_at
    FROM (VALUES (1)) AS singleton(n)
    LEFT JOIN public.auth_session s ON s.id = p_session_id AND s.user_id = p_user_id
    LEFT JOIN public.users u ON u.id = s.user_id;
$$;
COMMENT ON FUNCTION public.get_auth_session_status(UUID, UUID) IS
'Core verifica sub/sid do JWT. Consulta não renova prazo; segunda API consulta a Core, não estas tabelas.';

-- Bloqueio público na criação; ownership e acessos: access_control/01_roles.sql.
REVOKE ALL ON FUNCTION public.auth_idle_duration(public.user_type_t) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.open_auth_session(UUID, BYTEA) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.rotate_auth_refresh_token(BYTEA, BYTEA) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.revoke_auth_session(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.revoke_user_auth_sessions(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.get_auth_session_status(UUID, UUID) FROM PUBLIC;
-- Entradas estreitas para fatos operacionais. Horários são do servidor;
-- ler o QR não comprova presença nem identidade: essas verificações são da Core.
CREATE OR REPLACE FUNCTION public.record_collection_arrival(p_request_id uuid, p_driver_id uuid)
RETURNS timestamptz LANGUAGE plpgsql SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE r public.collection_request%ROWTYPE; t timestamptz;
BEGIN
    PERFORM 1 FROM public.driver WHERE id=p_driver_id AND status='ACTIVE' FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Motorista inexistente ou inativo.' USING ERRCODE='22023'; END IF;
    SELECT * INTO r FROM public.collection_request WHERE id=p_request_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Solicitação inexistente.' USING ERRCODE='22023'; END IF;
    IF r.status <> 'APPROVED' OR r.scheduled_at IS NULL THEN
        RAISE EXCEPTION 'Chegada exige pedido aprovado e agendado.' USING ERRCODE='22023';
    END IF;
    IF r.arrived_at IS NOT NULL THEN
        IF r.arrival_driver_id=p_driver_id THEN RETURN r.arrived_at; END IF;
        RAISE EXCEPTION 'Chegada já registrada por outro motorista.' USING ERRCODE='22023';
    END IF;
    IF EXISTS(SELECT 1 FROM public.collection WHERE collection_request_id=p_request_id) THEN
        RAISE EXCEPTION 'Coleta já registrada.' USING ERRCODE='22023';
    END IF;
    t:=clock_timestamp();
    UPDATE public.collection_request SET arrived_at=t,arrival_recorded_at=t,arrival_driver_id=p_driver_id WHERE id=p_request_id;
    RETURN t;
END $$;

CREATE OR REPLACE FUNCTION public.accept_collection_service(p_request_id uuid, p_establishment_id uuid)
RETURNS timestamptz LANGUAGE plpgsql SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE r public.collection_request%ROWTYPE; t timestamptz;
BEGIN
    PERFORM 1 FROM public.users WHERE id=p_establishment_id AND user_type='ESTABLISHMENT' AND status='ACTIVE' FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Estabelecimento inexistente ou inativo.' USING ERRCODE='22023'; END IF;
    SELECT * INTO r FROM public.collection_request WHERE id=p_request_id FOR UPDATE;
    IF NOT FOUND OR r.establishment_id IS DISTINCT FROM p_establishment_id THEN
        RAISE EXCEPTION 'Solicitação não pertence ao estabelecimento.' USING ERRCODE='22023';
    END IF;
    IF r.status <> 'APPROVED' OR r.arrived_at IS NULL THEN
        RAISE EXCEPTION 'Aceite exige aprovação e chegada registrada.' USING ERRCODE='22023';
    END IF;
    IF r.service_accepted_at IS NOT NULL THEN RETURN r.service_accepted_at; END IF;
    IF EXISTS(SELECT 1 FROM public.collection WHERE collection_request_id=p_request_id) THEN
        RAISE EXCEPTION 'Coleta já registrada.' USING ERRCODE='22023';
    END IF;
    t:=clock_timestamp();
    UPDATE public.collection_request SET service_accepted_at=t,service_acceptance_recorded_at=t,service_accepted_by=p_establishment_id WHERE id=p_request_id;
    RETURN t;
END $$;

-- Contrato aprovado: rejeitar somente PENDING; aprovados usam cancelamento.
-- Não há coluna de motivo de rejeição: preservá-lo no contexto da auditoria.
CREATE OR REPLACE FUNCTION public.reject_collection_request(p_request_id uuid, p_admin_id uuid, p_reason text)
RETURNS void LANGUAGE plpgsql SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE r public.collection_request%ROWTYPE; previous_reason text;
BEGIN
    IF p_reason IS NULL OR btrim(p_reason)='' THEN RAISE EXCEPTION 'Informe motivo de rejeição.' USING ERRCODE='22023'; END IF;
    PERFORM 1 FROM public.users WHERE id=p_admin_id AND user_type='ADMIN' AND status='ACTIVE' FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Administrador inexistente ou inativo.' USING ERRCODE='22023'; END IF;
    SELECT * INTO r FROM public.collection_request WHERE id=p_request_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Solicitação inexistente.' USING ERRCODE='22023'; END IF;
    IF r.status <> 'PENDING' OR r.arrived_at IS NOT NULL OR r.service_accepted_at IS NOT NULL
        OR EXISTS(SELECT 1 FROM public.collection WHERE collection_request_id=p_request_id) THEN
        RAISE EXCEPTION 'Rejeição exige pedido pendente sem atendimento.' USING ERRCODE='22023';
    END IF;
    previous_reason:=current_setting('app.audit_reason',true);
    PERFORM set_config('app.audit_reason',p_reason,true);
    UPDATE public.collection_request SET status='REJECTED' WHERE id=p_request_id;
    PERFORM set_config('app.audit_reason',coalesce(previous_reason,''),true);
END $$;

-- A Core sincroniza estes estados após confirmação externa. PAID pertence
-- exclusivamente à aplicação do pagamento; não liberar UPDATE(status) à API.
CREATE OR REPLACE FUNCTION public.set_billing_charge_external_status(p_charge_id uuid, p_status public.charge_status_t)
RETURNS void LANGUAGE plpgsql SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE r public.billing_charge%ROWTYPE;
BEGIN
    IF p_status IS NULL OR p_status NOT IN ('CANCELLATION_PENDING','CANCELLED','EXPIRED') THEN
        RAISE EXCEPTION 'Estado externo não permitido.' USING ERRCODE='22023';
    END IF;
    SELECT * INTO r FROM public.billing_charge WHERE id=p_charge_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Cobrança inexistente.' USING ERRCODE='22023'; END IF;
    IF r.status=p_status THEN RETURN; END IF;
    IF r.status NOT IN ('OPEN','CANCELLATION_PENDING') THEN
        RAISE EXCEPTION 'Cobrança encerrada não pode ter estado sobrescrito.' USING ERRCODE='22023';
    END IF;
    UPDATE public.billing_charge SET status=p_status,
        closed_at=CASE WHEN p_status='CANCELLATION_PENDING' THEN NULL ELSE clock_timestamp() END WHERE id=p_charge_id;
END $$;

REVOKE ALL ON FUNCTION public.record_collection_arrival(uuid,uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.accept_collection_service(uuid,uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.reject_collection_request(uuid,uuid,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_billing_charge_external_status(uuid,public.charge_status_t) FROM PUBLIC;
COMMIT;
