/*
===============================================================================
PROJETO.............: ÓLEO AMIGO — OLIUS
BANCO DE DADOS......: PostgreSQL 16.15 (alvo)
SCRIPT..............: 03 - Índices de Desempenho
===============================================================================
*/

-- PKs, UNIQUEs e exclusão já criam índices no arquivo 01.
-- Não há índice de ranking; Redis ZSET é a projeção do saldo persistente.
-- Não duplicar UNIQUE(user_id,telephone), UNIQUE(establishment_id,certificate_level_id), etc.
-- Banco novo: CREATE INDEX comum. CONCURRENTLY só se aplica a migração planejada.

-- Filtra estabelecimentos por categoria e apoia o relacionamento pela FK type_id.
-- A PK do catálogo não indexa automaticamente a coluna da tabela dependente.
CREATE INDEX idx_establishment_type
    ON establishment (type_id);

-- Histórico contratual por estabelecimento, começando pelos registros recentes.
-- O índice parcial de assinatura ACTIVE no 01 não cobre assinaturas antigas.
CREATE INDEX idx_subscription_establishment
    ON establishment_subscription (establishment_id, created_at DESC);

-- Busca ciclos de um estabelecimento por início do período. A ordem B-tree
-- atende histórico e intervalos de datas; a exclusão GiST do 01 protege sobreposição,
-- mas não substitui essa ordenação. Vigência ainda exige conferir ends_at.
CREATE INDEX idx_cycle_establishment_start
    ON subscription_cycle (establishment_id, starts_at DESC);

-- Localiza benefícios da assinatura em ordem de criação, apoiando histórico
-- comercial e reconciliação. A FK sozinha não cria esse caminho de consulta.
CREATE INDEX idx_order_subscription
    ON billing_order (subscription_id, created_at DESC);

-- Recupera as cobranças e reemissões de um benefício, mais recentes primeiro.
-- O índice começa pelo vínculo ao benefício, não pelo identificador do provedor.
CREATE INDEX idx_charge_order
    ON billing_charge (billing_order_id, created_at DESC);

-- Encontra todos os pagamentos de uma cobrança, inclusive recebimentos excedentes.
-- Não é UNIQUE: pagamentos reais distintos para a mesma cobrança precisam existir.
CREATE INDEX idx_payment_charge
    ON payment (billing_charge_id);

-- Os dois índices seguintes percorrem a aplicação de benefícios e os upgrades
-- de um ciclo por data. As unicidades do 01 protegem duplicidade, mas não substituem
-- a consulta cronológica de todas as operações daquele ciclo.
CREATE INDEX idx_application_cycle
    ON payment_application (cycle_id, applied_at);

CREATE INDEX idx_cycle_change_cycle
    ON subscription_cycle_change (cycle_id, applied_at);

-- Histórico de pedidos de uma unidade, com os mais recentes primeiro.
-- Atende listagens por estabelecimento sem varrer solicitações de outras unidades.
CREATE INDEX idx_request_establishment
    ON collection_request (establishment_id, request_at DESC);

-- Agrupa pedidos pelo ciclo de origem e permite restringir seus estados no cálculo
-- de reservas e perdas. O índice não calcula disponibilidade nem impede disputa
-- concorrente de franquia; a rotina agrega os fatos e controla as transações.
CREATE INDEX idx_request_cycle
    ON collection_request (subscription_cycle_id, status);

-- Consulta alterações de horário de um pedido, mais recentes primeiro,
-- para rastrear acordos e explicar os limites de cancelamento aplicados.
CREATE INDEX idx_schedule_request
    ON collection_schedule_history (collection_request_id, changed_at DESC);

-- Os dois índices seguintes oferecem históricos físicos por motorista e por
-- estabelecimento, com filtro de período e ordenação por collection_date.
-- A ordem fixa de pontuação (processing_order) atende outra finalidade.
CREATE INDEX idx_collection_driver_date
    ON collection (driver_id, collection_date DESC);

CREATE INDEX idx_collection_establishment_date
    ON collection (establishment_id, collection_date DESC);

-- A PK (collection_id, failure_reason_id) atende motivos de uma coleta.
-- Este índice oferece o caminho inverso: coletas associadas a determinado motivo.
-- Ao contar coletas com vários motivos, a consulta deve evitar dupla contagem.
CREATE INDEX idx_failure_reason
    ON collection_failure (failure_reason_id);

-- Dois acessos complementares às entregas: histórico do cidadão e movimento
-- do PEV, filtrados e ordenados pela data física. Não exigem leitura dos logs.
CREATE INDEX idx_delivery_pev_citizen_date
    ON delivery_pev (citizen_id, delivery_date DESC);

CREATE INDEX idx_delivery_pev_pev_date
    ON delivery_pev (pev_id, delivery_date DESC);

-- Localiza cálculos e reconstruções do usuário pela data de processamento.
-- Inclui revisões antigas; consultar o estado vigente ainda exige filtrar is_current.
-- Não substitui processing_order na reconstrução B2B nem serve como ranking.
CREATE INDEX idx_point_calculation_user
    ON point_calculation (user_id, calculated_at DESC);

-- Consulta concessões de um nível. UNIQUE(establishment_id, certificate_level_id)
-- cobre a direção estabelecimento → níveis, mas não tem o nível como primeira chave.
CREATE INDEX idx_certificate_level
    ON certificate (certificate_level_id);

-- FILAS DE APROVAÇÃO — os dois índices seguintes contêm apenas PENDING.
-- As consultas devem incluir esse filtro; data e id permitem ordenar e paginar
-- a fila. Não se indexa status como chave, pois seu valor já é fixo no predicado.
CREATE INDEX idx_pev_status_pending
    ON pev (created_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_collection_request_status_pending
    ON collection_request (request_at, id)
    WHERE status = 'PENDING';

-- Ordena solicitações APPROVED por horário para a agenda operacional.
-- Pedidos com coleta podem continuar APPROVED: excluir os já atendidos exige
-- considerar a coleta na consulta, pois o índice não faz essa distinção.
CREATE INDEX idx_request_schedule
    ON collection_request (scheduled_at, id)
    WHERE status = 'APPROVED';

-- REPROCESSAMENTO — os dois índices seguintes deixam de fora os estados finais
-- e apoiam a seleção por próxima tentativa, seguida da data de entrada.
-- A consulta deve incluir estados compatíveis com o predicado. PROCESSING não
-- significa falha por si só; prazos, concorrência e recuperação são das rotinas.
CREATE INDEX idx_refund_pending
    ON payment_refund (next_attempt_at, requested_at)
    WHERE status IN ('REQUESTED','PROCESSING','FAILED');

CREATE INDEX idx_provider_event_pending
    ON payment_provider_event (next_attempt_at, received_at)
    WHERE status IN ('PENDING','PROCESSING','FAILED');

-- HISTÓRICO — a estratégia abaixo começa pela chave do registro de origem
-- e ordena suas alterações por performed_at DESC. log_id identifica o snapshot,
-- mas não agrupa o histórico da entidade. O log de QR usa user_id como origem.
-- Esses índices não garantem unicidade nem ordenação total em empates de horário;
-- a unicidade dos snapshots já está no 01. Índices adicionais por autor ou data
-- global dependem de consultas medidas, para não ampliar toda escrita de auditoria.
CREATE INDEX idx_users_log_history
    ON users_log (id, performed_at DESC);

CREATE INDEX idx_addresses_log_history
    ON addresses_log (id, performed_at DESC);

CREATE INDEX idx_user_qr_code_log_history
    ON user_qr_code_log (user_id, performed_at DESC);

CREATE INDEX idx_citizens_log_history
    ON citizens_log (id, performed_at DESC);

CREATE INDEX idx_establishment_log_history
    ON establishment_log (id, performed_at DESC);

CREATE INDEX idx_pev_log_history
    ON pev_log (id, performed_at DESC);

CREATE INDEX idx_subscription_plan_log_history
    ON subscription_plan_log (id, performed_at DESC);

CREATE INDEX idx_establishment_subscription_log_history
    ON establishment_subscription_log (id, performed_at DESC);

CREATE INDEX idx_subscription_cycle_log_history
    ON subscription_cycle_log (id, performed_at DESC);

CREATE INDEX idx_billing_order_log_history
    ON billing_order_log (id, performed_at DESC);

CREATE INDEX idx_billing_charge_log_history
    ON billing_charge_log (id, performed_at DESC);

CREATE INDEX idx_payment_log_history
    ON payment_log (id, performed_at DESC);

CREATE INDEX idx_payment_application_log_history
    ON payment_application_log (id, performed_at DESC);

CREATE INDEX idx_payment_refund_log_history
    ON payment_refund_log (id, performed_at DESC);

CREATE INDEX idx_collection_request_log_history
    ON collection_request_log (id, performed_at DESC);

CREATE INDEX idx_collection_log_history
    ON collection_log (id, performed_at DESC);

CREATE INDEX idx_collection_failure_reason_log_history
    ON collection_failure_reason_log (id, performed_at DESC);

-- Aqui a origem é o vínculo completo coleta + motivo. Com ambos filtrados,
-- a data ordena as alterações desse vínculo; filtrar somente collection_id
-- não garante ordem temporal global entre motivos diferentes.
CREATE INDEX idx_collection_failure_log_history
    ON collection_failure_log (collection_id, failure_reason_id, performed_at DESC);

CREATE INDEX idx_delivery_pev_log_history
    ON delivery_pev_log (id, performed_at DESC);

CREATE INDEX idx_point_calculation_log_history
    ON point_calculation_log (id, performed_at DESC);

CREATE INDEX idx_certificate_level_log_history
    ON certificate_level_log (id, performed_at DESC);

CREATE INDEX idx_certificate_log_history
    ON certificate_log (id, performed_at DESC);