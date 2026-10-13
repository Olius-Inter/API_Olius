/* OLIUS — Roles técnicas e contrato de acesso da Core API — PostgreSQL 16.15
   Executar DEPOIS de 01–05 do catálogo, no banco do OLIUS e schema public.
   Executar o arquivo completo, fora de outra transação. Em erro: ROLLBACK.
   Requer CREATEROLE e propriedade (direta/herdada) sobre schema/objetos,
   além de autoridade para conceder CONNECT no banco selecionado.
   No Aiven, usar a conta administrativa autorizada; não exige SUPERUSER.

   Este script reconcilia concessões DIRETAS das cinco roles e de PUBLIC
   somente nos objetos OLIUS listados. Remove também grants por coluna.
   Reaplicá-lo remove concessões manuais adicionais nesses objetos:
   versionar futuras permissões da API neste arquivo antes de reaplicar.
   REVOKE usa RESTRICT: cadeias de concessão incompatíveis causam erro,
   sem remover permissões de terceiros com CASCADE.

   As roles são grupos NOLOGIN. Nenhuma conta pessoal, senha ou membership
   para integrantes é criada. Roles são globais ao servidor PostgreSQL.
   Transfere apenas os objetos explicitamente listados para olius_owner.
   Separa proprietários internos de auth e negócio; não altera outros bancos.
*/
BEGIN;

-- Lista fechada: não usar ALL TABLES IN SCHEMA nem o catálogo editável
-- como fonte de autoridade para conceder privilégios em objetos arbitrários.
CREATE TEMP TABLE olius_permission_scope (object_name text PRIMARY KEY) ON COMMIT DROP;
INSERT INTO olius_permission_scope VALUES
    ('users'),
    ('establishment_type'),
    ('addresses'),
    ('telephone'),
    ('user_qr_code'),
    ('citizens'),
    ('establishment'),
    ('driver'),
    ('pev'),
    ('subscription_plan'),
    ('establishment_subscription'),
    ('subscription_cycle'),
    ('billing_order'),
    ('billing_charge'),
    ('payment'),
    ('payment_application'),
    ('subscription_cycle_change'),
    ('payment_refund'),
    ('payment_refund_attempt'),
    ('payment_provider_event'),
    ('collection_request'),
    ('collection_schedule_history'),
    ('collection'),
    ('collection_failure_reason'),
    ('collection_failure'),
    ('delivery_pev'),
    ('point_calculation'),
    ('point_transaction'),
    ('certificate_level'),
    ('certificate'),
    ('users_log'),
    ('addresses_log'),
    ('user_qr_code_log'),
    ('citizens_log'),
    ('establishment_log'),
    ('pev_log'),
    ('subscription_plan_log'),
    ('establishment_subscription_log'),
    ('subscription_cycle_log'),
    ('billing_order_log'),
    ('billing_charge_log'),
    ('payment_log'),
    ('payment_application_log'),
    ('payment_refund_log'),
    ('collection_request_log'),
    ('collection_log'),
    ('collection_failure_reason_log'),
    ('collection_failure_log'),
    ('delivery_pev_log'),
    ('point_calculation_log'),
    ('certificate_level_log'),
    ('certificate_log'),
    ('auth_session'),
    ('auth_refresh_token'),
    ('auth_session_log'),
    ('data_catalog_table'),
    ('data_catalog_column'),
    ('data_catalog_role'),
    ('vw_data_catalog');

-- PAPÉIS GERAIS E RECONCILIAÇÃO
-- Valida os grupos e retira concessões anteriores dos objetos listados.
DO $setup$
DECLARE role_name text; existing_role record; obj record; col record;
BEGIN
    IF current_schema() IS DISTINCT FROM 'public' THEN
        RAISE EXCEPTION 'Selecione o schema public do banco OLIUS antes de executar.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM olius_permission_scope s
        WHERE to_regclass(format('public.%I', s.object_name)) IS NULL
    ) THEN
        RAISE EXCEPTION 'Faltam objetos OLIUS. Execute os scripts principais e o catálogo antes deste arquivo.';
    END IF;

    -- Evita sucesso aparente quando GRANT/REVOKE emitiria apenas um aviso
    -- por falta de autoridade. A conta deve administrar os objetos envolvidos.
    IF NOT (SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname = current_user) THEN
        IF EXISTS (SELECT 1 FROM olius_permission_scope s
            JOIN pg_catalog.pg_class c ON c.oid = to_regclass(format('public.%I', s.object_name))
            WHERE NOT pg_catalog.pg_has_role(current_user, c.relowner, 'USAGE'))
          OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace n WHERE n.nspname = 'public'
            AND pg_catalog.pg_has_role(current_user, n.nspowner, 'USAGE'))
          OR NOT pg_catalog.has_database_privilege(current_database(), 'CONNECT WITH GRANT OPTION') THEN
            RAISE EXCEPTION 'Conta sem autoridade de proprietário/concessão suficiente. Usar a conta de implantação autorizada.';
        END IF;
    END IF;

    FOREACH role_name IN ARRAY ARRAY['olius_owner','olius_reader','olius_auditor','olius_catalog_editor','olius_api'] LOOP
        SELECT * INTO existing_role FROM pg_catalog.pg_roles WHERE rolname = role_name;
        IF NOT FOUND THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', role_name);
        ELSIF existing_role.rolcanlogin OR existing_role.rolsuper OR existing_role.rolcreatedb
            OR existing_role.rolcreaterole OR existing_role.rolreplication OR existing_role.rolbypassrls THEN
            RAISE EXCEPTION 'Role % já existe com atributos incompatíveis. Revisar sem alterar automaticamente.', role_name;
        END IF;
        -- Uma role que herda outro grupo pode conservar privilégios após REVOKE.
        IF EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m
            JOIN pg_catalog.pg_roles r ON r.oid = m.member WHERE r.rolname = role_name) THEN
            RAISE EXCEPTION 'Role % pertence a outro grupo. Revisar herança antes de aplicar a matriz.', role_name;
        END IF;
    END LOOP;

    IF EXISTS (
        SELECT 1 FROM olius_permission_scope s
        JOIN pg_catalog.pg_class c ON c.oid = to_regclass(format('public.%I', s.object_name))
        JOIN pg_catalog.pg_roles r ON r.oid = c.relowner
        WHERE r.rolname IN ('olius_reader','olius_auditor','olius_catalog_editor','olius_api')
    ) THEN
        RAISE EXCEPTION 'Role operacional é proprietária de objeto. REVOKE não remove poderes de proprietário; revisar ownership.';
    END IF;

    FOR obj IN SELECT object_name FROM olius_permission_scope LOOP
        EXECUTE format('REVOKE ALL PRIVILEGES ON TABLE public.%I FROM PUBLIC, olius_owner, olius_reader, olius_auditor, olius_catalog_editor, olius_api RESTRICT', obj.object_name);
        -- Revogar na tabela não revoga concessões independentes por coluna.
        FOR col IN SELECT attname FROM pg_catalog.pg_attribute
            WHERE attrelid = to_regclass(format('public.%I', obj.object_name))
              AND attnum > 0 AND NOT attisdropped AND attacl IS NOT NULL LOOP
            EXECUTE format('REVOKE SELECT (%1$I), INSERT (%1$I), UPDATE (%1$I), REFERENCES (%1$I) ON TABLE public.%2$I FROM PUBLIC, olius_owner, olius_reader, olius_auditor, olius_catalog_editor, olius_api RESTRICT', col.attname, obj.object_name);
        END LOOP;
    END LOOP;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO olius_owner, olius_reader, olius_auditor, olius_catalog_editor, olius_api', current_database());
END $setup$;

-- Schema public deve ser o espaço de trabalho do OLIUS neste banco.
-- Retira criação genérica; não revoga CONNECT de PUBLIC nem afeta outros bancos.
REVOKE CREATE ON SCHEMA public FROM PUBLIC, olius_reader, olius_auditor, olius_catalog_editor, olius_api;
GRANT USAGE ON SCHEMA public TO olius_owner, olius_reader, olius_auditor, olius_catalog_editor, olius_api;
GRANT CREATE ON SCHEMA public TO olius_owner;

-- PAPEL olius_owner — manutenção dos dados e da estrutura.
-- ALL NÃO concede ALTER/DROP: tais poderes dependem da propriedade real.
-- A transferência restrita abaixo torna o grupo proprietário real.
-- Pedro e Caio não recebem poderes globais de criação de roles/bancos.
DO $owner_grants$
DECLARE obj record;
BEGIN
    FOR obj IN SELECT object_name FROM olius_permission_scope LOOP
        EXECUTE format('GRANT ALL PRIVILEGES ON TABLE public.%I TO olius_owner WITH GRANT OPTION', obj.object_name);
    END LOOP;
END $owner_grants$;

-- PAPEL olius_reader — consulta ao conjunto aprovado: não inclui automaticamente dados financeiros,
-- contatos, tokens ou hashes. Observações nas tabelas operacionais são visíveis.
GRANT SELECT ON
    establishment_type, subscription_plan, collection_failure_reason,
    certificate_level, subscription_cycle, collection_request, collection,
    delivery_pev, point_calculation, point_transaction, certificate
TO olius_reader;
GRANT SELECT (id, name, user_type, status) ON users TO olius_reader;

-- PAPEL olius_auditor — consulta aos logs de negócio aprovados.
GRANT SELECT ON
    users_log,
    addresses_log,
    user_qr_code_log,
    citizens_log,
    establishment_log,
    pev_log,
    subscription_plan_log,
    establishment_subscription_log,
    subscription_cycle_log,
    billing_order_log,
    billing_charge_log,
    payment_log,
    payment_application_log,
    payment_refund_log,
    collection_request_log,
    collection_log,
    collection_failure_reason_log,
    collection_failure_log,
    delivery_pev_log,
    point_calculation_log,
    certificate_level_log,
    certificate_log
TO olius_auditor;

-- CATÁLOGO — leitores, auditor e olius_catalog_editor
-- security_invoker exige leitura da view E das três tabelas documentais.
GRANT SELECT ON data_catalog_table, data_catalog_column, data_catalog_role,
    vw_data_catalog TO olius_reader, olius_auditor;
GRANT SELECT, INSERT, UPDATE, DELETE ON
    data_catalog_table, data_catalog_column, data_catalog_role
TO olius_catalog_editor;
GRANT SELECT ON vw_data_catalog TO olius_catalog_editor;

-- PAPEL olius_api — conexão confiável da Core; autorização por usuário na API.
-- Consultas de negócio incluem dados pessoais e password_hash para login.
-- Esses dados não devem ser expostos integralmente nas respostas HTTP.
GRANT SELECT ON users, establishment_type, addresses, telephone, user_qr_code,
    citizens, establishment, driver, pev, subscription_plan,
    establishment_subscription, subscription_cycle, billing_order, billing_charge,
    payment, payment_application, subscription_cycle_change, payment_refund,
    payment_refund_attempt, payment_provider_event, collection_request,
    collection_schedule_history, collection, collection_failure_reason,
    collection_failure, delivery_pev, point_calculation, point_transaction,
    certificate_level, certificate TO olius_api;

-- Cadastros: não permite escrever saldos, flags derivadas ou timestamps.
GRANT INSERT (id, name, email, password_hash, user_type),
    UPDATE (name, email, password_hash, status) ON users TO olius_api;
GRANT INSERT (id, owner_kind, state, city, neighborhood, street, number, cep,
    complement, latitude, longitude),
    UPDATE (state, city, neighborhood, street, number, cep, complement, latitude, longitude)
    ON addresses TO olius_api;
GRANT INSERT (id, telephone, user_id), UPDATE (telephone), DELETE ON telephone TO olius_api;
GRANT INSERT (user_id, qr_token, user_type), UPDATE (qr_token) ON user_qr_code TO olius_api;
GRANT INSERT (id, cpf, qr_token), UPDATE (cpf) ON citizens TO olius_api;
GRANT INSERT (id, cnpj, qr_token, description, type_id, address_id),
    UPDATE (cnpj, description, type_id, address_id) ON establishment TO olius_api;
GRANT INSERT (id, name, cpf, cnh), UPDATE (name, cpf, cnh, status) ON driver TO olius_api;
GRANT INSERT (id, citizen_id, establishment_id, address_id),
    UPDATE (status, approved_at, approved_by, address_id) ON pev TO olius_api;

-- Administração dos catálogos existentes; autenticar e autorizar ADM na Core.
GRANT INSERT (id, name, description), UPDATE (name, description)
    ON establishment_type TO olius_api;
GRANT INSERT (id, name, description, monthly_price, volume_limit_liters, collection_limit),
    UPDATE (name, description, monthly_price, volume_limit_liters, collection_limit, status)
    ON subscription_plan TO olius_api;
GRANT INSERT (id, code, name, description), UPDATE (name, description, status)
    ON collection_failure_reason TO olius_api;
GRANT INSERT (id, name, description, required_liters, badge_image_url),
    UPDATE (name, description, required_liters, badge_image_url) ON certificate_level TO olius_api;

-- Integração financeira: fatos confirmados entram uma vez; cotação não é editável.
-- A Core confirma autenticidade/valor com o provedor antes de registrar payment.
GRANT INSERT (id, establishment_id) ON establishment_subscription TO olius_api;
GRANT INSERT (id, establishment_id, subscription_id, purpose, benefit_key,
    target_cycle_id, previous_cycle_id) ON billing_order TO olius_api;
GRANT INSERT (id, billing_order_id, establishment_id, purpose, target_cycle_id,
    provider, provider_charge_id, idempotency_key, plan_id, plan_name, plan_description,
    quoted_monthly_price, quoted_volume_limit_liters, quoted_collection_limit,
    from_plan_id, from_monthly_price, amount, currency, expires_at),
    UPDATE (provider_charge_id) ON billing_charge TO olius_api;
GRANT INSERT (id, billing_charge_id, billing_order_id, establishment_id, provider,
    provider_payment_id, amount, currency, paid_at, verified_at) ON payment TO olius_api;
GRANT UPDATE (provider_refund_id, status, completed_at, next_attempt_at, last_error)
    ON payment_refund TO olius_api;
GRANT INSERT (id, refund_id, attempt_number),
    UPDATE (finished_at, provider_request_id, result_status, error_description)
    ON payment_refund_attempt TO olius_api;
GRANT INSERT (id, provider, provider_event_id, event_type, provider_payment_id),
    UPDATE (payment_id, processed_at, status, attempts, next_attempt_at, last_error)
    ON payment_provider_event TO olius_api;
GRANT UPDATE (pdf_url) ON certificate TO olius_api;

-- Pedidos, coletas, entregas, ciclos, pontos e certificados derivados:
-- alteração exclusivamente pelas rotinas públicas revisadas abaixo.

-- Roles NOLOGIN não representam as contas pessoais da equipe. Exemplos somente:
-- GRANT olius_reader TO nome_da_conta;
-- REVOKE olius_reader FROM nome_da_conta;
-- Nunca atribuir olius_owner à conta da API.

-- Objetos futuros não são cobertos pelos GRANTs acima. Na implantação das
-- rotinas, executar como a role que realmente as criará, antes de criá-las:
-- ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
-- Não aplicar FOR ROLE olius_owner presumindo que ela seja a criadora atual.

-- Propriedade compartilhada para manutenção por Pedro/Caio. Lista fechada;
-- não transfere banco, schema, extensão nem funções SECURITY DEFINER de auth.
DO $ownership$
DECLARE obj record; object_name text; matches integer;
BEGIN
    IF NOT pg_catalog.pg_has_role(current_user, 'olius_owner', 'SET') THEN
        RAISE EXCEPTION 'Implantação precisa poder SET ROLE olius_owner para transferir os objetos. Configure essa autoridade antes de executar.';
    END IF;
    FOR obj IN SELECT s.object_name, c.relkind FROM olius_permission_scope s
        JOIN pg_catalog.pg_class c ON c.oid = to_regclass(format('public.%I',s.object_name)) LOOP
        IF EXISTS (SELECT 1 FROM pg_catalog.pg_depend WHERE classid='pg_class'::regclass
                   AND objid=to_regclass(format('public.%I',obj.object_name)) AND deptype='e') THEN
            RAISE EXCEPTION 'Objeto % pertence a extensão; transferência recusada.',obj.object_name;
        END IF;
        IF obj.relkind NOT IN ('r','v') THEN
            RAISE EXCEPTION 'Tipo inesperado de objeto: %',obj.object_name;
        END IF;
        EXECUTE format('ALTER %s public.%I OWNER TO olius_owner',
                       CASE WHEN obj.relkind='v' THEN 'VIEW' ELSE 'TABLE' END,obj.object_name);
    END LOOP;
    FOREACH object_name IN ARRAY ARRAY['user_type_t', 'active_status_t', 'approval_status_t', 'request_status_t', 'operation_status_t', 'record_status_t', 'collection_result_t', 'oil_condition_t', 'address_owner_t', 'subscription_status_t', 'billing_purpose_t', 'charge_status_t', 'refund_status_t', 'refund_reason_t', 'certificate_status_t', 'cancellation_initiative_t', 'cancellation_policy_t', 'point_component_t', 'audit_actor_t', 'audit_snapshot_t', 'processing_status_t', 'auth_revocation_reason_t'] LOOP
        EXECUTE format('ALTER TYPE public.%I OWNER TO olius_owner',object_name);
    END LOOP;
    FOREACH object_name IN ARRAY ARRAY['get_cycle_availability',
        'calculate_collection_score',
        'get_certificate_progress',
        'fn_olius_audit_actor',
        'fn_olius_current_user_id',
        'fn_olius_operational_driver_id',
        'fn_olius_audit_reason',
        'fn_olius_write_audit_snapshot',
        'fn_olius_set_updated_at',
        'fn_olius_auth_token_guard',
        'fn_olius_auth_session_guard'] LOOP
        SELECT count(*) INTO matches FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='public' AND p.proname=object_name;
        IF matches <> 1 THEN
            RAISE EXCEPTION 'Rotina % ausente ou com sobrecarga não revisada.',object_name;
        END IF;
        FOR obj IN SELECT p.oid, p.prokind, p.prosecdef,
                pg_catalog.pg_get_function_identity_arguments(p.oid) AS args
            FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
            WHERE n.nspname='public' AND p.proname=object_name LOOP
            IF obj.prosecdef OR EXISTS (SELECT 1 FROM pg_catalog.pg_depend
                WHERE classid='pg_proc'::regclass AND objid=obj.oid AND deptype='e') THEN
                RAISE EXCEPTION 'Rotina % privilegiada ou de extensão: revisar propriedade.',object_name;
            END IF;
            EXECUTE format('ALTER %s public.%I(%s) OWNER TO olius_owner',
                           CASE WHEN obj.prokind='p' THEN 'PROCEDURE' ELSE 'FUNCTION' END,
                           object_name,obj.args);
        END LOOP;
    END LOOP;
END $ownership$;

-- Objetos futuros devem ser criados com SET ROLE olius_owner. Sem concessões
-- automáticas de dados aos leitores; funções novas não ficam públicas.
ALTER DEFAULT PRIVILEGES FOR ROLE olius_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- AUTENTICAÇÃO — PAPEL olius_auth_owner
-- Cria/valida o proprietário restrito e configura seus acessos às sessões.
SET LOCAL search_path = public, pg_catalog;
DO $auth_roles$
DECLARE v_name TEXT; v_role RECORD;
BEGIN
    FOREACH v_name IN ARRAY ARRAY['olius_api', 'olius_auth_owner'] LOOP
        SELECT * INTO v_role FROM pg_catalog.pg_roles WHERE rolname = v_name;
        IF NOT FOUND THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', v_name);
        ELSIF v_role.rolcanlogin OR v_role.rolsuper OR v_role.rolcreatedb
            OR v_role.rolcreaterole OR v_role.rolreplication OR v_role.rolbypassrls THEN
            RAISE EXCEPTION 'Role % possui atributos incompatíveis; revisar implantação.', v_name;
        END IF;
        IF EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m
                   JOIN pg_catalog.pg_roles r ON r.oid = m.member WHERE r.rolname = v_name) THEN
            RAISE EXCEPTION 'Role % herda outros papéis; revisar antes de instalar autenticação.', v_name;
        END IF;
    END LOOP;
END $auth_roles$;

REVOKE ALL ON auth_session, auth_refresh_token, auth_session_log FROM PUBLIC, olius_api;
GRANT USAGE ON SCHEMA public TO olius_api, olius_auth_owner;
GRANT SELECT, INSERT, UPDATE ON auth_session, auth_refresh_token TO olius_auth_owner;
GRANT INSERT ON auth_session_log TO olius_auth_owner;
GRANT SELECT (id, user_type, status), UPDATE (id) ON users TO olius_auth_owner;
-- UPDATE(id) permite SELECT FOR UPDATE. O papel é NOLOGIN, sem membership
-- da API; as rotinas não alteram users.id. Não conceder esse owner à aplicação.

-- PROPRIEDADE RESTRITA E EXECUÇÃO PELA API
-- Apenas as funções revisadas ficam disponíveis à Core e ao grupo administrador.
-- A conta de implantação deve poder SET ROLE olius_auth_owner (ou ser superusuária).
GRANT CREATE ON SCHEMA public TO olius_auth_owner;
ALTER FUNCTION public.auth_idle_duration(public.user_type_t) OWNER TO olius_auth_owner;
REVOKE ALL ON FUNCTION public.auth_idle_duration(public.user_type_t) FROM PUBLIC, olius_api;
ALTER FUNCTION public.open_auth_session(UUID, BYTEA) OWNER TO olius_auth_owner;
REVOKE ALL ON FUNCTION public.open_auth_session(UUID, BYTEA) FROM PUBLIC, olius_api;
GRANT EXECUTE ON FUNCTION public.open_auth_session(UUID, BYTEA) TO olius_api, olius_owner;
ALTER FUNCTION public.rotate_auth_refresh_token(BYTEA, BYTEA) OWNER TO olius_auth_owner;
REVOKE ALL ON FUNCTION public.rotate_auth_refresh_token(BYTEA, BYTEA) FROM PUBLIC, olius_api;
GRANT EXECUTE ON FUNCTION public.rotate_auth_refresh_token(BYTEA, BYTEA) TO olius_api, olius_owner;
ALTER FUNCTION public.revoke_auth_session(UUID, UUID) OWNER TO olius_auth_owner;
REVOKE ALL ON FUNCTION public.revoke_auth_session(UUID, UUID) FROM PUBLIC, olius_api;
GRANT EXECUTE ON FUNCTION public.revoke_auth_session(UUID, UUID) TO olius_api, olius_owner;
ALTER FUNCTION public.revoke_user_auth_sessions(UUID) OWNER TO olius_auth_owner;
REVOKE ALL ON FUNCTION public.revoke_user_auth_sessions(UUID) FROM PUBLIC, olius_api;
GRANT EXECUTE ON FUNCTION public.revoke_user_auth_sessions(UUID) TO olius_api, olius_owner;
ALTER FUNCTION public.get_auth_session_status(UUID, UUID) OWNER TO olius_auth_owner;
REVOKE ALL ON FUNCTION public.get_auth_session_status(UUID, UUID) FROM PUBLIC, olius_api;
GRANT EXECUTE ON FUNCTION public.get_auth_session_status(UUID, UUID) TO olius_api, olius_owner;
REVOKE CREATE ON SCHEMA public FROM olius_auth_owner;

GRANT CREATE ON SCHEMA public TO olius_auth_owner;
ALTER FUNCTION public.fn_olius_auth_session_audit() OWNER TO olius_auth_owner;
ALTER FUNCTION public.fn_olius_auth_user_inactive() OWNER TO olius_auth_owner;
REVOKE CREATE ON SCHEMA public FROM olius_auth_owner;
REVOKE ALL ON FUNCTION public.fn_olius_auth_session_audit(), public.fn_olius_auth_user_inactive(),
    public.fn_olius_auth_token_guard(), public.fn_olius_auth_session_guard() FROM PUBLIC, olius_api;

-- NEGÓCIO — PAPEL olius_business_owner: nunca atribuir à API ou à equipe.
DO $business_role$
DECLARE r record;
BEGIN
    SELECT * INTO r FROM pg_catalog.pg_roles WHERE rolname='olius_business_owner';
    IF NOT FOUND THEN
        CREATE ROLE olius_business_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    ELSIF r.rolcanlogin OR r.rolsuper OR r.rolcreatedb OR r.rolcreaterole
        OR r.rolreplication OR r.rolbypassrls THEN
        RAISE EXCEPTION 'olius_business_owner possui atributos incompatíveis.';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members WHERE member='olius_business_owner'::regrole) THEN
        RAISE EXCEPTION 'olius_business_owner não deve herdar outros papéis.';
    END IF;
    IF NOT pg_catalog.pg_has_role(current_user,'olius_business_owner','SET') THEN
        RAISE EXCEPTION 'Implantador precisa poder SET ROLE olius_business_owner para transferir rotinas.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_namespace n,
            LATERAL pg_catalog.aclexplode(coalesce(n.nspacl,pg_catalog.acldefault('n',n.nspowner))) a
        WHERE n.nspname='public' AND a.privilege_type='CREATE'
          AND a.grantee NOT IN (n.nspowner,(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=current_user),
            'olius_owner'::regrole,'olius_auth_owner'::regrole,'olius_business_owner'::regrole)
    ) THEN
        RAISE EXCEPTION 'CREATE no schema public concedido a outro papel: revisar confiança antes de elevar rotinas.';
    END IF;
END $business_role$;

-- Reconcilia também privilégios antigos do proprietário interno.
DO $business_reset$
DECLARE o record; c record;
BEGIN
    FOR o IN SELECT object_name FROM olius_permission_scope LOOP
        IF (SELECT relowner FROM pg_catalog.pg_class WHERE oid=to_regclass(format('public.%I',o.object_name)))='olius_business_owner'::regrole THEN
            RAISE EXCEPTION 'Proprietário interno não deve ser dono da tabela %.',o.object_name;
        END IF;
        EXECUTE format('REVOKE ALL ON TABLE public.%I FROM olius_business_owner RESTRICT',o.object_name);
        FOR c IN SELECT attname FROM pg_catalog.pg_attribute
            WHERE attrelid=to_regclass(format('public.%I',o.object_name))
              AND attnum>0 AND NOT attisdropped AND attacl IS NOT NULL LOOP
            EXECUTE format('REVOKE SELECT (%1$I), INSERT (%1$I), UPDATE (%1$I), REFERENCES (%1$I) ON public.%2$I FROM olius_business_owner RESTRICT',c.attname,o.object_name);
        END LOOP;
    END LOOP;
END $business_reset$;
GRANT USAGE ON SCHEMA public TO olius_business_owner;
GRANT SELECT (id,user_type,status), UPDATE (id) ON users TO olius_business_owner;
GRANT SELECT ON establishment, citizens, driver, pev, establishment_subscription,
    subscription_cycle, billing_order, billing_charge, payment, payment_application,
    payment_refund, collection_request, collection_schedule_history, collection,
    collection_failure_reason, collection_failure, delivery_pev, point_calculation,
    point_transaction, certificate_level, certificate,
    collection_request_log, collection_log, collection_failure_log, delivery_pev_log
    TO olius_business_owner;
-- UPDATE(id) habilita os bloqueios FOR SHARE dos validadores, sem expor essa
-- concessão a uma conexão. As rotinas revisadas não modificam esses IDs.
GRANT UPDATE (id) ON driver, pev TO olius_business_owner;
GRANT UPDATE (id) ON payment, billing_order, payment_application TO olius_business_owner;
GRANT UPDATE (points, is_pev) ON establishment TO olius_business_owner;
GRANT UPDATE (points) ON citizens TO olius_business_owner;
GRANT UPDATE (status, activated_at, inactivated_at, updated_at) ON establishment_subscription TO olius_business_owner;
GRANT UPDATE ON billing_charge TO olius_business_owner;
GRANT INSERT, UPDATE ON subscription_cycle, collection_request,
    collection, delivery_pev, point_calculation, certificate TO olius_business_owner;
GRANT INSERT ON payment_application, subscription_cycle_change, payment_refund,
    collection_schedule_history, point_transaction TO olius_business_owner;
GRANT INSERT, DELETE ON collection_failure TO olius_business_owner;
DO $business_logs$
DECLARE o record;
BEGIN
    FOR o IN SELECT object_name FROM olius_permission_scope
        WHERE object_name LIKE '%\_log' ESCAPE '\' AND object_name <> 'auth_session_log' LOOP
        EXECUTE format('GRANT INSERT ON public.%I TO olius_business_owner',o.object_name);
    END LOOP;
END $business_logs$;

-- Propriedade, search_path e EXECUTE por lista fechada. Só os entrypoints
-- públicos elevam privilégio; manutenção de pontos/certificados fica interna.
GRANT CREATE ON SCHEMA public TO olius_business_owner;
DO $business_routines$
DECLARE name text; r record; matches integer; kind text; internal boolean;
BEGIN
    FOREACH name IN ARRAY ARRAY['rebuild_user_points','reconcile_establishment_certificates',
        'apply_verified_payment','create_collection_request','schedule_collection_request',
        'cancel_collection_request','record_collection','record_pev_delivery','correct_collection','correct_pev_delivery',
        'fn_olius_audit_trigger','fn_olius_sync_establishment_is_pev',
        'record_collection_arrival','accept_collection_service','reject_collection_request','set_billing_charge_external_status'] LOOP
        SELECT count(*) INTO matches FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
            WHERE n.nspname='public' AND p.proname=name;
        IF matches <> 1 THEN RAISE EXCEPTION 'Rotina % ausente ou com sobrecarga não revisada.',name; END IF;
        SELECT p.*,pg_catalog.pg_get_function_identity_arguments(p.oid) AS args INTO r
            FROM pg_catalog.pg_proc p WHERE p.pronamespace='public'::regnamespace AND p.proname=name;
        IF EXISTS(SELECT 1 FROM pg_catalog.pg_depend WHERE classid='pg_proc'::regclass AND objid=r.oid AND deptype='e')
            OR r.prokind NOT IN ('p','f') THEN RAISE EXCEPTION 'Rotina não compatível: %',name; END IF;
        IF EXISTS (SELECT 1 FROM pg_catalog.aclexplode(coalesce(r.proacl,pg_catalog.acldefault('f',r.proowner))) a
            WHERE a.privilege_type='EXECUTE' AND a.grantee NOT IN
                (0,r.proowner,'olius_owner'::regrole,'olius_api'::regrole,'olius_business_owner'::regrole)) THEN
            RAISE EXCEPTION 'Rotina % tem EXECUTE externo: revisar antes de elevar privilégios.',name;
        END IF;
        kind:=CASE WHEN r.prokind='p' THEN 'PROCEDURE' ELSE 'FUNCTION' END;
        internal:=name IN ('rebuild_user_points','reconcile_establishment_certificates');
        EXECUTE format('ALTER %s public.%I(%s) OWNER TO olius_business_owner',kind,name,r.args);
        EXECUTE format('ALTER %s public.%I(%s) SECURITY %s',kind,name,r.args,CASE WHEN internal THEN 'INVOKER' ELSE 'DEFINER' END);
        EXECUTE format('ALTER %s public.%I(%s) SET search_path = pg_catalog, public, pg_temp',kind,name,r.args);
        EXECUTE format('REVOKE ALL ON %s public.%I(%s) FROM PUBLIC, olius_api, olius_owner RESTRICT',kind,name,r.args);
        IF name NOT IN ('fn_olius_audit_trigger','fn_olius_sync_establishment_is_pev') THEN
            EXECUTE format('GRANT EXECUTE ON %s public.%I(%s) TO olius_owner',kind,name,r.args);
            IF NOT internal THEN EXECUTE format('GRANT EXECUTE ON %s public.%I(%s) TO olius_api',kind,name,r.args); END IF;
        END IF;
    END LOOP;
    FOREACH name IN ARRAY ARRAY['get_cycle_availability','calculate_collection_score','get_certificate_progress',
        'fn_olius_audit_actor','fn_olius_current_user_id','fn_olius_operational_driver_id',
        'fn_olius_audit_reason','fn_olius_write_audit_snapshot','fn_olius_set_updated_at'] LOOP
        SELECT p.*,pg_catalog.pg_get_function_identity_arguments(p.oid) AS args INTO STRICT r
            FROM pg_catalog.pg_proc p WHERE p.pronamespace='public'::regnamespace AND p.proname=name;
        EXECUTE format('REVOKE ALL ON FUNCTION public.%I(%s) FROM PUBLIC, olius_api RESTRICT',name,r.args);
        EXECUTE format('GRANT EXECUTE ON FUNCTION public.%I(%s) TO olius_business_owner',name,r.args);
        IF name IN ('get_cycle_availability','calculate_collection_score','get_certificate_progress') THEN
            EXECUTE format('GRANT EXECUTE ON FUNCTION public.%I(%s) TO olius_api',name,r.args);
        END IF;
    END LOOP;
END $business_routines$;
REVOKE CREATE ON SCHEMA public FROM olius_business_owner;
ALTER DEFAULT PRIVILEGES FOR ROLE olius_business_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

COMMIT;

-- Conferência: presença no servidor e privilégios refletidos no catálogo.
SELECT role_name, description FROM data_catalog_role ORDER BY role_name;
SELECT DISTINCT table_name, access_status FROM vw_data_catalog
WHERE schema_name = 'public' ORDER BY table_name, access_status;
