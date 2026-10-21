/* Consultas somente de leitura. Executar após a carga, com o mesmo schema ativo.
   Resultado esperado na implantação inicial: primeira consulta sem linhas;
   61 tabelas no resumo (58 da aplicação + 3 documentais); roles documentadas e existência física conferidas.
   Quantidades são referência desta versão, não limites para evoluções futuras. */

-- Diferenças entre objetos reais e documentação. Não remove registros antigos.
WITH physical_tables AS (
    SELECT n.nspname AS schema_name, c.relname AS table_name, c.oid
    FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = current_schema() AND c.relkind IN ('r','p')
      AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend dep
          WHERE dep.classid = 'pg_class'::regclass AND dep.objid = c.oid AND dep.deptype = 'e')
), physical_columns AS (
    SELECT t.*, a.attname AS column_name FROM physical_tables t
    JOIN pg_catalog.pg_attribute a ON a.attrelid = t.oid AND a.attnum > 0 AND NOT a.attisdropped
), issues AS (
    SELECT 'TABLE_NOT_CATALOGED' AS issue, p.schema_name, p.table_name, NULL::text AS column_name
    FROM physical_tables p LEFT JOIN data_catalog_table d
        ON (d.schema_name, d.table_name) = (p.schema_name, p.table_name)
    WHERE d.id IS NULL
    UNION ALL
    SELECT 'COLUMN_NOT_CATALOGED', p.schema_name, p.table_name, p.column_name
    FROM physical_columns p JOIN data_catalog_table d
        ON (d.schema_name, d.table_name) = (p.schema_name, p.table_name)
    LEFT JOIN data_catalog_column dc ON dc.table_catalog_id = d.id AND dc.column_name = p.column_name
    WHERE dc.id IS NULL
    UNION ALL
    SELECT 'TABLE_REMOVED_OR_RENAMED', d.schema_name, d.table_name, NULL::text
    FROM data_catalog_table d LEFT JOIN physical_tables p
        ON (d.schema_name, d.table_name) = (p.schema_name, p.table_name)
    WHERE d.schema_name = current_schema() AND p.oid IS NULL
    UNION ALL
    SELECT 'COLUMN_REMOVED_OR_RENAMED', d.schema_name, d.table_name, dc.column_name
    FROM data_catalog_table d JOIN data_catalog_column dc ON dc.table_catalog_id = d.id
    JOIN physical_tables pt ON (pt.schema_name, pt.table_name) = (d.schema_name, d.table_name)
    LEFT JOIN physical_columns pc ON pc.oid = pt.oid AND pc.column_name = dc.column_name
    WHERE pc.column_name IS NULL
)
SELECT * FROM issues ORDER BY schema_name, table_name, column_name, issue;

-- Contagem independente das colunas para evitar multiplicar o total de tabelas.
SELECT
    (SELECT count(*) FROM data_catalog_table WHERE schema_name = current_schema()) AS cataloged_tables,
    (SELECT count(*) FROM data_catalog_column dc JOIN data_catalog_table d ON d.id = dc.table_catalog_id
        WHERE d.schema_name = current_schema()) AS cataloged_columns;

SELECT d.table_name, dc.column_name, dc.documentation_status
FROM data_catalog_table d JOIN data_catalog_column dc ON dc.table_catalog_id = d.id
WHERE d.schema_name = current_schema() AND dc.documentation_status = 'PENDING'
ORDER BY d.table_name, dc.column_name;

-- Pendências são decisões explícitas, não autorização tácita de acesso.
SELECT table_name, access_status, access_policy
FROM data_catalog_table
WHERE schema_name = current_schema() AND access_status = 'PENDING'
ORDER BY table_name;

-- Reconhece tabelas novas cuja finalidade ainda não foi classificada.
SELECT table_name, domain_name, business_rules
FROM data_catalog_table
WHERE schema_name = current_schema() AND domain_name = 'Pendente de classificação'
ORDER BY table_name;

-- Ausência no servidor é pendência de implantação, não negação de privilégio.
SELECT d.role_name, d.description, r.oid IS NOT NULL AS role_exists
FROM data_catalog_role d LEFT JOIN pg_catalog.pg_roles r ON r.rolname = d.role_name
ORDER BY d.role_name;
