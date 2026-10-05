/*
===============================================================================
PROJETO.............: ÓLEO AMIGO — OLIUS
BANCO DE DADOS......: PostgreSQL 16.15 (alvo)
SCRIPT..............: 01 - Estrutura do Banco de Dados
===============================================================================
*/


-- btree_gist permite impedir ciclos sobrepostos por estabelecimento.
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

-- Dependem das rotinas: reservas concorrentes, classificação pela estimativa,
-- recorrência, revisões de saldo, validação do PEV, pagamentos e auditoria.
-- A aplicação não deve ser dona das tabelas nem editar saldos ou logs diretamente.

-- ENUMS

-- Os ENUMs limitam os valores aceitos; as transições entre estados dependem
-- das validações e rotinas, não apenas da existência destes tipos.
CREATE TYPE user_type_t AS ENUM ('ESTABLISHMENT', 'CITIZENS', 'ADMIN');
CREATE TYPE active_status_t AS ENUM ('ACTIVE', 'INACTIVE');
CREATE TYPE approval_status_t AS ENUM ('PENDING', 'APPROVED', 'INACTIVE', 'REJECTED');
CREATE TYPE request_status_t AS ENUM ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED');
CREATE TYPE operation_status_t AS ENUM ('INSERT', 'UPDATE', 'DELETE');
CREATE TYPE record_status_t AS ENUM ('RECORDED', 'ANNULLED');
CREATE TYPE collection_result_t AS ENUM ('SUCCESSFUL', 'UNSUCCESSFUL');
CREATE TYPE oil_condition_t AS ENUM ('ACCEPTABLE', 'UNACCEPTABLE', 'NOT_ASSESSED');
CREATE TYPE address_owner_t AS ENUM ('ESTABLISHMENT', 'PEV');
CREATE TYPE subscription_status_t AS ENUM ('PENDING', 'ACTIVE', 'INACTIVE');
CREATE TYPE billing_purpose_t AS ENUM ('INITIAL', 'RENEWAL', 'UPGRADE');
CREATE TYPE charge_status_t AS ENUM ('OPEN', 'CANCELLATION_PENDING', 'PAID', 'CANCELLED', 'EXPIRED');
CREATE TYPE refund_status_t AS ENUM ('REQUESTED', 'PROCESSING', 'FAILED', 'COMPLETED');
CREATE TYPE refund_reason_t AS ENUM ('DUPLICATE_BENEFIT', 'EXPIRED_UPGRADE');
CREATE TYPE certificate_status_t AS ENUM ('ACTIVE', 'REVOKED');
CREATE TYPE cancellation_initiative_t AS ENUM ('ESTABLISHMENT', 'OPERATION');
CREATE TYPE cancellation_policy_t AS ENUM ('FREE', 'LATE_FORFEITURE');
CREATE TYPE point_component_t AS ENUM ('VOLUME', 'SUCCESS_BONUS', 'RECURRENCE_BONUS', 'FAILURE_PENALTY');
CREATE TYPE audit_actor_t AS ENUM ('USER', 'DRIVER_FORM', 'SYSTEM');
CREATE TYPE audit_snapshot_t AS ENUM ('BEFORE', 'AFTER');
CREATE TYPE processing_status_t AS ENUM ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED');

CREATE TABLE users (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    user_type user_type_t NOT NULL,
    status active_status_t NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- A chave composta permite às FKs validar também o perfil do usuário.
    -- Embora id já seja único, a combinação é necessária como alvo dessas FKs.
    CONSTRAINT uq_users_id_type UNIQUE (id, user_type)
);

COMMENT ON TABLE users IS 'Usuários autenticados. Driver não possui login. Inativar para preservar histórico.';

CREATE TABLE establishment_type (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255)
);

COMMENT ON TABLE establishment_type IS 'Catálogo de tipos de estabelecimento.';

CREATE TABLE addresses (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_kind address_owner_t NOT NULL,
    state CHAR(2) NOT NULL,
    city VARCHAR(100) NOT NULL,
    neighborhood VARCHAR(100) NOT NULL,
    street VARCHAR(150) NOT NULL,
    number VARCHAR(20) NOT NULL,
    cep CHAR(8) NOT NULL,
    complement VARCHAR(150),
    latitude DECIMAL(9,6),
    longitude DECIMAL(9,6),
    -- O tipo do proprietário, combinado às FKs e ao UNIQUE de address_id
    -- nas tabelas consumidoras, impede compartilhar o mesmo registro entre donos.
    CONSTRAINT uq_addresses_owner UNIQUE (id, owner_kind)
);

COMMENT ON TABLE addresses IS 'Registro próprio por estabelecimento ou PEV; conteúdo textual pode se repetir.';

CREATE TABLE telephone (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    telephone VARCHAR(20) NOT NULL,
    user_id UUID NOT NULL,
    CONSTRAINT uq_telephone_user_number UNIQUE (user_id, telephone),
    CONSTRAINT fk_telephone_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE telephone IS 'Um número pode pertencer a vários usuários, sem repetição para o mesmo usuário.';

-- A unicidade do token é centralizada para abranger B2C e B2B. A rotação
-- neste registro propaga o novo token às especializações via ON UPDATE CASCADE.
CREATE TABLE user_qr_code (
user_id UUID PRIMARY KEY,
    qr_token VARCHAR(64) NOT NULL UNIQUE,
    user_type user_type_t NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_qr_owner_token UNIQUE (user_id, qr_token),
    CONSTRAINT fk_user_qr_type FOREIGN KEY (user_id, user_type)
        REFERENCES users (id, user_type) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE user_qr_code IS 'Registro central do token vigente. Unicidade global B2C/B2B; sem histórico do conteúdo dos tokens.';

-- Citizens e establishment reutilizam a identidade de users. O perfil gerado
-- e a FK composta impedem vincular a especialização a um usuário de outro tipo.
CREATE TABLE citizens (
id UUID PRIMARY KEY,
    cpf CHAR(11) NOT NULL UNIQUE,
    user_type user_type_t GENERATED ALWAYS AS ('CITIZENS'::user_type_t) STORED,
    qr_token VARCHAR(64) NOT NULL,
    points BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_citizens_user_type FOREIGN KEY (id, user_type)
        REFERENCES users (id, user_type) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_citizens_qr FOREIGN KEY (id, qr_token)
        REFERENCES user_qr_code (user_id, qr_token)
        ON UPDATE CASCADE ON DELETE RESTRICT
);

COMMENT ON TABLE citizens IS 'Especialização de users. Saldo derivado das revisões de pontos; atualização somente pelas rotinas.';

CREATE TABLE establishment (
id UUID PRIMARY KEY,
    cnpj CHAR(14) NOT NULL UNIQUE,
    user_type user_type_t GENERATED ALWAYS AS ('ESTABLISHMENT'::user_type_t) STORED,
    qr_token VARCHAR(64) NOT NULL,
    points BIGINT NOT NULL DEFAULT 0,
    description TEXT,
    is_pev BOOLEAN NOT NULL DEFAULT FALSE,
    type_id UUID NOT NULL,
    address_id UUID NOT NULL UNIQUE,
    address_kind address_owner_t GENERATED ALWAYS AS ('ESTABLISHMENT'::address_owner_t) STORED,
    CONSTRAINT fk_establishment_user_type FOREIGN KEY (id, user_type)
        REFERENCES users (id, user_type) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_establishment_qr FOREIGN KEY (id, qr_token)
        REFERENCES user_qr_code (user_id, qr_token)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_establishment_type FOREIGN KEY (type_id)
        REFERENCES establishment_type (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_establishment_address FOREIGN KEY (address_id, address_kind)
        REFERENCES addresses (id, owner_kind) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE establishment IS 'Especialização de users. Saldo derivado das revisões de pontos; atualização somente pelas rotinas.';

CREATE TABLE driver (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL,
    cpf CHAR(11) NOT NULL UNIQUE,
    cnh CHAR(11) NOT NULL UNIQUE,
    registration_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status active_status_t NOT NULL DEFAULT 'ACTIVE'
);

COMMENT ON TABLE driver IS 'Participante operacional cadastrado; não equivale a usuário autenticado.';

-- Os UNIQUEs dos responsáveis limitam cada cidadão ou estabelecimento a um PEV.
-- A exigência de exatamente um dos dois responsáveis fica nas CHECKs do script 02.
-- As FKs com admin_type validam o perfil; a autorização da ação cabe ao backend.
CREATE TABLE pev (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status approval_status_t NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_at TIMESTAMPTZ,
    approved_by UUID,
    admin_type user_type_t GENERATED ALWAYS AS ('ADMIN'::user_type_t) STORED,
    citizen_id UUID UNIQUE,
    establishment_id UUID UNIQUE,
    address_id UUID NOT NULL UNIQUE,
    address_kind address_owner_t GENERATED ALWAYS AS ('PEV'::address_owner_t) STORED,
    CONSTRAINT fk_pev_citizen FOREIGN KEY (citizen_id)
        REFERENCES citizens (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_pev_establishment FOREIGN KEY (establishment_id)
        REFERENCES establishment (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_pev_address FOREIGN KEY (address_id, address_kind)
        REFERENCES addresses (id, owner_kind) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_pev_admin FOREIGN KEY (approved_by, admin_type)
        REFERENCES users (id, user_type) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE pev IS 'Exatamente um responsável. Inativação preserva aprovação. Sincronizar is_pev na mesma transação.';

CREATE TABLE subscription_plan (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    monthly_price DECIMAL(10,2) NOT NULL,
    volume_limit_liters DECIMAL(8,2) NOT NULL,
    collection_limit INTEGER NOT NULL,
    status active_status_t NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE subscription_plan IS 'Catálogo comercial. Cobranças e ciclos mantêm cópias das condições contratadas.';

CREATE TABLE establishment_subscription (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    establishment_id UUID NOT NULL,
    status subscription_status_t NOT NULL DEFAULT 'PENDING',
    activated_at TIMESTAMPTZ,
    inactivated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_subscription_owner UNIQUE (id, establishment_id),
    CONSTRAINT fk_subscription_establishment FOREIGN KEY (establishment_id)
        REFERENCES establishment (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE establishment_subscription IS 'Vínculo contratual. Status ACTIVE não substitui a verificação do intervalo do ciclo pago.';

-- As condições copiadas do plano preservam a contratação quando o catálogo muda.
-- As FKs que incluem establishment_id também verificam a titularidade: IDs
-- existentes, mas pertencentes a estabelecimentos diferentes, não podem se combinar.
CREATE TABLE subscription_cycle (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL,
    establishment_id UUID NOT NULL,
    cycle_number INTEGER NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    anchor_day SMALLINT NOT NULL,
    anchor_local_time TIME NOT NULL,
    anchor_timezone VARCHAR(100) NOT NULL,
    plan_id UUID NOT NULL,
    plan_name VARCHAR(100) NOT NULL,
    plan_description TEXT,
    monthly_price DECIMAL(10,2) NOT NULL,
    volume_limit_liters DECIMAL(8,2) NOT NULL,
    collection_limit INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_cycle_number UNIQUE (subscription_id, cycle_number),
    CONSTRAINT uq_cycle_owner UNIQUE (id, establishment_id),
    -- A exclusão compara linhas e impede períodos sobrepostos para o mesmo dono.
    -- O intervalo [) admite ciclos consecutivos: o fim de um pode ser o início do outro.
    CONSTRAINT ex_cycle_no_overlap EXCLUDE USING gist
        (establishment_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&),
    CONSTRAINT fk_cycle_subscription_owner FOREIGN KEY (subscription_id, establishment_id)
        REFERENCES establishment_subscription (id, establishment_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_cycle_plan FOREIGN KEY (plan_id)
        REFERENCES subscription_plan (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE subscription_cycle IS 'Período pago [início,fim). Limites vigentes substituídos no upgrade. Sem contadores duplicados de consumo.';

CREATE TABLE billing_order (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    establishment_id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    purpose billing_purpose_t NOT NULL,
    benefit_key VARCHAR(150) NOT NULL,
    target_cycle_id UUID,
    previous_cycle_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_billing_order_benefit UNIQUE (establishment_id, benefit_key),
    CONSTRAINT uq_billing_order_owner UNIQUE (id, establishment_id, purpose),
    CONSTRAINT uq_billing_order_target UNIQUE (id, target_cycle_id),
    CONSTRAINT fk_order_subscription_owner FOREIGN KEY (subscription_id, establishment_id)
        REFERENCES establishment_subscription (id, establishment_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_order_target_cycle FOREIGN KEY (target_cycle_id, establishment_id)
        REFERENCES subscription_cycle (id, establishment_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_order_previous_cycle FOREIGN KEY (previous_cycle_id, establishment_id)
        REFERENCES subscription_cycle (id, establishment_id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE billing_order IS 'Identidade estável do benefício, reutilizada nas reemissões. Não é a cobrança do provedor.';

-- Reemitir uma cobrança mantém a ordem do benefício, mas cria outra tentativa.
-- Ao repetir a mesma tentativa após falha de comunicação, a integração deve
-- reutilizar sua idempotency_key; o DEFAULT só gera a chave no cadastro.
CREATE TABLE billing_charge (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    billing_order_id UUID NOT NULL,
    establishment_id UUID NOT NULL,
    purpose billing_purpose_t NOT NULL,
    target_cycle_id UUID,
    provider VARCHAR(100) NOT NULL,
    provider_charge_id VARCHAR(255),
    idempotency_key UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    status charge_status_t NOT NULL DEFAULT 'OPEN',
    plan_id UUID NOT NULL,
    plan_name VARCHAR(100) NOT NULL,
    plan_description TEXT,
    quoted_monthly_price DECIMAL(10,2) NOT NULL,
    quoted_volume_limit_liters DECIMAL(8,2) NOT NULL,
    quoted_collection_limit INTEGER NOT NULL,
    from_plan_id UUID,
    from_monthly_price DECIMAL(10,2),
    amount DECIMAL(10,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'BRL',
    expires_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_charge_provider UNIQUE (provider, provider_charge_id),
    CONSTRAINT uq_charge_order UNIQUE (id, billing_order_id, establishment_id),
    CONSTRAINT uq_charge_provider_identity UNIQUE (id, provider),
    CONSTRAINT fk_charge_order_owner FOREIGN KEY (billing_order_id, establishment_id, purpose)
        REFERENCES billing_order (id, establishment_id, purpose) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_charge_order_target FOREIGN KEY (billing_order_id, target_cycle_id)
        REFERENCES billing_order (id, target_cycle_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_charge_cycle_owner FOREIGN KEY (target_cycle_id, establishment_id)
        REFERENCES subscription_cycle (id, establishment_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_charge_plan FOREIGN KEY (plan_id)
        REFERENCES subscription_plan (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_charge_from_plan FOREIGN KEY (from_plan_id)
        REFERENCES subscription_plan (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE billing_charge IS 'Tentativa de cobrança com preço e limites congelados. CANCELLATION_PENDING ainda ocupa a vaga de cobrança aberta.';

CREATE TABLE payment (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    billing_charge_id UUID NOT NULL,
    billing_order_id UUID NOT NULL,
    establishment_id UUID NOT NULL,
    provider VARCHAR(100) NOT NULL,
    provider_payment_id VARCHAR(255) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'BRL',
    paid_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- O identificador externo é único dentro do provedor. Assim, notificações
    -- repetidas não permitem cadastrar duas vezes o mesmo pagamento real.
    CONSTRAINT uq_payment_provider UNIQUE (provider, provider_payment_id),
    CONSTRAINT uq_payment_order UNIQUE (id, billing_order_id, establishment_id),
    CONSTRAINT uq_payment_refund_reference UNIQUE (id, amount, provider),
    CONSTRAINT uq_payment_event_reference UNIQUE (id, provider, provider_payment_id),
    CONSTRAINT fk_payment_charge_order FOREIGN KEY (billing_charge_id, billing_order_id, establishment_id)
        REFERENCES billing_charge (id, billing_order_id, establishment_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_payment_charge_provider FOREIGN KEY (billing_charge_id, provider)
        REFERENCES billing_charge (id, provider) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE payment IS 'Pagamento real confirmado pelo backend. Mensagens repetidas não criam outro pagamento; valor recebido é fato.';

-- Os UNIQUEs independentes impedem aplicar um pagamento mais de uma vez
-- e conceder novamente o benefício da mesma ordem, mesmo com outra cobrança.
CREATE TABLE payment_application (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL UNIQUE,
    billing_order_id UUID NOT NULL UNIQUE,
    establishment_id UUID NOT NULL,
    purpose billing_purpose_t NOT NULL,
    cycle_id UUID NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_application_cycle UNIQUE (id, cycle_id, purpose),
    CONSTRAINT fk_application_payment_order FOREIGN KEY (payment_id, billing_order_id, establishment_id)
        REFERENCES payment (id, billing_order_id, establishment_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_application_order FOREIGN KEY (billing_order_id, establishment_id, purpose)
        REFERENCES billing_order (id, establishment_id, purpose) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_application_cycle_owner FOREIGN KEY (cycle_id, establishment_id)
        REFERENCES subscription_cycle (id, establishment_id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE payment_application IS 'Um benefício recebe no máximo um pagamento. Criar/aplicar ciclo ou upgrade na mesma transação.';

CREATE TABLE subscription_cycle_change (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id UUID NOT NULL,
    payment_application_id UUID NOT NULL UNIQUE,
    purpose billing_purpose_t GENERATED ALWAYS AS ('UPGRADE'::billing_purpose_t) STORED,
    from_plan_id UUID NOT NULL,
    to_plan_id UUID NOT NULL,
    from_monthly_price DECIMAL(10,2) NOT NULL,
    to_monthly_price DECIMAL(10,2) NOT NULL,
    from_volume_limit_liters DECIMAL(8,2) NOT NULL,
    to_volume_limit_liters DECIMAL(8,2) NOT NULL,
    from_collection_limit INTEGER NOT NULL,
    to_collection_limit INTEGER NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- A finalidade gerada UPGRADE exige uma aplicação de upgrade no mesmo ciclo;
    -- um pagamento de início ou renovação não pode justificar esta alteração.
    CONSTRAINT fk_cycle_change_application FOREIGN KEY (payment_application_id, cycle_id, purpose)
        REFERENCES payment_application (id, cycle_id, purpose) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_cycle_change_from_plan FOREIGN KEY (from_plan_id)
        REFERENCES subscription_plan (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_cycle_change_to_plan FOREIGN KEY (to_plan_id)
        REFERENCES subscription_plan (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE subscription_cycle_change IS 'Histórico de upgrade pago: antes/depois dos limites. Não muda datas nem soma franquias.';

CREATE TABLE payment_refund (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL UNIQUE,
    reason refund_reason_t NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    provider VARCHAR(100) NOT NULL,
    status refund_status_t NOT NULL DEFAULT 'REQUESTED',
    idempotency_key UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    provider_refund_id VARCHAR(255),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    last_error TEXT,
    CONSTRAINT uq_refund_provider UNIQUE (provider, provider_refund_id),
    -- A FK inclui valor e provedor para exigir o reembolso integral do pagamento
    -- referenciado, sem permitir outro valor ou outro provedor nesta solicitação.
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id, amount, provider)
        REFERENCES payment (id, amount, provider) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE payment_refund IS 'Reembolso integral único por pagamento nos dois casos aprovados. Solicitação não significa conclusão.';

CREATE TABLE payment_refund_attempt (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    provider_request_id VARCHAR(255),
    result_status refund_status_t,
    error_description TEXT,
    CONSTRAINT uq_refund_attempt UNIQUE (refund_id, attempt_number),
    CONSTRAINT fk_refund_attempt_refund FOREIGN KEY (refund_id)
        REFERENCES payment_refund (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE payment_refund_attempt IS 'Tentativas do mesmo reembolso, preservando uma chave idempotente no provedor. Não armazenar credenciais nas mensagens.';

CREATE TABLE payment_provider_event (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(100) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    provider_payment_id VARCHAR(255),
    payment_id UUID,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ,
    status processing_status_t NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error TEXT,
    -- A deduplicação é por evento do provedor, distinta da identidade do pagamento:
    -- vários eventos diferentes podem se referir ao mesmo pagamento.
    CONSTRAINT uq_provider_event UNIQUE (provider, provider_event_id),
    CONSTRAINT fk_provider_event_payment FOREIGN KEY (payment_id, provider, provider_payment_id)
        REFERENCES payment (id, provider, provider_payment_id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE payment_provider_event IS 'Evento verificado do provedor. Sem payload bruto, dados de cartão, credenciais ou URLs de pagamento.';

CREATE TABLE collection_request (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    estimated_volume_liters DECIMAL(8,2) NOT NULL,
    status request_status_t NOT NULL DEFAULT 'PENDING',
    observation TEXT,
    request_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    establishment_id UUID NOT NULL,
    subscription_cycle_id UUID NOT NULL,
    approved_by UUID,
    approved_at TIMESTAMPTZ,
    admin_type user_type_t GENERATED ALWAYS AS ('ADMIN'::user_type_t) STORED,
    scheduled_at TIMESTAMPTZ,
    -- O instante da ocorrência e o instante do seu registro são separados
    -- para distinguir o atendimento efetivo de informações lançadas depois.
    arrived_at TIMESTAMPTZ,
    arrival_recorded_at TIMESTAMPTZ,
    arrival_driver_id UUID,
    service_accepted_at TIMESTAMPTZ,
    service_acceptance_recorded_at TIMESTAMPTZ,
    service_accepted_by UUID,
    cancelled_at TIMESTAMPTZ,
    cancellation_recorded_at TIMESTAMPTZ,
    cancelled_by UUID,
    cancellation_initiative cancellation_initiative_t,
    cancellation_policy cancellation_policy_t,
    cancellation_reason TEXT,
    forfeited_volume_liters DECIMAL(8,2) NOT NULL DEFAULT 0,
    forfeited_collection_slots SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_request_owner_status UNIQUE (id, establishment_id, status),
    CONSTRAINT fk_request_cycle_owner FOREIGN KEY (subscription_cycle_id, establishment_id)
        REFERENCES subscription_cycle (id, establishment_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_request_admin FOREIGN KEY (approved_by, admin_type)
        REFERENCES users (id, user_type) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_request_cancelled_by FOREIGN KEY (cancelled_by)
        REFERENCES users (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_request_arrival_driver FOREIGN KEY (arrival_driver_id)
        REFERENCES driver (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_request_service_accepted_by FOREIGN KEY (service_accepted_by)
        REFERENCES establishment (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE collection_request IS 'Reserva estimativa + uma vaga desde PENDING no ciclo de origem. Após coleta, não permitir cancelamento.';

CREATE TABLE collection_schedule_history (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    collection_request_id UUID NOT NULL,
    previous_scheduled_at TIMESTAMPTZ,
    new_scheduled_at TIMESTAMPTZ NOT NULL,
    agreed_at TIMESTAMPTZ NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_by UUID NOT NULL,
    admin_type user_type_t GENERATED ALWAYS AS ('ADMIN'::user_type_t) STORED,
    reason TEXT NOT NULL,
    CONSTRAINT fk_schedule_request FOREIGN KEY (collection_request_id)
        REFERENCES collection_request (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_schedule_admin FOREIGN KEY (changed_by, admin_type)
        REFERENCES users (id, user_type) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE collection_schedule_history IS 'Agendamento inicial e reagendamentos acordados, com horários anterior/novo e autoria administrativa.';

CREATE TABLE collection (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    collected_volume_liters DECIMAL(8,2) NOT NULL,
    presented_volume_liters DECIMAL(8,2) NOT NULL,
    oil_condition oil_condition_t NOT NULL,
    has_compromising_occurrence BOOLEAN NOT NULL,
    result collection_result_t NOT NULL,
    points_earned BIGINT NOT NULL,
    observation TEXT,
    collection_date TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- A unicidade vale também para uma coleta anulada: a correção preserva
    -- a identidade da visita, sem liberar o pedido para um segundo registro.
    collection_request_id UUID NOT NULL UNIQUE,
    request_status request_status_t GENERATED ALWAYS AS ('APPROVED'::request_status_t) STORED,
    establishment_id UUID NOT NULL,
    processing_order BIGINT NOT NULL,
    driver_id UUID NOT NULL,
    record_status record_status_t NOT NULL DEFAULT 'RECORDED',
    revision INTEGER NOT NULL DEFAULT 1,
    corrected_at TIMESTAMPTZ,
    corrected_by UUID,
    correction_reason TEXT,
    admin_type user_type_t GENERATED ALWAYS AS ('ADMIN'::user_type_t) STORED,
    CONSTRAINT uq_collection_order UNIQUE (establishment_id, processing_order),
    CONSTRAINT uq_collection_owner UNIQUE (id, establishment_id),
    CONSTRAINT fk_collection_request_owner FOREIGN KEY (collection_request_id, establishment_id, request_status)
        REFERENCES collection_request (id, establishment_id, status) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_collection_driver FOREIGN KEY (driver_id)
        REFERENCES driver (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_collection_correction_admin FOREIGN KEY (corrected_by, admin_type)
        REFERENCES users (id, user_type) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE collection IS 'Visita B2B realizada, inclusive zero recolhido. A FK exige pedido aprovado e bloqueia seu cancelamento após a coleta. Resultado e pontos são derivados pelas rotinas.';

CREATE TABLE collection_failure_reason (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(150) NOT NULL,
    description TEXT,
    status active_status_t NOT NULL DEFAULT 'ACTIVE'
);

COMMENT ON TABLE collection_failure_reason IS 'Catálogo dos motivos de malsucesso, sem criar penalidades adicionais por motivo.';

CREATE TABLE collection_failure (
collection_id UUID NOT NULL,
    failure_reason_id UUID NOT NULL,
    PRIMARY KEY (collection_id, failure_reason_id),
    CONSTRAINT fk_failure_collection FOREIGN KEY (collection_id)
        REFERENCES collection (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_failure_reason FOREIGN KEY (failure_reason_id)
        REFERENCES collection_failure_reason (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE collection_failure IS 'Associação N:N sem repetição. Rotina mantém motivos coerentes com resultado e corrige com auditoria.';

CREATE TABLE delivery_pev (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    oil_volume_liters DECIMAL(8,2) NOT NULL,
    points_earned BIGINT NOT NULL,
    delivery_date TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    citizen_id UUID NOT NULL,
    pev_id UUID NOT NULL,
    validated_by UUID NOT NULL,
    record_status record_status_t NOT NULL DEFAULT 'RECORDED',
    revision INTEGER NOT NULL DEFAULT 1,
    corrected_at TIMESTAMPTZ,
    corrected_by UUID,
    correction_reason TEXT,
    admin_type user_type_t GENERATED ALWAYS AS ('ADMIN'::user_type_t) STORED,
    CONSTRAINT uq_delivery_owner UNIQUE (id, citizen_id),
    CONSTRAINT fk_delivery_citizen FOREIGN KEY (citizen_id)
        REFERENCES citizens (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    -- A FK preserva o vínculo mesmo se o PEV for inativado posteriormente.
    -- A aprovação do PEV e a legitimidade do validador no ato dependem da rotina.
    CONSTRAINT fk_delivery_pev FOREIGN KEY (pev_id)
        REFERENCES pev (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_validator FOREIGN KEY (validated_by)
        REFERENCES users (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_correction_admin FOREIGN KEY (corrected_by, admin_type)
        REFERENCES users (id, user_type) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE delivery_pev IS 'Somente volume B2C aceito positivo. Validador é o responsável do PEV aprovado; anulação é correção administrativa.';

CREATE TABLE point_calculation (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    collection_id UUID,
    delivery_pev_id UUID,
    revision INTEGER NOT NULL,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    points_total BIGINT NOT NULL,
    balance_before BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    successful_collections_count BIGINT,
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recalculated_by UUID,
    recalculation_reason TEXT,
    admin_type user_type_t GENERATED ALWAYS AS ('ADMIN'::user_type_t) STORED,
    CONSTRAINT uq_point_collection_revision UNIQUE (collection_id, revision),
    CONSTRAINT uq_point_delivery_revision UNIQUE (delivery_pev_id, revision),
    CONSTRAINT fk_point_calculation_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_point_calculation_collection FOREIGN KEY (collection_id, user_id)
        REFERENCES collection (id, establishment_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_point_calculation_delivery FOREIGN KEY (delivery_pev_id, user_id)
        REFERENCES delivery_pev (id, citizen_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_point_calculation_admin FOREIGN KEY (recalculated_by, admin_type)
        REFERENCES users (id, user_type) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE point_calculation IS 'Revisão de cálculo por evento. Piso zero aplicado em sequência, não ao somatório final; preservar revisões anteriores.';

-- Os componentes detalham o cálculo, mas a FK não garante que sua soma
-- coincida com points_total. A rotina deve manter cálculo, componentes e saldo
-- consistentes na mesma transação.
CREATE TABLE point_transaction (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    point_calculation_id UUID NOT NULL,
    component point_component_t NOT NULL,
    points BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_point_component UNIQUE (point_calculation_id, component),
    CONSTRAINT fk_point_transaction_calculation FOREIGN KEY (point_calculation_id)
        REFERENCES point_calculation (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE point_transaction IS 'Componentes nominais de uma revisão: volume, sucesso, recorrência ou penalidade única. Sem ajuste manual livre.';

CREATE TABLE certificate_level (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    description TEXT,
    required_liters DECIMAL(8,2) NOT NULL,
    badge_image_url VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE certificate_level IS 'Metas e nomes definitivos pendentes; não inserir níveis fictícios.';

CREATE TABLE certificate (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    certificate_code VARCHAR(64) NOT NULL UNIQUE,
    pdf_url VARCHAR(500),
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    certificate_level_id UUID NOT NULL,
    establishment_id UUID NOT NULL,
    status certificate_status_t NOT NULL DEFAULT 'ACTIVE',
    revoked_at TIMESTAMPTZ,
    reactivated_at TIMESTAMPTZ,
    status_reason TEXT,
    CONSTRAINT uq_certificate_establishment_level UNIQUE (establishment_id, certificate_level_id),
    CONSTRAINT fk_certificate_level FOREIGN KEY (certificate_level_id)
        REFERENCES certificate_level (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_certificate_establishment FOREIGN KEY (establishment_id)
        REFERENCES establishment (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

COMMENT ON TABLE certificate IS 'Uma concessão por estabelecimento/nível. Revogar e reativar o mesmo registro; histórico nos logs.';

COMMENT ON COLUMN establishment.is_pev IS
'Derivado do PEV APPROVED; sincronizar na mesma transação, sem edição independente.';
COMMENT ON COLUMN subscription_cycle.anchor_day IS
'Dia original do calendário mensal: 31 pode ajustar para fevereiro sem virar 28 nos meses seguintes.';
COMMENT ON COLUMN subscription_cycle.anchor_timezone IS
'Fuso usado no calendário mensal; TIMESTAMPTZ preserva o instante, não o nome do fuso.';
COMMENT ON COLUMN billing_order.benefit_key IS
'Identidade estável do benefício; reutilizar nas reemissões da cobrança equivalente.';
COMMENT ON COLUMN collection_request.arrival_recorded_at IS
'Momento registrado pelo backend; informar depois não penaliza retroativamente um cancelamento gratuito.';
COMMENT ON COLUMN collection_request.service_accepted_at IS
'Aceite do estabelecimento para início do atendimento; após chegada atrasada, encerra a opção de cancelar gratuitamente.';
COMMENT ON COLUMN collection_request.cancelled_at IS
'Instante efetivo da decisão de cancelamento, validado pelo backend; não é horário livre informado pelo cliente.';
COMMENT ON COLUMN collection_request.forfeited_volume_liters IS
'Franquia perdida por cancelamento tardio; não representa óleo recolhido, pontos ou volume ambiental.';
COMMENT ON COLUMN collection.processing_order IS
'Ordem fixa por estabelecimento para reconstruir pontos e piso zero, independente da data física da visita.';
COMMENT ON COLUMN point_calculation.revision IS
'Revisão do cálculo, inclusive por correção de evento anterior; não precisa coincidir com a revisão da coleta.';


-- ============================================================================
-- AUDITORIA TIPADA — NÃO É PREENCHIDA AUTOMATICAMENTE SEM AS TRIGGERS
-- LIKE copia colunas e NOT NULL; não copia PKs, FKs, UNIQUEs, CHECKs ou índices.
-- Colunas geradas da origem tornam-se valores normais no snapshot do log.
-- UPDATE: gravar BEFORE e AFTER com o mesmo audit_event_id.
-- INSERT: somente AFTER. DELETE autorizado: somente BEFORE.
-- Não excluir historicamente para simular correção/anulação.
-- ============================================================================

-- A estrutura copiada por LIKE é a existente na criação; mudanças futuras na
-- tabela de origem exigem revisar o log. Os IDs do retrato não recebem as FKs
-- da origem, permitindo preservar o histórico independentemente dela.
CREATE TABLE users_log (
    LIKE users,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_users_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_users_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
ALTER TABLE users_log DROP COLUMN password_hash;
COMMENT ON TABLE users_log IS 'Histórico de users, preenchido pelas rotinas de auditoria.';

-- Nos logs abaixo, PUBLIC representa todos os papéis, não o schema public.
-- O REVOKE remove concessões a esse grupo; privilégios próprios do dono ou
-- concedidos diretamente a outros papéis exigem controle separado.
REVOKE ALL ON users_log FROM PUBLIC;

CREATE TABLE addresses_log (
    LIKE addresses,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_addresses_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_addresses_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE addresses_log IS 'Histórico de addresses, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON addresses_log FROM PUBLIC;

CREATE TABLE user_qr_code_log (
    LIKE user_qr_code,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_user_qr_code_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_qr_code_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
ALTER TABLE user_qr_code_log DROP COLUMN qr_token;
COMMENT ON TABLE user_qr_code_log IS 'Histórico de user_qr_code, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON user_qr_code_log FROM PUBLIC;

CREATE TABLE citizens_log (
    LIKE citizens,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_citizens_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_citizens_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
ALTER TABLE citizens_log DROP COLUMN qr_token;
COMMENT ON TABLE citizens_log IS 'Histórico de citizens, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON citizens_log FROM PUBLIC;

CREATE TABLE establishment_log (
    LIKE establishment,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_establishment_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_establishment_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
ALTER TABLE establishment_log DROP COLUMN qr_token;
COMMENT ON TABLE establishment_log IS 'Histórico de establishment, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON establishment_log FROM PUBLIC;

CREATE TABLE pev_log (
    LIKE pev,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_pev_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pev_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE pev_log IS 'Histórico de pev, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON pev_log FROM PUBLIC;

CREATE TABLE subscription_plan_log (
    LIKE subscription_plan,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_subscription_plan_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_plan_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE subscription_plan_log IS 'Histórico de subscription_plan, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON subscription_plan_log FROM PUBLIC;

CREATE TABLE establishment_subscription_log (
    LIKE establishment_subscription,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_establishment_subscription_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_establishment_subscription_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE establishment_subscription_log IS 'Histórico de establishment_subscription, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON establishment_subscription_log FROM PUBLIC;

CREATE TABLE subscription_cycle_log (
    LIKE subscription_cycle,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_subscription_cycle_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_cycle_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE subscription_cycle_log IS 'Histórico de subscription_cycle, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON subscription_cycle_log FROM PUBLIC;

CREATE TABLE billing_order_log (
    LIKE billing_order,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_billing_order_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_billing_order_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE billing_order_log IS 'Histórico de billing_order, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON billing_order_log FROM PUBLIC;

CREATE TABLE billing_charge_log (
    LIKE billing_charge,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_billing_charge_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_billing_charge_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE billing_charge_log IS 'Histórico de billing_charge, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON billing_charge_log FROM PUBLIC;

CREATE TABLE payment_log (
    LIKE payment,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_payment_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE payment_log IS 'Histórico de payment, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON payment_log FROM PUBLIC;

CREATE TABLE payment_application_log (
    LIKE payment_application,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_payment_application_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_application_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE payment_application_log IS 'Histórico de payment_application, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON payment_application_log FROM PUBLIC;

CREATE TABLE payment_refund_log (
    LIKE payment_refund,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_payment_refund_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_refund_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE payment_refund_log IS 'Histórico de payment_refund, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON payment_refund_log FROM PUBLIC;

CREATE TABLE collection_request_log (
    LIKE collection_request,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_collection_request_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collection_request_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE collection_request_log IS 'Histórico de collection_request, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON collection_request_log FROM PUBLIC;

CREATE TABLE collection_log (
    LIKE collection,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_collection_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collection_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE collection_log IS 'Histórico de collection, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON collection_log FROM PUBLIC;

CREATE TABLE collection_failure_reason_log (
    LIKE collection_failure_reason,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_collection_failure_reason_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collection_failure_reason_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE collection_failure_reason_log IS 'Histórico de collection_failure_reason, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON collection_failure_reason_log FROM PUBLIC;

CREATE TABLE collection_failure_log (
    LIKE collection_failure,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_collection_failure_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_collection_failure_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE collection_failure_log IS 'Histórico de collection_failure, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON collection_failure_log FROM PUBLIC;

CREATE TABLE delivery_pev_log (
    LIKE delivery_pev,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_delivery_pev_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_pev_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE delivery_pev_log IS 'Histórico de delivery_pev, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON delivery_pev_log FROM PUBLIC;

CREATE TABLE point_calculation_log (
    LIKE point_calculation,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_point_calculation_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_point_calculation_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE point_calculation_log IS 'Histórico de point_calculation, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON point_calculation_log FROM PUBLIC;

CREATE TABLE certificate_level_log (
    LIKE certificate_level,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_certificate_level_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_certificate_level_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE certificate_level_log IS 'Histórico de certificate_level, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON certificate_level_log FROM PUBLIC;

CREATE TABLE certificate_log (
    LIKE certificate,
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_event_id UUID NOT NULL,
    snapshot_kind audit_snapshot_t NOT NULL,
    operation operation_status_t NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL,
    performed_by UUID,
    actor_kind audit_actor_t NOT NULL,
    operational_driver_id UUID,
    audit_reason TEXT,
    changed_columns TEXT[] NOT NULL,
    CONSTRAINT fk_certificate_log_user FOREIGN KEY (performed_by)
        REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_certificate_log_driver FOREIGN KEY (operational_driver_id)
        REFERENCES driver(id) ON DELETE RESTRICT
);
COMMENT ON TABLE certificate_log IS 'Histórico de certificate, preenchido pelas rotinas de auditoria.';

REVOKE ALL ON certificate_log FROM PUBLIC;

-- Unicidades condicionais são garantias estruturais, não otimizações opcionais.

-- Limita o vínculo ACTIVE a um por estabelecimento; não verifica sozinho
-- se há um ciclo pago vigente na data do atendimento.
CREATE UNIQUE INDEX uq_subscription_active
    ON establishment_subscription (establishment_id)
    WHERE status = 'ACTIVE';

-- Início da assinatura e renovação de um ciclo têm uma única ordem cada.
-- Reemissões usam novas cobranças dessa ordem, sem duplicar o benefício.
CREATE UNIQUE INDEX uq_order_initial_subscription
    ON billing_order (subscription_id)
    WHERE purpose = 'INITIAL';

CREATE UNIQUE INDEX uq_order_renewal_previous_cycle
    ON billing_order (previous_cycle_id)
    WHERE purpose = 'RENEWAL';

-- As duas regras abaixo limitam cobranças abertas por estabelecimento (período)
-- e por ciclo (upgrade). Cancelamento pendente ainda ocupa a vaga para evitar
-- abrir outra cobrança antes da confirmação do cancelamento.
CREATE UNIQUE INDEX uq_charge_open_period
    ON billing_charge (establishment_id)
    WHERE purpose IN ('INITIAL','RENEWAL') AND status IN ('OPEN','CANCELLATION_PENDING');

CREATE UNIQUE INDEX uq_charge_open_upgrade
    ON billing_charge (target_cycle_id)
    WHERE purpose = 'UPGRADE' AND status IN ('OPEN','CANCELLATION_PENDING');

-- Cada ciclo recebe no máximo uma aplicação de início ou renovação.
-- Upgrades ficam fora deste filtro, permitindo alterações pagas posteriores.
CREATE UNIQUE INDEX uq_application_initial_cycle
    ON payment_application (cycle_id)
    WHERE purpose IN ('INITIAL','RENEWAL');

-- As duas regras abaixo permitem várias revisões históricas, mas no máximo
-- uma vigente por coleta ou entrega. A rotina deve garantir que exista a
-- revisão vigente necessária; estes índices apenas impedem duplicidade.
CREATE UNIQUE INDEX uq_point_collection_current
    ON point_calculation (collection_id)
    WHERE is_current AND collection_id IS NOT NULL;

CREATE UNIQUE INDEX uq_point_delivery_current
    ON point_calculation (delivery_pev_id)
    WHERE is_current AND delivery_pev_id IS NOT NULL;

-- Nas unicidades dos logs abaixo, evento + identidade + lado do retrato
-- impedem repetir BEFORE ou AFTER para o mesmo registro no mesmo evento.
-- A presença do par necessário em um UPDATE continua a cargo das triggers.
ALTER TABLE users_log
    ADD CONSTRAINT uq_users_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE addresses_log
    ADD CONSTRAINT uq_addresses_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE user_qr_code_log
    ADD CONSTRAINT uq_user_qr_code_log_snapshot UNIQUE (audit_event_id, user_id, snapshot_kind);

ALTER TABLE citizens_log
    ADD CONSTRAINT uq_citizens_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE establishment_log
    ADD CONSTRAINT uq_establishment_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE pev_log
    ADD CONSTRAINT uq_pev_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE subscription_plan_log
    ADD CONSTRAINT uq_subscription_plan_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE establishment_subscription_log
    ADD CONSTRAINT uq_establishment_subscription_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE subscription_cycle_log
    ADD CONSTRAINT uq_subscription_cycle_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE billing_order_log
    ADD CONSTRAINT uq_billing_order_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE billing_charge_log
    ADD CONSTRAINT uq_billing_charge_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE payment_log
    ADD CONSTRAINT uq_payment_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE payment_application_log
    ADD CONSTRAINT uq_payment_application_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE payment_refund_log
    ADD CONSTRAINT uq_payment_refund_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE collection_request_log
    ADD CONSTRAINT uq_collection_request_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE collection_log
    ADD CONSTRAINT uq_collection_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE collection_failure_reason_log
    ADD CONSTRAINT uq_collection_failure_reason_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE collection_failure_log
    ADD CONSTRAINT uq_collection_failure_log_snapshot UNIQUE (audit_event_id, collection_id, failure_reason_id, snapshot_kind);

ALTER TABLE delivery_pev_log
    ADD CONSTRAINT uq_delivery_pev_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE point_calculation_log
    ADD CONSTRAINT uq_point_calculation_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE certificate_level_log
    ADD CONSTRAINT uq_certificate_level_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);

ALTER TABLE certificate_log
    ADD CONSTRAINT uq_certificate_log_snapshot UNIQUE (audit_event_id, id, snapshot_kind);