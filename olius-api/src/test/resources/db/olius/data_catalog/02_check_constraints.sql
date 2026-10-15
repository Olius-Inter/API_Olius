-- Metadados vazios impediriam compreender os objetos catalogados.
ALTER TABLE data_catalog_table
    ADD CONSTRAINT ck_data_catalog_table_text CHECK (
        btrim(schema_name) <> '' AND btrim(table_name) <> ''
        AND btrim(domain_name) <> '' AND btrim(description) <> ''
        AND btrim(business_rules) <> '' AND btrim(access_policy) <> ''
        AND btrim(source_reference) <> ''),
    ADD CONSTRAINT ck_data_catalog_table_access CHECK (
        access_status IN ('PENDING', 'DEFINED'));

-- DEFINED significa política documentada, não comprova GRANT/RLS implementado.
ALTER TABLE data_catalog_column
    ADD CONSTRAINT ck_data_catalog_column_text CHECK (
        btrim(column_name) <> '' AND btrim(description) <> ''
        AND btrim(source_reference) <> ''
        AND (business_rules IS NULL OR btrim(business_rules) <> '')
        AND (access_policy IS NULL OR btrim(access_policy) <> '')),
    ADD CONSTRAINT ck_data_catalog_column_status CHECK (
        documentation_status IN ('DOCUMENTED', 'PENDING'));


-- Nome documental deve caber em um identificador PostgreSQL sem truncamento.
ALTER TABLE data_catalog_role
    ADD CONSTRAINT ck_data_catalog_role_text CHECK (
        btrim(role_name) <> '' AND role_name = btrim(role_name)
        AND octet_length(role_name) <= 63 AND btrim(description) <> '');

