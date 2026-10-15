-- Instalação nova. Para substituir uma view anterior, usar upgrade_view.sql.
-- security_invoker respeita os privilégios do leitor nas tabelas do catálogo.
-- A view expõe metadados, não linhas de negócio; sua leitura exige concessão
-- explícita na view e nas três tabelas documentais. Não há SECURITY DEFINER.
CREATE VIEW vw_data_catalog WITH (security_invoker = true) AS
SELECT
    d.schema_name, d.table_name, d.domain_name,
    d.description AS table_description,
    d.business_rules AS table_business_rules,
    d.access_policy AS table_access_policy, access.role_name AS access_status,
    d.source_reference AS table_source,
    dc.column_name, dc.description AS column_description,
    pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type,
    NOT a.attnotnull AS is_nullable,
    pg_catalog.pg_get_expr(ad.adbin, ad.adrelid) AS default_or_generation_expression,
    COALESCE(keys.items, '[]'::jsonb) AS column_constraints,
    COALESCE(all_keys.items, '[]'::jsonb) AS table_constraints,
    COALESCE(idx.items, '[]'::jsonb) AS table_indexes,
    COALESCE(enums.items, '[]'::jsonb) AS enum_values,
    pg_catalog.pg_get_userbyid(c.relowner) AS table_owner
FROM data_catalog_table d
JOIN data_catalog_column dc ON dc.table_catalog_id = d.id
JOIN pg_catalog.pg_namespace ns ON ns.nspname = d.schema_name
JOIN pg_catalog.pg_class c
    ON c.relnamespace = ns.oid AND c.relname = d.table_name AND c.relkind IN ('r', 'p')
JOIN pg_catalog.pg_attribute a
    ON a.attrelid = c.oid AND a.attname = dc.column_name
    AND a.attnum > 0 AND NOT a.attisdropped
LEFT JOIN pg_catalog.pg_attrdef ad ON ad.adrelid = c.oid AND ad.adnum = a.attnum
LEFT JOIN LATERAL (
    SELECT jsonb_agg(jsonb_build_object(
        'name', k.conname, 'type', k.contype,
        'definition', pg_catalog.pg_get_constraintdef(k.oid, true)
    ) ORDER BY k.conname) AS items
    FROM pg_catalog.pg_constraint k
    WHERE k.conrelid = c.oid AND a.attnum = ANY(k.conkey)
) keys ON true
-- A definição completa preserva FKs/UNIQUEs compostas e exclusões de períodos.
LEFT JOIN LATERAL (
    SELECT jsonb_agg(jsonb_build_object(
        'name', k.conname, 'type', k.contype,
        'definition', pg_catalog.pg_get_constraintdef(k.oid, true)
    ) ORDER BY k.conname) AS items
    FROM pg_catalog.pg_constraint k WHERE k.conrelid = c.oid
) all_keys ON true
LEFT JOIN LATERAL (
    SELECT jsonb_agg(pg_catalog.pg_get_indexdef(i.indexrelid)
                     ORDER BY i.indexrelid::regclass::text) AS items
    FROM pg_catalog.pg_index i WHERE i.indrelid = c.oid
) idx ON true
LEFT JOIN LATERAL (
    SELECT jsonb_agg(e.enumlabel ORDER BY e.enumsortorder) AS items
    FROM pg_catalog.pg_enum e WHERE e.enumtypid = a.atttypid
) enums ON true
-- Uma linha por coluna/role com privilégio de dados e USAGE no schema.
-- has_column_privilege também reconhece concessões na tabela inteira,
-- herança disponível e PUBLIC. Não confundir REFERENCES/TRIGGER com acesso a dados.
-- Sem role elegível, a coluna permanece visível com access_status NULL.
LEFT JOIN LATERAL (
    SELECT dr.role_name
    FROM data_catalog_role dr
    JOIN pg_catalog.pg_roles pr ON pr.rolname = dr.role_name
    WHERE pg_catalog.has_schema_privilege(pr.oid, ns.oid, 'USAGE')
      AND (pg_catalog.has_column_privilege(pr.oid, c.oid, a.attnum, 'SELECT,INSERT,UPDATE')
        OR pg_catalog.has_table_privilege(pr.oid, c.oid, 'DELETE,TRUNCATE'))
) access ON true
WHERE d.table_name NOT IN ('data_catalog_table', 'data_catalog_column', 'data_catalog_role');

COMMENT ON VIEW vw_data_catalog IS
'Dicionário de tabelas de negócio e auditoria. access_status é o nome da role técnica com privilégio de dados, ou NULL se nenhuma role cadastrada for elegível. Não inclui as tabelas do catálogo.';
REVOKE ALL ON vw_data_catalog FROM PUBLIC;
