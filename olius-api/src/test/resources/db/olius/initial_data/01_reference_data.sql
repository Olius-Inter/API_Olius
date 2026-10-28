/* OLIUS — Configuração oficial inicial, aprovada em 20/10/2026.
   Executar após estrutura, rotinas, triggers e permissões, com conta de implantação.
   Não contém usuários, senhas ou movimentos. Executar o arquivo inteiro.
   Em erro, ROLLBACK; divergências exigem revisão, nunca são sobrescritas.
*/
BEGIN;
SET LOCAL search_path = pg_catalog, public, pg_temp;
SELECT set_config('app.audit_actor', 'SYSTEM', TRUE),
       set_config('app.current_user_id', '', TRUE),
       set_config('app.operational_driver_id', '', TRUE),
       set_config('app.audit_reason', 'Carga inicial oficial OLIUS v1', TRUE);

-- Serializa cargas e impede mudanças concorrentes nos quatro catálogos.
LOCK TABLE public.subscription_plan, public.certificate_level,
    public.establishment_type, public.collection_failure_reason IN SHARE ROW EXCLUSIVE MODE;

DO $reference_data$
DECLARE
    catalogs CONSTANT JSONB := $catalogs${
    "subscription_plan": [
        {
            "name": "Plano Básico",
            "monthly_price": 19.9,
            "volume_limit_liters": 50,
            "collection_limit": 2,
            "status": "ACTIVE"
        },
        {
            "name": "Plano Profissional",
            "monthly_price": 39.9,
            "volume_limit_liters": 220,
            "collection_limit": 4,
            "status": "ACTIVE"
        },
        {
            "name": "Plano Empresa",
            "monthly_price": 79.9,
            "volume_limit_liters": 500,
            "collection_limit": 8,
            "status": "ACTIVE"
        }
    ],
    "certificate_level": [
        {
            "name": "Óleo Amigo — Bronze",
            "required_liters": 100
        },
        {
            "name": "Óleo Amigo — Prata",
            "required_liters": 500
        },
        {
            "name": "Óleo Amigo — Ouro",
            "required_liters": 1000
        }
    ],
    "establishment_type": [
        {
            "name": "Restaurante"
        },
        {
            "name": "Lanchonete"
        },
        {
            "name": "Padaria e confeitaria"
        },
        {
            "name": "Bar"
        },
        {
            "name": "Hotel e pousada"
        },
        {
            "name": "Cozinha institucional"
        },
        {
            "name": "Outro"
        }
    ],
    "collection_failure_reason": [
        {
            "code": "VOLUME_OUT_OF_TOLERANCE",
            "name": "Volume fora da tolerância",
            "description": "Volume efetivamente recolhido fora da tolerância definida para a solicitação.",
            "status": "ACTIVE"
        },
        {
            "code": "OIL_UNACCEPTABLE",
            "name": "Óleo em condição inadequada",
            "description": "Condição inadequada do óleo registrada na coleta malsucedida.",
            "status": "ACTIVE"
        },
        {
            "code": "COMPROMISING_OCCURRENCE",
            "name": "Ocorrência que comprometeu a coleta",
            "description": "Ocorrência comprometedora registrada na coleta malsucedida.",
            "status": "ACTIVE"
        }
    ]
}$catalogs$::JSONB;
    catalog RECORD;
    item JSONB;
    key_column TEXT;
    key_value TEXT;
    existing_count BIGINT;
    existing_row JSONB;
    stable_id UUID;
    columns_sql TEXT;
    values_sql TEXT;
BEGIN
    FOR catalog IN SELECT key AS table_name, value AS rows FROM jsonb_each(catalogs) ORDER BY key LOOP
        key_column := CASE WHEN catalog.table_name = 'collection_failure_reason' THEN 'code' ELSE 'name' END;
        FOR item IN SELECT value FROM jsonb_array_elements(catalog.rows) LOOP
            key_value := item ->> key_column;
            EXECUTE format('SELECT count(*), (jsonb_agg(to_jsonb(t)))->0 FROM public.%I t WHERE %I = $1',
                           catalog.table_name, key_column)
                INTO existing_count, existing_row USING key_value;
            IF existing_count > 1 THEN
                RAISE EXCEPTION 'Carga inicial: chave ambígua em %.% = %.', catalog.table_name, key_column, key_value;
            ELSIF existing_count = 1 THEN
                IF NOT existing_row @> item THEN
                    RAISE EXCEPTION 'Carga inicial: configuração divergente em % (%). Revisar antes de prosseguir.',
                        catalog.table_name, key_value;
                END IF;
                CONTINUE;
            END IF;
            -- Namespace separado da demonstração. MD5 gera identidade, não segredo.
            stable_id := md5('olius-reference-v1:' || catalog.table_name || ':' || key_value)::UUID;
            item := item || jsonb_build_object('id', stable_id);
            SELECT string_agg(format('%I', key), ', ' ORDER BY key),
                   string_agg(format('r.%I', key), ', ' ORDER BY key)
                INTO columns_sql, values_sql FROM jsonb_object_keys(item) AS keys(key);
            EXECUTE format('INSERT INTO public.%I (%s) SELECT %s FROM jsonb_populate_record(NULL::public.%I, $1) r',
                           catalog.table_name, columns_sql, values_sql, catalog.table_name) USING item;
        END LOOP;
    END LOOP;
END;
$reference_data$;
COMMIT;
