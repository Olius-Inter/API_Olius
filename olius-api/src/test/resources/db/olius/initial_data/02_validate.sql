/* OLIUS — Conferência independente do contrato oficial inicial.
   Somente leitura; aceita outros registros legítimos e IDs preexistentes.
   As relações esperadas verificam os valores sem repetir o algoritmo da carga.
   Descrições/imagens opcionais não especificadas no contrato são preservadas.
*/
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL search_path = pg_catalog, public, pg_temp;
DO $validate_reference_data$
DECLARE
    discrepancies TEXT;
BEGIN
    WITH expected_plans(name, price, liters, slots) AS (
        VALUES
            ('Plano Básico', 19.90, 50, 2),
            ('Plano Profissional', 39.90, 220, 4),
            ('Plano Empresa', 79.90, 500, 8)
    ), expected_levels(name, liters) AS (
        VALUES
            ('Óleo Amigo — Bronze', 100),
            ('Óleo Amigo — Prata', 500),
            ('Óleo Amigo — Ouro', 1000)
    ), expected_types(name) AS (
        VALUES
            ('Restaurante'),
            ('Lanchonete'),
            ('Padaria e confeitaria'),
            ('Bar'),
            ('Hotel e pousada'),
            ('Cozinha institucional'),
            ('Outro')
    ), expected_reasons(code, name, description) AS (
        VALUES
            ('VOLUME_OUT_OF_TOLERANCE', 'Volume fora da tolerância',
             'Volume efetivamente recolhido fora da tolerância definida para a solicitação.'),
            ('OIL_UNACCEPTABLE', 'Óleo em condição inadequada',
             'Condição inadequada do óleo registrada na coleta malsucedida.'),
            ('COMPROMISING_OCCURRENCE', 'Ocorrência que comprometeu a coleta',
             'Ocorrência comprometedora registrada na coleta malsucedida.'),
            ('ESTABLISHMENT_CLOSED', 'Estabelecimento fechado', 'Visita sem atendimento: estabelecimento fechado, registrada por ADMIN após chegada do motorista.'),
            ('NO_RESPONSIBLE_AVAILABLE', 'Responsável indisponível', 'Visita sem atendimento: ausência de responsável para confirmar o atendimento, registrada por ADMIN após chegada.')
    ), expected(table_name, record_key, attributes) AS (
        SELECT 'subscription_plan', name,
            jsonb_build_object('name', name, 'monthly_price', price,
                'volume_limit_liters', liters, 'collection_limit', slots, 'status', 'ACTIVE')
        FROM expected_plans
        UNION ALL
        SELECT 'certificate_level', name, jsonb_build_object('name', name, 'required_liters', liters)
        FROM expected_levels
        UNION ALL
        SELECT 'establishment_type', name, jsonb_build_object('name', name)
        FROM expected_types
        UNION ALL
        SELECT 'collection_failure_reason', code,
            jsonb_build_object('code', code, 'name', name, 'description', description, 'status', 'ACTIVE')
        FROM expected_reasons
    ), actual(table_name, record_key, attributes) AS (
        SELECT 'subscription_plan', p.name, to_jsonb(p) FROM public.subscription_plan p
        UNION ALL
        SELECT 'certificate_level', l.name, to_jsonb(l) FROM public.certificate_level l
        UNION ALL
        SELECT 'establishment_type', t.name, to_jsonb(t) FROM public.establishment_type t
        UNION ALL
        SELECT 'collection_failure_reason', r.code, to_jsonb(r) FROM public.collection_failure_reason r
    ), invalid AS (
        SELECT e.table_name, e.record_key
        FROM expected e LEFT JOIN actual a
            ON a.table_name = e.table_name AND a.record_key = e.record_key
        GROUP BY e.table_name, e.record_key, e.attributes
        HAVING count(a.attributes) <> 1
            OR bool_and(a.attributes @> e.attributes) IS DISTINCT FROM TRUE
    )
    SELECT string_agg(format('%s (%s)', table_name, record_key), ', ' ORDER BY table_name, record_key)
    INTO discrepancies FROM invalid;

    IF discrepancies IS NOT NULL THEN
        RAISE EXCEPTION 'Validação inicial: registro ausente, duplicado ou divergente em %.', discrepancies;
    END IF;
    RAISE NOTICE 'Configuração oficial validada: 3 planos, 3 níveis, 7 tipos e 5 motivos (18 registros).';
END;
$validate_reference_data$;
COMMIT;
