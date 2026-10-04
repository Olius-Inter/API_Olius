/*
===============================================================================
PROJETO.............: ÓLEO AMIGO — OLIUS
BANCO DE DADOS......: PostgreSQL 16.15 (alvo)
SCRIPT..............: 05 - Functions de Trigger e Triggers
VERSÃO..............: Revisada após feedbacks
===============================================================================

OBJETIVO
-------------------------------------------------------------------------------
Concentrar toda a infraestrutura necessária às triggers:
- Functions auxiliares de contexto e gravação da auditoria;
- Functions que retornam TRIGGER;
- Criação/remoção das triggers;
- Consultas de conferência relacionadas às triggers.

DEPENDÊNCIAS
-------------------------------------------------------------------------------
Este script depende dos tipos, tabelas e tabelas *_log definidos nos scripts
anteriores de estrutura/constraints. Ele NÃO depende das functions/procedures
de negócio do script 04 e pode ser desenvolvido/executado independentemente
 dele, desde que a estrutura necessária já exista.

IMPORTANTE
-------------------------------------------------------------------------------
A instalação/substituição das functions e triggers é executada em uma única
transação. Em caso de erro, as alterações desta instalação não devem ser
confirmadas parcialmente.
===============================================================================
*/

BEGIN;

-- ============================================================================
-- 1. FUNCTIONS AUXILIARES DE CONTEXTO DE AUDITORIA
-- ============================================================================

CREATE OR REPLACE FUNCTION fn_olius_audit_actor()
RETURNS audit_actor_t
LANGUAGE plpgsql
AS $$
DECLARE
    v_actor TEXT;
BEGIN
    v_actor := NULLIF(BTRIM(current_setting('app.audit_actor', true)), '');

    IF v_actor IS NULL THEN
        RETURN 'SYSTEM'::audit_actor_t;
    END IF;

    RETURN v_actor::audit_actor_t;
EXCEPTION
    WHEN invalid_text_representation THEN
        RAISE EXCEPTION
            'app.audit_actor inválido: %. Valores aceitos: USER, DRIVER_FORM, SYSTEM.',
            v_actor;
END;
$$;


CREATE OR REPLACE FUNCTION fn_olius_current_user_id()
RETURNS UUID
LANGUAGE plpgsql
AS $$
DECLARE
    v_value TEXT;
BEGIN
    v_value := NULLIF(BTRIM(current_setting('app.current_user_id', true)), '');

    IF v_value IS NULL THEN
        RETURN NULL;
    END IF;

    RETURN v_value::UUID;
EXCEPTION
    WHEN invalid_text_representation THEN
        RAISE EXCEPTION
            'app.current_user_id deve conter um UUID válido.';
END;
$$;


CREATE OR REPLACE FUNCTION fn_olius_operational_driver_id()
RETURNS UUID
LANGUAGE plpgsql
AS $$
DECLARE
    v_value TEXT;
BEGIN
    v_value := NULLIF(BTRIM(current_setting('app.operational_driver_id', true)), '');

    IF v_value IS NULL THEN
        RETURN NULL;
    END IF;

    RETURN v_value::UUID;
EXCEPTION
    WHEN invalid_text_representation THEN
        RAISE EXCEPTION
            'app.operational_driver_id deve conter um UUID válido.';
END;
$$;


CREATE OR REPLACE FUNCTION fn_olius_audit_reason()
RETURNS TEXT
LANGUAGE sql
STABLE
AS $$
    SELECT NULLIF(BTRIM(current_setting('app.audit_reason', true)), '');
$$;


-- ============================================================================
-- 2. FUNCTION AUXILIAR — GRAVAÇÃO GENÉRICA DE SNAPSHOT
-- ============================================================================

CREATE OR REPLACE FUNCTION fn_olius_write_audit_snapshot(
    p_table_name TEXT,
    p_row JSONB,
    p_operation operation_status_t,
    p_snapshot_kind audit_snapshot_t,
    p_audit_event_id UUID,
    p_changed_columns TEXT[]
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_log_table TEXT := p_table_name || '_log';
    v_actor audit_actor_t := fn_olius_audit_actor();
    v_user_id UUID := fn_olius_current_user_id();
    v_driver_id UUID := fn_olius_operational_driver_id();
    v_reason TEXT := fn_olius_audit_reason();
    v_payload JSONB;
BEGIN
    /*
    O objeto base da linha é combinado com os metadados do log.
    O lado direito do || prevalece em caso de mesma chave.
    clock_timestamp() representa o instante efetivo da gravação do snapshot,
    e não o início da transação.
    */
    v_payload :=
        p_row
        || jsonb_build_object(
            'log_id', gen_random_uuid(),
            'audit_event_id', p_audit_event_id,
            'snapshot_kind', p_snapshot_kind,
            'operation', p_operation,
            'performed_at', clock_timestamp(),
            'performed_by', v_user_id,
            'actor_kind', v_actor,
            'operational_driver_id', v_driver_id,
            'audit_reason', v_reason,
            'changed_columns', COALESCE(p_changed_columns, ARRAY[]::TEXT[])
        );

    /*
    jsonb_populate_record ignora chaves que não existem na tabela de log.
    Isso permite que logs que removem dados sensíveis continuem compatíveis
    com a gravação genérica do snapshot.
    */
    EXECUTE format(
        'INSERT INTO %I.%I
         SELECT (jsonb_populate_record(NULL::%I.%I, $1)).*',
        'public',
        v_log_table,
        'public',
        v_log_table
    )
    USING v_payload;
END;
$$;


-- ============================================================================
-- 3. LIMPEZA DE MECANISMO DE CORRELAÇÃO OBSOLETO
-- ============================================================================

/*
A correlação por tabela temporária deixou de ser necessária porque o UPDATE é
inteiramente auditado em uma única execução AFTER UPDATE.
*/
DROP FUNCTION IF EXISTS fn_olius_audit_row_key(OID, JSONB);


-- ============================================================================
-- 4. FUNCTION DE TRIGGER — AUDITORIA
-- ============================================================================

CREATE OR REPLACE FUNCTION fn_olius_audit_trigger()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_event_id UUID;
    v_changed_columns TEXT[];
    v_old JSONB;
    v_new JSONB;
BEGIN
    /*
    Toda a auditoria é instalada como AFTER. Isso garante que os snapshots
    representem operações que chegaram ao estágio posterior da alteração e,
    no UPDATE, que NEW contenha os valores finais produzidos por BEFORE triggers.
    */
    IF TG_WHEN <> 'AFTER' THEN
        RAISE EXCEPTION
            'fn_olius_audit_trigger() deve ser executada somente por triggers AFTER. Recebido: %.%',
            TG_OP,
            TG_WHEN;
    END IF;


    IF TG_OP = 'INSERT' THEN
        v_event_id := gen_random_uuid();

        PERFORM fn_olius_write_audit_snapshot(
            TG_TABLE_NAME,
            to_jsonb(NEW),
            'INSERT'::operation_status_t,
            'AFTER'::audit_snapshot_t,
            v_event_id,
            ARRAY[]::TEXT[]
        );

        RETURN NEW;
    END IF;


    IF TG_OP = 'UPDATE' THEN
        v_old := to_jsonb(OLD);
        v_new := to_jsonb(NEW);
        v_event_id := gen_random_uuid();

        /*
        changed_columns é calculado uma única vez a partir dos estados finais
        OLD e NEW e reutilizado nos dois snapshots do mesmo audit_event_id.
        */
        SELECT ARRAY(
            SELECT key
            FROM jsonb_each(v_old) o
            JOIN jsonb_each(v_new) n USING (key)
            WHERE o.value IS DISTINCT FROM n.value
            ORDER BY key
        )
        INTO v_changed_columns;

        v_changed_columns := COALESCE(v_changed_columns, ARRAY[]::TEXT[]);

        PERFORM fn_olius_write_audit_snapshot(
            TG_TABLE_NAME,
            v_old,
            'UPDATE'::operation_status_t,
            'BEFORE'::audit_snapshot_t,
            v_event_id,
            v_changed_columns
        );

        PERFORM fn_olius_write_audit_snapshot(
            TG_TABLE_NAME,
            v_new,
            'UPDATE'::operation_status_t,
            'AFTER'::audit_snapshot_t,
            v_event_id,
            v_changed_columns
        );

        RETURN NEW;
    END IF;


    IF TG_OP = 'DELETE' THEN
        v_event_id := gen_random_uuid();

        /*
        A trigger é AFTER DELETE, mas o snapshot continua sendo BEFORE porque
        o conteúdo registrado é OLD: o estado que existia antes da exclusão.
        */
        PERFORM fn_olius_write_audit_snapshot(
            TG_TABLE_NAME,
            to_jsonb(OLD),
            'DELETE'::operation_status_t,
            'BEFORE'::audit_snapshot_t,
            v_event_id,
            ARRAY[]::TEXT[]
        );

        RETURN OLD;
    END IF;


    RAISE EXCEPTION
        'Operação não suportada pela auditoria: %.',
        TG_OP;
END;
$$;


-- ============================================================================
-- 5. FUNCTION DE TRIGGER — updated_at
-- ============================================================================

CREATE OR REPLACE FUNCTION fn_olius_set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    /* Momento efetivo da atualização, não o início da transação. */
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;


-- ============================================================================
-- 6. FUNCTION DE TRIGGER — SINCRONIZAÇÃO establishment.is_pev
-- ============================================================================

CREATE OR REPLACE FUNCTION fn_olius_sync_establishment_is_pev()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_is_pev BOOLEAN;
BEGIN
    /*
    INSERT: recalcula apenas o estabelecimento associado ao novo PEV.
    */
    IF TG_OP = 'INSERT' THEN
        IF NEW.establishment_id IS NOT NULL THEN
            SELECT EXISTS (
                SELECT 1
                FROM pev p
                WHERE p.establishment_id = NEW.establishment_id
                  AND p.status = 'APPROVED'
            )
            INTO v_is_pev;

            UPDATE establishment e
               SET is_pev = v_is_pev
             WHERE e.id = NEW.establishment_id
               AND e.is_pev IS DISTINCT FROM v_is_pev;
        END IF;

        RETURN NEW;
    END IF;


    /*
    DELETE: o PEV já não está mais visível na tabela; recalcula o antigo
    estabelecimento com base nos PEVs restantes.
    */
    IF TG_OP = 'DELETE' THEN
        IF OLD.establishment_id IS NOT NULL THEN
            SELECT EXISTS (
                SELECT 1
                FROM pev p
                WHERE p.establishment_id = OLD.establishment_id
                  AND p.status = 'APPROVED'
            )
            INTO v_is_pev;

            UPDATE establishment e
               SET is_pev = v_is_pev
             WHERE e.id = OLD.establishment_id
               AND e.is_pev IS DISTINCT FROM v_is_pev;
        END IF;

        RETURN OLD;
    END IF;


    /*
    UPDATE: a trigger de UPDATE só chama esta função quando status ou
    establishment_id realmente mudam. Se o estabelecimento mudou, primeiro
    recalculamos o antigo; depois recalculamos o novo/atual.
    */
    IF TG_OP = 'UPDATE' THEN
        IF OLD.establishment_id IS DISTINCT FROM NEW.establishment_id
           AND OLD.establishment_id IS NOT NULL THEN

            SELECT EXISTS (
                SELECT 1
                FROM pev p
                WHERE p.establishment_id = OLD.establishment_id
                  AND p.status = 'APPROVED'
            )
            INTO v_is_pev;

            UPDATE establishment e
               SET is_pev = v_is_pev
             WHERE e.id = OLD.establishment_id
               AND e.is_pev IS DISTINCT FROM v_is_pev;
        END IF;

        IF NEW.establishment_id IS NOT NULL THEN
            SELECT EXISTS (
                SELECT 1
                FROM pev p
                WHERE p.establishment_id = NEW.establishment_id
                  AND p.status = 'APPROVED'
            )
            INTO v_is_pev;

            UPDATE establishment e
               SET is_pev = v_is_pev
             WHERE e.id = NEW.establishment_id
               AND e.is_pev IS DISTINCT FROM v_is_pev;
        END IF;

        RETURN NEW;
    END IF;


    RAISE EXCEPTION
        'Operação não suportada na sincronização establishment.is_pev: %.',
        TG_OP;
END;
$$;


-- ============================================================================
-- 7. REMOÇÃO IDEMPOTENTE DOS TRIGGERS DESTA VERSÃO E VERSÕES ANTERIORES
-- ============================================================================

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT event_object_table, trigger_name
        FROM information_schema.triggers
        WHERE trigger_schema = 'public'
          AND trigger_name IN (
              -- Auditoria: versão atual e nomes das versões anteriores
              'trg_olius_audit_insert',
              'trg_olius_audit_update',
              'trg_olius_audit_update_before',
              'trg_olius_audit_update_after',
              'trg_olius_audit_delete',

              -- updated_at
              'trg_olius_set_updated_at',

              -- PEV: versão unificada anterior e versão atual separada
              'trg_olius_sync_establishment_is_pev',
              'trg_olius_sync_establishment_is_pev_insert',
              'trg_olius_sync_establishment_is_pev_update',
              'trg_olius_sync_establishment_is_pev_delete'
          )
    LOOP
        EXECUTE format(
            'DROP TRIGGER IF EXISTS %I ON public.%I',
            r.trigger_name,
            r.event_object_table
        );
    END LOOP;
END;
$$;


-- ============================================================================
-- 8. AUDITORIA — AS 22 TABELAS COM *_log COBERTAS POR ESTE SCRIPT
-- ============================================================================

DO $$
DECLARE
    v_table TEXT;
    v_tables TEXT[] := ARRAY[
        'users',
        'addresses',
        'user_qr_code',
        'citizens',
        'establishment',
        'pev',
        'subscription_plan',
        'establishment_subscription',
        'subscription_cycle',
        'billing_order',
        'billing_charge',
        'payment',
        'payment_application',
        'payment_refund',
        'collection_request',
        'collection',
        'collection_failure_reason',
        'collection_failure',
        'delivery_pev',
        'point_calculation',
        'certificate_level',
        'certificate'
    ];
BEGIN
    FOREACH v_table IN ARRAY v_tables
    LOOP
        EXECUTE format(
            'CREATE TRIGGER trg_olius_audit_insert
             AFTER INSERT ON public.%I
             FOR EACH ROW
             EXECUTE FUNCTION fn_olius_audit_trigger()',
            v_table
        );

        EXECUTE format(
            'CREATE TRIGGER trg_olius_audit_update
             AFTER UPDATE ON public.%I
             FOR EACH ROW
             EXECUTE FUNCTION fn_olius_audit_trigger()',
            v_table
        );

        EXECUTE format(
            'CREATE TRIGGER trg_olius_audit_delete
             AFTER DELETE ON public.%I
             FOR EACH ROW
             EXECUTE FUNCTION fn_olius_audit_trigger()',
            v_table
        );
    END LOOP;
END;
$$;


-- ============================================================================
-- 9. updated_at — TABELAS DEFINIDAS PELO SCRIPT 02
-- ============================================================================

DO $$
DECLARE
    v_table TEXT;
    v_tables TEXT[] := ARRAY[
        'users',
        'pev',
        'subscription_plan',
        'establishment_subscription',
        'subscription_cycle',
        'billing_order',
        'billing_charge',
        'certificate_level',
        'certificate'
    ];
BEGIN
    FOREACH v_table IN ARRAY v_tables
    LOOP
        EXECUTE format(
            'CREATE TRIGGER trg_olius_set_updated_at
             BEFORE UPDATE ON public.%I
             FOR EACH ROW
             EXECUTE FUNCTION fn_olius_set_updated_at()',
            v_table
        );
    END LOOP;
END;
$$;


-- ============================================================================
-- 10. updated_at — OUTRAS TABELAS COM CAMPO updated_at
-- ============================================================================

CREATE TRIGGER trg_olius_set_updated_at
BEFORE UPDATE ON public.collection_request
FOR EACH ROW
EXECUTE FUNCTION fn_olius_set_updated_at();


CREATE TRIGGER trg_olius_set_updated_at
BEFORE UPDATE ON public.driver
FOR EACH ROW
EXECUTE FUNCTION fn_olius_set_updated_at();


CREATE TRIGGER trg_olius_set_updated_at
BEFORE UPDATE ON public.user_qr_code
FOR EACH ROW
EXECUTE FUNCTION fn_olius_set_updated_at();


-- ============================================================================
-- 11. PEV -> establishment.is_pev
-- ============================================================================

/*
INSERT e DELETE sempre exigem recálculo do estabelecimento relacionado.
UPDATE só dispara quando status ou establishment_id realmente mudarem.
*/
CREATE TRIGGER trg_olius_sync_establishment_is_pev_insert
AFTER INSERT ON public.pev
FOR EACH ROW
EXECUTE FUNCTION fn_olius_sync_establishment_is_pev();


CREATE TRIGGER trg_olius_sync_establishment_is_pev_update
AFTER UPDATE ON public.pev
FOR EACH ROW
WHEN (
    OLD.status IS DISTINCT FROM NEW.status
    OR OLD.establishment_id IS DISTINCT FROM NEW.establishment_id
)
EXECUTE FUNCTION fn_olius_sync_establishment_is_pev();


CREATE TRIGGER trg_olius_sync_establishment_is_pev_delete
AFTER DELETE ON public.pev
FOR EACH ROW
EXECUTE FUNCTION fn_olius_sync_establishment_is_pev();


-- ============================================================================
-- 12. COMENTÁRIOS
-- ============================================================================

COMMENT ON FUNCTION fn_olius_audit_actor()
IS 'Obtém o tipo de ator da auditoria a partir do contexto local da transação.';

COMMENT ON FUNCTION fn_olius_current_user_id()
IS 'Obtém o UUID do usuário responsável a partir do contexto local da transação.';

COMMENT ON FUNCTION fn_olius_operational_driver_id()
IS 'Obtém o UUID do motorista operacional a partir do contexto local da transação.';

COMMENT ON FUNCTION fn_olius_audit_reason()
IS 'Obtém a justificativa opcional da auditoria a partir do contexto local da transação.';

COMMENT ON FUNCTION fn_olius_write_audit_snapshot(
    TEXT,
    JSONB,
    operation_status_t,
    audit_snapshot_t,
    UUID,
    TEXT[]
)
IS 'Grava snapshot genérico na tabela *_log correspondente, com timestamp efetivo via clock_timestamp().';

COMMENT ON FUNCTION fn_olius_audit_trigger()
IS 'Auditoria genérica executada somente em AFTER: INSERT=NEW/AFTER, UPDATE=OLD/BEFORE + NEW/AFTER no mesmo evento, DELETE=OLD/BEFORE.';

COMMENT ON FUNCTION fn_olius_set_updated_at()
IS 'Atualiza updated_at com clock_timestamp(), representando o instante efetivo da alteração.';

COMMENT ON FUNCTION fn_olius_sync_establishment_is_pev()
IS 'Mantém establishment.is_pev derivado de PEVs APPROVED, evitando UPDATE quando a flag já está correta.';


COMMIT;


-- ============================================================================
-- 13. CONSULTAS DE CONFERÊNCIA — EXECUTADAS APÓS A INSTALAÇÃO
-- ============================================================================

SELECT
    event_object_table,
    trigger_name,
    event_manipulation,
    action_timing
FROM information_schema.triggers
WHERE trigger_schema = 'public'
  AND trigger_name LIKE 'trg_olius_%'
ORDER BY event_object_table, trigger_name, event_manipulation;


/*
Conferir se não restou nenhuma auditoria BEFORE:
*/
-- SELECT
--     event_object_table,
--     trigger_name,
--     event_manipulation,
--     action_timing
-- FROM information_schema.triggers
-- WHERE trigger_schema = 'public'
--   AND trigger_name LIKE 'trg_olius_audit_%'
--   AND action_timing <> 'AFTER';


/*
Conferir auditoria:
*/
-- SELECT * FROM collection_log ORDER BY performed_at DESC;
-- SELECT * FROM collection_request_log ORDER BY performed_at DESC;
-- SELECT * FROM delivery_pev_log ORDER BY performed_at DESC;
-- SELECT * FROM point_calculation_log ORDER BY performed_at DESC;


/*
Conferir pares BEFORE/AFTER de UPDATE:
*/
-- SELECT
--     audit_event_id,
--     operation,
--     snapshot_kind,
--     performed_at,
--     changed_columns
-- FROM collection_log
-- WHERE operation = 'UPDATE'
-- ORDER BY performed_at DESC;


/*
Conferir saldo/flags derivadas:
*/
-- SELECT id, points FROM citizens;
-- SELECT id, points, is_pev FROM establishment;


/*
Exemplo de contexto de auditoria dentro de uma TRANSAÇÃO:

BEGIN;

SELECT set_config('app.audit_actor', 'USER', true);
SELECT set_config('app.current_user_id', 'UUID_DO_USUARIO', true);
SELECT set_config('app.audit_reason', 'Atualização solicitada pelo usuário', true);

-- operação...

COMMIT;

Para formulário operacional de motorista:

BEGIN;

SELECT set_config('app.audit_actor', 'DRIVER_FORM', true);
SELECT set_config('app.current_user_id', '', true);
SELECT set_config('app.operational_driver_id', 'UUID_DO_MOTORISTA', true);
SELECT set_config('app.audit_reason', 'Registro operacional da coleta', true);

-- operação...

COMMIT;
*/


/*
===============================================================================
FIM DO SCRIPT 05
===============================================================================
*/
