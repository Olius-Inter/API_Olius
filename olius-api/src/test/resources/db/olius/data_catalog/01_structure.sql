/* OLIUS — Catálogo de Dados — PostgreSQL 16
   Executar no mesmo banco/schema dos scripts principais, após os cinco scripts principais (01 a 05).
   Este módulo contém apenas metadados: nunca copie valores reais de usuários. */

CREATE TABLE data_catalog_table (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schema_name TEXT NOT NULL,
    table_name TEXT NOT NULL,
    domain_name TEXT NOT NULL,
    description TEXT NOT NULL,
    business_rules TEXT NOT NULL,
    access_policy TEXT NOT NULL,
    access_status TEXT NOT NULL DEFAULT 'PENDING',
    source_reference TEXT NOT NULL,
    CONSTRAINT uq_data_catalog_table_name UNIQUE (schema_name, table_name)
);

COMMENT ON TABLE data_catalog_table IS
'Metadados documentais das tabelas. Política descrita não concede privilégios nem implementa regras de negócio.';

CREATE TABLE data_catalog_column (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    table_catalog_id UUID NOT NULL,
    column_name TEXT NOT NULL,
    description TEXT NOT NULL,
    business_rules TEXT,
    access_policy TEXT,
    documentation_status TEXT NOT NULL DEFAULT 'DOCUMENTED',
    source_reference TEXT NOT NULL,
    CONSTRAINT uq_data_catalog_column_name UNIQUE (table_catalog_id, column_name),
    CONSTRAINT fk_data_catalog_column_table FOREIGN KEY (table_catalog_id)
        REFERENCES data_catalog_table (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE data_catalog_column IS
'Significado e regras de cada coluna; os tipos e as constraints são consultados diretamente no PostgreSQL.';
COMMENT ON COLUMN data_catalog_column.access_policy IS
'NULL herda a política documental da tabela; texto preenchido complementa essa política, sem conceder privilégios.';
COMMENT ON COLUMN data_catalog_column.documentation_status IS
'PENDING identifica coluna nova cujo significado ainda precisa de revisão humana.';

-- Apenas o responsável pela implantação decide as concessões a roles reais.
-- REVOKE de PUBLIC não elimina privilégios próprios do dono ou grants diretos.
REVOKE ALL ON data_catalog_table, data_catalog_column FROM PUBLIC;
-- UNIQUE(table_catalog_id, column_name) já cobre consultas pela FK;
-- o volume pequeno do catálogo não justifica índices adicionais.


CREATE TABLE data_catalog_role (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_name TEXT NOT NULL,
    description TEXT NOT NULL,
    CONSTRAINT uq_data_catalog_role_name UNIQUE (role_name)
);
COMMENT ON TABLE data_catalog_role IS
'Roles técnicas documentadas. O cadastro não cria roles PostgreSQL nem concede privilégios; associação ao servidor pelo nome exato.';
REVOKE ALL ON data_catalog_role FROM PUBLIC;
