-- HISTÓRICO: somente contrato de login com dois parâmetros. Não reaplicar após recuperação de senha.
-- A verificação de versão recusa o contrato novo; usar a migração oficial do banco.
-- Atualização pontual para banco já instalado. Fonte: DB_Segundo f1a7bff.
-- Revisar/backup e interromper tráfego antes da implantação. Executar como conta
-- de implantação autorizada, fora de outra transação; nunca pela conta da Core.
-- Não cria roles, não concede privilégios e não recria tabelas.
BEGIN;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.pronamespace='public'::regnamespace AND p.proname='open_auth_session' AND p.proowner='olius_auth_owner'::regrole AND md5(btrim(regexp_replace(p.prosrc, '\s+', ' ', 'g'))) IN ('4c1274d6d582097d4e2e12a1358d701b','eac7caf0fe6f1d3619f839d758f53a71')) THEN RAISE EXCEPTION 'Rotina ausente, owner ou versão inesperada: open_auth_session'; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.pronamespace='public'::regnamespace AND p.proname='rotate_auth_refresh_token' AND p.proowner='olius_auth_owner'::regrole AND md5(btrim(regexp_replace(p.prosrc, '\s+', ' ', 'g'))) IN ('aa4b8b1a1265af04b614da345da5e3ba','13105325ccddb214dda7b23e9a1fe74c')) THEN RAISE EXCEPTION 'Rotina ausente, owner ou versão inesperada: rotate_auth_refresh_token'; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.pronamespace='public'::regnamespace AND p.proname='revoke_auth_session' AND p.proowner='olius_auth_owner'::regrole AND md5(btrim(regexp_replace(p.prosrc, '\s+', ' ', 'g'))) IN ('691ffebd9db22a125939dbdb45143a69','5d0406ff031dc956f37046130b86833c')) THEN RAISE EXCEPTION 'Rotina ausente, owner ou versão inesperada: revoke_auth_session'; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.pronamespace='public'::regnamespace AND p.proname='revoke_user_auth_sessions' AND p.proowner='olius_auth_owner'::regrole AND md5(btrim(regexp_replace(p.prosrc, '\s+', ' ', 'g'))) IN ('b83dc4762d130f4448cde777294160d5','5d997ae24485746d7378d859985eba04')) THEN RAISE EXCEPTION 'Rotina ausente, owner ou versão inesperada: revoke_user_auth_sessions'; END IF;
END $$;
CREATE TEMP TABLE auth_audit_acl_before ON COMMIT DROP AS
SELECT oid,proowner,proacl,prosecdef FROM pg_proc WHERE pronamespace='public'::regnamespace
AND proname IN ('open_auth_session','rotate_auth_refresh_token','revoke_auth_session','revoke_user_auth_sessions');

CREATE OR REPLACE FUNCTION public.open_auth_session(p_user_id UUID, p_token_hash BYTEA)
RETURNS TABLE (result_code TEXT, session_id UUID, expires_at TIMESTAMPTZ)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_user RECORD; v_now TIMESTAMPTZ; v_expiry TIMESTAMPTZ; v_id UUID;
        v_login_context JSONB;
BEGIN
    SELECT jsonb_object_agg(setting_name, current_setting(setting_name, true)) INTO v_login_context
    FROM unnest(ARRAY['app.audit_actor', 'app.current_user_id',
                      'app.operational_driver_id', 'app.audit_reason']) AS settings(setting_name);
    <<session_operation>>
    BEGIN
        PERFORM set_config(setting_name, setting_value, true)
        FROM (VALUES ('app.audit_actor', 'SYSTEM'), ('app.current_user_id', ''),
                     ('app.operational_driver_id', ''), ('app.audit_reason', '')) AS settings(setting_name, setting_value);
        IF p_token_hash IS NULL OR octet_length(p_token_hash) <> 32 THEN
            RETURN QUERY SELECT 'INVALID_INPUT'::TEXT, NULL::UUID, NULL::TIMESTAMPTZ; EXIT session_operation;
        END IF;
        SELECT u.id, u.status, u.user_type INTO v_user FROM public.users u
        WHERE u.id = p_user_id FOR UPDATE;
        IF NOT FOUND OR v_user.status <> 'ACTIVE'::public.active_status_t THEN
            RETURN QUERY SELECT 'USER_UNAVAILABLE'::TEXT, NULL::UUID, NULL::TIMESTAMPTZ; EXIT session_operation;
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
    END session_operation;
    PERFORM set_config(key, coalesce(value, ''), true) FROM jsonb_each_text(v_login_context);
    RETURN;
EXCEPTION WHEN OTHERS THEN
    PERFORM set_config(key, coalesce(value, ''), true) FROM jsonb_each_text(v_login_context);
    RAISE;
END;
$$;

CREATE OR REPLACE FUNCTION public.rotate_auth_refresh_token(p_token_hash BYTEA, p_next_token_hash BYTEA)
RETURNS TABLE (result_code TEXT, session_id UUID, user_id UUID, expires_at TIMESTAMPTZ)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_uid UUID; v_sid UUID; v_tid UUID; v_user RECORD;
        v_session public.auth_session%ROWTYPE; v_token public.auth_refresh_token%ROWTYPE;
        v_now TIMESTAMPTZ; v_expiry TIMESTAMPTZ;
        v_rotation_context JSONB;
BEGIN
    SELECT jsonb_object_agg(setting_name, current_setting(setting_name, true)) INTO v_rotation_context
    FROM unnest(ARRAY['app.audit_actor', 'app.current_user_id',
                      'app.operational_driver_id', 'app.audit_reason']) AS settings(setting_name);
    <<session_operation>>
    BEGIN
        PERFORM set_config(setting_name, setting_value, true)
        FROM (VALUES ('app.audit_actor', 'SYSTEM'), ('app.current_user_id', ''),
                     ('app.operational_driver_id', ''), ('app.audit_reason', '')) AS settings(setting_name, setting_value);
        IF p_token_hash IS NULL OR octet_length(p_token_hash) <> 32 THEN
            RETURN QUERY SELECT 'INVALID_INPUT'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; EXIT session_operation;
        END IF;
        SELECT s.user_id, s.id, t.id INTO v_uid, v_sid, v_tid
        FROM public.auth_refresh_token t JOIN public.auth_session s ON s.id = t.session_id
        WHERE t.token_hash = p_token_hash;
        IF NOT FOUND THEN
            RETURN QUERY SELECT 'INVALID_TOKEN'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; EXIT session_operation;
        END IF;
        -- Mesma hierarquia de login, logout e UPDATE de inativação: usuário → sessão → token.
        SELECT u.id, u.status, u.user_type INTO v_user FROM public.users u WHERE u.id = v_uid FOR UPDATE;
        IF NOT FOUND THEN
            RETURN QUERY SELECT 'INVALID_TOKEN'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; EXIT session_operation;
        END IF;
        SELECT s.* INTO v_session FROM public.auth_session s
        WHERE s.id = v_sid AND s.user_id = v_uid FOR UPDATE;
        IF NOT FOUND THEN
            RETURN QUERY SELECT 'INVALID_TOKEN'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; EXIT session_operation;
        END IF;
        SELECT t.* INTO v_token FROM public.auth_refresh_token t
        WHERE t.id = v_tid AND t.session_id = v_sid AND t.token_hash = p_token_hash FOR UPDATE;
        IF NOT FOUND THEN
            RETURN QUERY SELECT 'INVALID_TOKEN'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; EXIT session_operation;
        END IF;
        v_now := clock_timestamp();
        IF v_user.status <> 'ACTIVE'::public.active_status_t THEN
            RETURN QUERY SELECT 'USER_INACTIVE'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; EXIT session_operation;
        ELSIF v_session.revoked_at IS NOT NULL THEN
            RETURN QUERY SELECT 'SESSION_REVOKED'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; EXIT session_operation;
        ELSIF v_now >= v_session.idle_expires_at THEN
            RETURN QUERY SELECT 'SESSION_EXPIRED'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; EXIT session_operation;
        ELSIF v_token.consumed_at IS NOT NULL THEN
            -- Posse de segredo já consumido não comprova autoria do titular.
            PERFORM set_config('app.audit_reason', 'Reutilização de refresh token consumido', true);
            UPDATE public.auth_session SET revoked_at = v_now, revocation_reason = 'REFRESH_REUSE'
            WHERE id = v_sid;
            RETURN QUERY SELECT 'REFRESH_REUSED'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; EXIT session_operation;
        ELSIF v_now >= v_token.expires_at THEN
            RETURN QUERY SELECT 'TOKEN_EXPIRED'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; EXIT session_operation;
        END IF;
        -- Mesmo sucessor inválido não pode mascarar reutilização de token consumido.
        IF p_next_token_hash IS NULL OR octet_length(p_next_token_hash) <> 32
           OR p_token_hash = p_next_token_hash THEN
            RETURN QUERY SELECT 'INVALID_INPUT'::TEXT, NULL::UUID, NULL::UUID, NULL::TIMESTAMPTZ; EXIT session_operation;
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
    END session_operation;
    PERFORM set_config(key, coalesce(value, ''), true) FROM jsonb_each_text(v_rotation_context);
    RETURN;
EXCEPTION WHEN OTHERS THEN
    PERFORM set_config(key, coalesce(value, ''), true) FROM jsonb_each_text(v_rotation_context);
    RAISE;
END;
$$;

CREATE OR REPLACE FUNCTION public.revoke_auth_session(p_user_id UUID, p_session_id UUID)
RETURNS TEXT
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_session public.auth_session%ROWTYPE;
        v_logout_context JSONB;
        v_logout_result TEXT;
BEGIN
    SELECT jsonb_object_agg(setting_name, current_setting(setting_name, true)) INTO v_logout_context
    FROM unnest(ARRAY['app.audit_actor', 'app.current_user_id',
                      'app.operational_driver_id', 'app.audit_reason']) AS settings(setting_name);
    <<session_operation>>
    BEGIN
        PERFORM set_config(setting_name, setting_value, true)
        FROM (VALUES ('app.audit_actor', 'SYSTEM'), ('app.current_user_id', ''),
                     ('app.operational_driver_id', ''), ('app.audit_reason', '')) AS settings(setting_name, setting_value);
        PERFORM 1 FROM public.users u WHERE u.id = p_user_id FOR UPDATE;
        IF NOT FOUND THEN v_logout_result := 'INVALID_SESSION'; EXIT session_operation; END IF;
        SELECT s.* INTO v_session FROM public.auth_session s
        WHERE s.id = p_session_id AND s.user_id = p_user_id FOR UPDATE;
        IF NOT FOUND THEN v_logout_result := 'INVALID_SESSION'; EXIT session_operation; END IF;
        IF v_session.revoked_at IS NOT NULL THEN v_logout_result := 'ALREADY_REVOKED'; EXIT session_operation; END IF;
        PERFORM set_config('app.audit_actor', 'USER', true);
        PERFORM set_config('app.current_user_id', p_user_id::TEXT, true);
        PERFORM set_config('app.audit_reason', 'Logout autenticado pela Core', true);
        UPDATE public.auth_session SET revoked_at = clock_timestamp(), revocation_reason = 'LOGOUT'
        WHERE id = p_session_id;
        v_logout_result := 'OK'; EXIT session_operation;
    END session_operation;
    PERFORM set_config(key, coalesce(value, ''), true) FROM jsonb_each_text(v_logout_context);
    RETURN v_logout_result;
EXCEPTION WHEN OTHERS THEN
    PERFORM set_config(key, coalesce(value, ''), true) FROM jsonb_each_text(v_logout_context);
    RAISE;
END;
$$;

CREATE OR REPLACE FUNCTION public.revoke_user_auth_sessions(p_user_id UUID)
RETURNS TABLE (result_code TEXT, revoked_count BIGINT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_count BIGINT;
        v_bulk_logout_context JSONB;
BEGIN
    SELECT jsonb_object_agg(setting_name, current_setting(setting_name, true)) INTO v_bulk_logout_context
    FROM unnest(ARRAY['app.audit_actor', 'app.current_user_id',
                      'app.operational_driver_id', 'app.audit_reason']) AS settings(setting_name);
    <<session_operation>>
    BEGIN
        PERFORM set_config(setting_name, setting_value, true)
        FROM (VALUES ('app.audit_actor', 'SYSTEM'), ('app.current_user_id', ''),
                     ('app.operational_driver_id', ''), ('app.audit_reason', '')) AS settings(setting_name, setting_value);
        PERFORM 1 FROM public.users u WHERE u.id = p_user_id FOR UPDATE;
        IF NOT FOUND THEN RETURN QUERY SELECT 'USER_UNAVAILABLE'::TEXT, 0::BIGINT; EXIT session_operation; END IF;
        PERFORM set_config('app.audit_actor', 'USER', true);
        PERFORM set_config('app.current_user_id', p_user_id::TEXT, true);
        PERFORM set_config('app.audit_reason', 'Logout das sessões do titular autenticado', true);
        UPDATE public.auth_session SET revoked_at = clock_timestamp(), revocation_reason = 'LOGOUT'
        WHERE user_id = p_user_id AND revoked_at IS NULL;
        GET DIAGNOSTICS v_count = ROW_COUNT;
        RETURN QUERY SELECT 'OK'::TEXT, v_count;
    END session_operation;
    PERFORM set_config(key, coalesce(value, ''), true) FROM jsonb_each_text(v_bulk_logout_context);
    RETURN;
EXCEPTION WHEN OTHERS THEN
    PERFORM set_config(key, coalesce(value, ''), true) FROM jsonb_each_text(v_bulk_logout_context);
    RAISE;
END;
$$;

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM auth_audit_acl_before b JOIN pg_proc p ON p.oid=b.oid
        WHERE p.proowner<>b.proowner OR p.proacl IS DISTINCT FROM b.proacl
           OR p.prosecdef IS DISTINCT FROM b.prosecdef
           OR p.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, pg_temp']) THEN
        RAISE EXCEPTION 'Atributos ou permissões divergentes; atualização cancelada';
    END IF;
END $$;
COMMIT;
