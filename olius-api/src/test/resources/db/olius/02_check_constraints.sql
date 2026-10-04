/*
===============================================================================
PROJETO.............: ÓLEO AMIGO — OLIUS
BANCO DE DADOS......: PostgreSQL 16.15 (alvo)
SCRIPT..............: 02 - CHECK Constraints
===============================================================================
*/

-- Identificação dos cadastros e catálogos: NOT NULL não impede texto vazio
-- ou composto apenas de espaços. As seis validações abaixo fecham essa lacuna.
ALTER TABLE users
    ADD CONSTRAINT ck_users_name_not_blank
    CHECK (BTRIM(name) <> '');

ALTER TABLE establishment_type
    ADD CONSTRAINT ck_establishment_type_name_not_blank
    CHECK (BTRIM(name) <> '');

ALTER TABLE driver
    ADD CONSTRAINT ck_driver_name_not_blank
    CHECK (BTRIM(name) <> '');

ALTER TABLE subscription_plan
    ADD CONSTRAINT ck_subscription_plan_name_not_blank
    CHECK (BTRIM(name) <> '');

ALTER TABLE certificate_level
    ADD CONSTRAINT ck_certificate_level_name_not_blank
    CHECK (BTRIM(name) <> '');

ALTER TABLE collection_failure_reason
    ADD CONSTRAINT ck_collection_failure_reason_name_not_blank
    CHECK (BTRIM(name) <> '');

-- Verificação mínima para evitar e-mail vazio ou sem parte anterior ao @.
-- Não valida o formato completo nem comprova a existência ou posse do endereço.
ALTER TABLE users
    ADD CONSTRAINT ck_users_email
    CHECK (BTRIM(email) <> '' AND POSITION('@' IN email) > 1);

-- Impede cadastro com hash vazio. Algoritmo, custo e verificação de senha
-- pertencem à autenticação; esta regra não avalia a qualidade do hash.
ALTER TABLE users
    ADD CONSTRAINT ck_users_password_not_blank
    CHECK (BTRIM(password_hash) <> '');

-- Limita o tamanho armazenado do telefone; não valida dígitos, DDD ou existência.
-- O compartilhamento entre usuários é permitido pela unicidade por usuário no 01.
ALTER TABLE telephone
    ADD CONSTRAINT ck_telephone_length
    CHECK (LENGTH(telephone) BETWEEN 10 AND 15);

-- QR pertence apenas aos perfis que apresentam óleo, nunca ao ADMIN.
-- O token não pode ser vazio; sua unicidade global e vínculo ao dono ficam no 01.
ALTER TABLE user_qr_code
    ADD CONSTRAINT ck_user_qr_code_type
    CHECK (user_type IN ('CITIZENS', 'ESTABLISHMENT'));

ALTER TABLE user_qr_code
    ADD CONSTRAINT ck_user_qr_code_token
    CHECK (BTRIM(qr_token) <> '');

-- Nos dois perfis abaixo, o comprimento dos documentos não valida dígitos
-- verificadores ou autenticidade. O saldo persistido não pode ficar negativo,
-- embora o histórico B2B preserve a movimentação nominal de uma penalidade.
ALTER TABLE citizens
    ADD CONSTRAINT ck_citizens_cpf
    CHECK (LENGTH(cpf) = 11);

ALTER TABLE citizens
    ADD CONSTRAINT ck_citizens_points
    CHECK (points >= 0);

ALTER TABLE establishment
    ADD CONSTRAINT ck_establishment_cnpj
    CHECK (LENGTH(cnpj) = 14);

ALTER TABLE establishment
    ADD CONSTRAINT ck_establishment_points
    CHECK (points >= 0);

-- Mantém o tamanho esperado dos documentos do motorista, sem validar
-- autenticidade e sem transformar seu cadastro operacional em usuário de login.
ALTER TABLE driver
    ADD CONSTRAINT ck_driver_documents
    CHECK (LENGTH(cpf) = 11 AND LENGTH(cnh) = 11);

-- Mantém a cronologia do cadastro. Instantes infinitos não representam
-- eventos operacionais; atualizar updated_at continua sendo tarefa das rotinas.
ALTER TABLE driver
    ADD CONSTRAINT ck_driver_dates
    CHECK (isfinite(registration_date) AND isfinite(updated_at) AND updated_at >= registration_date);

-- Formato de armazenamento de UF e CEP. Esses padrões não verificam
-- se a UF existe nem se o CEP corresponde ao endereço informado.
ALTER TABLE addresses
    ADD CONSTRAINT ck_addresses_state
    CHECK (state ~ '^[A-Z]{2}$');

ALTER TABLE addresses
    ADD CONSTRAINT ck_addresses_cep
    CHECK (cep ~ '^[0-9]{8}$');

-- Os quatro componentes obrigatórios do endereço não podem conter só espaços.
-- O número permanece textual, permitindo identificações não numéricas.
ALTER TABLE addresses
    ADD CONSTRAINT ck_addresses_city
    CHECK (BTRIM(city) <> '');

ALTER TABLE addresses
    ADD CONSTRAINT ck_addresses_neighborhood
    CHECK (BTRIM(neighborhood) <> '');

ALTER TABLE addresses
    ADD CONSTRAINT ck_addresses_street
    CHECK (BTRIM(street) <> '');

ALTER TABLE addresses
    ADD CONSTRAINT ck_addresses_number
    CHECK (BTRIM(number) <> '');

-- Coordenadas são opcionais, mas, quando informadas, respeitam os limites
-- geográficos. Não se exige aqui que latitude e longitude venham em conjunto.
ALTER TABLE addresses
    ADD CONSTRAINT ck_addresses_latitude
    CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90);

ALTER TABLE addresses
    ADD CONSTRAINT ck_addresses_longitude
    CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180);

-- Proprietário exclusivo: um PEV pertence a cidadão OU estabelecimento.
-- Rejeita tanto ausência de responsável quanto preenchimento dos dois vínculos.
ALTER TABLE pev
    ADD CONSTRAINT ck_pev_single_owner
    CHECK ((citizen_id IS NOT NULL) <> (establishment_id IS NOT NULL));

-- Aprovação exige autor e data juntos, a partir do cadastro. Esses dados
-- podem permanecer após inativação para preservar a aprovação histórica.
-- A condição de administrador do aprovador é protegida pela FK do arquivo 01.
ALTER TABLE pev
    ADD CONSTRAINT ck_pev_approval
    CHECK ((approved_at IS NULL) = (approved_by IS NULL)
        AND (status <> 'APPROVED' OR approved_at IS NOT NULL)
        AND (approved_at IS NULL OR (isfinite(approved_at) AND approved_at >= created_at)));

-- Catálogo e ciclo contratado exigem preço e franquias positivos.
-- NaN é excluído explicitamente porque não representa dinheiro ou litros.
-- As seis regras validam os limites, não o consumo: uma coleta real que ultrapasse
-- a franquia deve ser registrada; as rotinas bloqueiam novas reservas.
ALTER TABLE subscription_plan
    ADD CONSTRAINT ck_subscription_plan_price
    CHECK (monthly_price > 0 AND monthly_price <> 'NaN'::numeric);

ALTER TABLE subscription_plan
    ADD CONSTRAINT ck_subscription_plan_volume
    CHECK (volume_limit_liters > 0 AND volume_limit_liters <> 'NaN'::numeric);

ALTER TABLE subscription_plan
    ADD CONSTRAINT ck_subscription_plan_slots
    CHECK (collection_limit > 0);

ALTER TABLE subscription_cycle
    ADD CONSTRAINT ck_subscription_cycle_price
    CHECK (monthly_price > 0 AND monthly_price <> 'NaN'::numeric);

ALTER TABLE subscription_cycle
    ADD CONSTRAINT ck_subscription_cycle_volume
    CHECK (volume_limit_liters > 0 AND volume_limit_liters <> 'NaN'::numeric);

ALTER TABLE subscription_cycle
    ADD CONSTRAINT ck_subscription_cycle_slots
    CHECK (collection_limit > 0);

-- Exige os marcos correspondentes a ativação/inativação e sua cronologia.
-- ACTIVE sozinho não comprova pagamento nem garante que exista ciclo vigente.
ALTER TABLE establishment_subscription
    ADD CONSTRAINT ck_establishment_subscription_activation
    CHECK ((status <> 'ACTIVE' OR activated_at IS NOT NULL)
        AND (status <> 'INACTIVE' OR inactivated_at IS NOT NULL)
        AND (activated_at IS NULL OR (isfinite(activated_at) AND activated_at >= created_at))
        AND (inactivated_at IS NULL OR (isfinite(inactivated_at) AND inactivated_at >= created_at)));

-- Garante um intervalo finito e não vazio. Sobreposição entre ciclos é
-- protegida por EXCLUDE no 01; o cálculo do mês civil pertence às rotinas.
ALTER TABLE subscription_cycle
    ADD CONSTRAINT ck_subscription_cycle_period
    CHECK (isfinite(starts_at) AND isfinite(ends_at) AND ends_at > starts_at);

-- Preserva parâmetros do calendário mensal, inclusive o dia original 31.
-- Evita 24:00 como horário de referência; o nome do fuso é apenas não vazio aqui.
-- A rotina valida o fuso e ajusta meses curtos sem perder o dia original.
ALTER TABLE subscription_cycle
    ADD CONSTRAINT ck_subscription_cycle_anchor
    CHECK (anchor_day BETWEEN 1 AND 31 AND BTRIM(anchor_timezone) <> '' AND anchor_local_time < TIME '24:00');

-- Numeração de períodos começa em um. A unicidade por assinatura está no 01;
-- esta regra não exige sequência sem lacunas nem atribui o próximo número.
ALTER TABLE subscription_cycle
    ADD CONSTRAINT ck_subscription_cycle_number
    CHECK (cycle_number > 0);

-- A chave identifica o benefício para reemissões idempotentes. Não pode ser
-- vazia; sua geração estável e reutilização correta dependem do backend.
ALTER TABLE billing_order
    ADD CONSTRAINT ck_billing_order_benefit_key
    CHECK (BTRIM(benefit_key) <> '');

-- Distingue contratação, renovação após um ciclo e upgrade de um ciclo alvo.
-- Impede que a mesma finalidade carregue referências de fluxos incompatíveis.
ALTER TABLE billing_order
    ADD CONSTRAINT ck_billing_order_purpose
    CHECK ((purpose = 'INITIAL' AND target_cycle_id IS NULL AND previous_cycle_id IS NULL)
        OR (purpose = 'RENEWAL' AND target_cycle_id IS NULL AND previous_cycle_id IS NOT NULL)
        OR (purpose = 'UPGRADE' AND target_cycle_id IS NOT NULL AND previous_cycle_id IS NULL));

-- A cotação preservada precisa representar preço, valor cobrado e franquias
-- utilizáveis. Rejeita NaN; não consulta o catálogo nem reescreve preço histórico.
ALTER TABLE billing_charge
    ADD CONSTRAINT ck_billing_charge_quote
    CHECK (quoted_monthly_price > 0 AND quoted_monthly_price <> 'NaN'::numeric
        AND quoted_volume_limit_liters > 0 AND quoted_volume_limit_liters <> 'NaN'::numeric
        AND quoted_collection_limit > 0 AND amount > 0 AND amount <> 'NaN'::numeric);

-- Contratação/renovação cobram a mensalidade cotada. Upgrade exige origem,
-- destino e ciclo, cobrando a diferença integral positiva, sem pró-rata.
-- A compatibilidade com o plano vigente e o prazo do ciclo exige as rotinas.
ALTER TABLE billing_charge
    ADD CONSTRAINT ck_billing_charge_purpose
    CHECK ((purpose IN ('INITIAL','RENEWAL') AND target_cycle_id IS NULL
            AND from_plan_id IS NULL AND from_monthly_price IS NULL AND amount = quoted_monthly_price)
        OR (purpose = 'UPGRADE' AND target_cycle_id IS NOT NULL
            AND from_plan_id IS NOT NULL AND from_monthly_price IS NOT NULL
            AND from_monthly_price > 0 AND from_monthly_price <> 'NaN'::numeric
            AND from_plan_id <> plan_id AND amount = quoted_monthly_price - from_monthly_price));

-- Expiração deve suceder a criação. CANCELLATION_PENDING ainda está aberta:
-- pedir cancelamento não equivale a encerrá-lo no provedor.
-- Estados encerrados exigem data; a passagem de estado não é automática.
ALTER TABLE billing_charge
    ADD CONSTRAINT ck_billing_charge_dates
    CHECK (isfinite(expires_at) AND expires_at > created_at
        AND ((status IN ('OPEN','CANCELLATION_PENDING') AND closed_at IS NULL)
             OR (status IN ('PAID','CANCELLED','EXPIRED') AND closed_at IS NOT NULL
                 AND isfinite(closed_at) AND closed_at >= created_at)));

-- Cobranças e pagamentos usam reais e identificam um provedor não vazio.
-- Essas quatro validações não escolhem fornecedor nem autenticam notificações.
ALTER TABLE billing_charge
    ADD CONSTRAINT ck_billing_charge_currency
    CHECK (currency = 'BRL');

ALTER TABLE billing_charge
    ADD CONSTRAINT ck_billing_charge_provider
    CHECK (BTRIM(provider) <> '');

ALTER TABLE payment
    ADD CONSTRAINT ck_payment_currency
    CHECK (currency = 'BRL');

ALTER TABLE payment
    ADD CONSTRAINT ck_payment_provider
    CHECK (BTRIM(provider) <> '');

-- Exige a identidade externa do pagamento real. A unicidade por provedor
-- no 01 evita tratar reenvio da mesma confirmação como outro pagamento.
ALTER TABLE payment
    ADD CONSTRAINT ck_payment_identity
    CHECK (BTRIM(provider_payment_id) <> '');

-- Preserva um recebimento positivo e mensurável. A conferência do valor
-- recebido contra a cobrança cabe à rotina antes de liberar o benefício.
ALTER TABLE payment
    ADD CONSTRAINT ck_payment_amount
    CHECK (amount > 0 AND amount <> 'NaN'::numeric);

-- Distingue pagamento, verificação e registro, permitindo confirmação tardia.
-- Não comprova autenticidade nem autoriza benefício pelo horário de pagamento.
ALTER TABLE payment
    ADD CONSTRAINT ck_payment_dates
    CHECK (isfinite(paid_at) AND isfinite(verified_at) AND isfinite(created_at) AND paid_at <= verified_at AND verified_at <= created_at);

-- Aplicação do benefício e histórico de upgrade admitem registro posterior
-- ao fato, com instantes finitos. A validade do ciclo na aplicação é verificada
-- entre tabelas pelas rotinas, não por estes dois CHECKs.
ALTER TABLE payment_application
    ADD CONSTRAINT ck_payment_application_dates
    CHECK (isfinite(applied_at) AND isfinite(created_at) AND applied_at <= created_at);

ALTER TABLE subscription_cycle_change
    ADD CONSTRAINT ck_subscription_cycle_change_dates
    CHECK (isfinite(applied_at) AND isfinite(created_at) AND applied_at <= created_at);

-- O histórico deve representar mudança efetiva para um plano de maior preço
-- e maiores franquias. Estes dois CHECKs não somam limites nem iniciam outro ciclo;
-- a rotina aplica a substituição, preservando datas, consumo e reservas.
ALTER TABLE subscription_cycle_change
    ADD CONSTRAINT ck_subscription_cycle_change_plans
    CHECK (from_plan_id <> to_plan_id);

ALTER TABLE subscription_cycle_change
    ADD CONSTRAINT ck_subscription_cycle_change_limits
    CHECK (from_monthly_price > 0 AND to_monthly_price > from_monthly_price
        AND to_monthly_price <> 'NaN'::numeric
        AND from_volume_limit_liters > 0 AND to_volume_limit_liters > from_volume_limit_liters
        AND to_volume_limit_liters <> 'NaN'::numeric
        AND from_collection_limit > 0 AND to_collection_limit > from_collection_limit);

-- Reembolso precisa representar dinheiro positivo. A igualdade integral
-- com o valor do pagamento é garantida pela FK composta do arquivo 01.
ALTER TABLE payment_refund
    ADD CONSTRAINT ck_payment_refund_amount
    CHECK (amount > 0 AND amount <> 'NaN'::numeric);

-- Separa solicitar devolução de concluí-la: só COMPLETED possui data final,
-- nunca anterior à solicitação. A confirmação efetiva vem do provedor.
ALTER TABLE payment_refund
    ADD CONSTRAINT ck_payment_refund_completion
    CHECK ((status = 'COMPLETED' AND completed_at IS NOT NULL AND isfinite(completed_at) AND completed_at >= requested_at)
        OR (status <> 'COMPLETED' AND completed_at IS NULL));

-- A solicitação de reembolso deve ter instante finito para rastreamento
-- e reconciliação das tentativas pelo backend.
ALTER TABLE payment_refund
    ADD CONSTRAINT ck_payment_refund_requested_at
    CHECK (isfinite(requested_at));

-- Tentativas são numeradas a partir de um; a unicidade por reembolso está
-- no 01. Repetir a integração não deve criar outro pedido de devolução.
ALTER TABLE payment_refund_attempt
    ADD CONSTRAINT ck_payment_refund_attempt_number
    CHECK (attempt_number > 0);

-- Tentativa ainda em andamento não possui resultado final. Ao encerrá-la,
-- resultado e término são registrados juntos e preservam a ordem temporal.
ALTER TABLE payment_refund_attempt
    ADD CONSTRAINT ck_payment_refund_attempt_dates
    CHECK (isfinite(started_at) AND ((finished_at IS NULL AND result_status IS NULL) OR (finished_at IS NOT NULL AND result_status IS NOT NULL AND isfinite(finished_at) AND finished_at >= started_at)));

-- Provedor, identidade externa e tipo tornam o evento rastreável.
-- A unicidade do evento está no 01; sua autenticidade depende do backend.
ALTER TABLE payment_provider_event
    ADD CONSTRAINT ck_payment_provider_event_identity
    CHECK (BTRIM(provider) <> '' AND BTRIM(provider_event_id) <> '' AND BTRIM(event_type) <> '');

-- O contador começa em zero, pois o evento pode aguardar processamento.
-- Seu incremento e controle de concorrência são responsabilidade da rotina.
ALTER TABLE payment_provider_event
    ADD CONSTRAINT ck_payment_provider_event_attempts
    CHECK (attempts >= 0);

-- Somente evento PROCESSED possui data de conclusão, posterior ao recebimento.
-- Receber uma notificação não equivale a processá-la com sucesso.
ALTER TABLE payment_provider_event
    ADD CONSTRAINT ck_payment_provider_event_dates
    CHECK (isfinite(received_at) AND ((status = 'PROCESSED' AND processed_at IS NOT NULL AND isfinite(processed_at) AND processed_at >= received_at) OR (status <> 'PROCESSED' AND processed_at IS NULL)));

-- O pedido reserva uma estimativa positiva. Ela não é volume ambiental;
-- as rotinas conferem e reservam litros e vaga no ciclo sob concorrência.
ALTER TABLE collection_request
    ADD CONSTRAINT ck_collection_request_volume
    CHECK (estimated_volume_liters > 0 AND estimated_volume_liters <> 'NaN'::numeric);

-- Preserva a ordem entre solicitação e atualização, com instantes finitos.
-- O CHECK não atualiza o timestamp automaticamente.
ALTER TABLE collection_request
    ADD CONSTRAINT ck_collection_request_updated_at
    CHECK (isfinite(request_at) AND isfinite(updated_at) AND updated_at >= request_at);

-- APPROVED exige aprovação identificada e horário exato; PENDING pode
-- aguardar agendamento. Autor/data permanecem possíveis em estados posteriores,
-- preservando o histórico em vez de apagá-lo no cancelamento.
ALTER TABLE collection_request
    ADD CONSTRAINT ck_collection_request_approval
    CHECK ((approved_at IS NULL) = (approved_by IS NULL)
        AND (status <> 'APPROVED' OR (approved_at IS NOT NULL AND scheduled_at IS NOT NULL))
        AND (approved_at IS NULL OR (isfinite(approved_at) AND approved_at >= request_at))
        AND (scheduled_at IS NULL OR (isfinite(scheduled_at) AND scheduled_at >= request_at)));

-- Chegada, registro pelo backend e motorista informado formam um conjunto.
-- As duas regras exigem aprovação e agendamento para registrar a chegada.
-- Os dois horários distinguem o fato de seu registro; não provam presença física.
ALTER TABLE collection_request
    ADD CONSTRAINT ck_collection_request_arrival
    CHECK ((arrived_at IS NULL AND arrival_recorded_at IS NULL AND arrival_driver_id IS NULL)
        OR (arrived_at IS NOT NULL AND arrival_recorded_at IS NOT NULL AND arrival_driver_id IS NOT NULL
            AND isfinite(arrived_at) AND isfinite(arrival_recorded_at) AND arrived_at >= request_at AND arrival_recorded_at >= arrived_at));

ALTER TABLE collection_request
    ADD CONSTRAINT ck_collection_request_arrival_approval
    CHECK (arrived_at IS NULL OR (approved_at IS NOT NULL AND scheduled_at IS NOT NULL));

-- O aceite pertence ao próprio estabelecimento e pressupõe chegada registrada.
-- Seus marcos não podem anteceder a chegada; a exigência de APPROVED torna o
-- aceite incompatível com CANCELLED. Após atraso, esse aceite encerra a opção
-- de cancelamento gratuito. Autenticação e transições ainda exigem as rotinas.
ALTER TABLE collection_request
    ADD CONSTRAINT ck_collection_request_service_acceptance
    CHECK ((service_accepted_at IS NULL AND service_acceptance_recorded_at IS NULL AND service_accepted_by IS NULL)
        OR (service_accepted_at IS NOT NULL AND service_acceptance_recorded_at IS NOT NULL
            AND service_accepted_by IS NOT NULL AND service_accepted_by = establishment_id
            AND isfinite(service_accepted_at) AND isfinite(service_acceptance_recorded_at)
            AND arrived_at IS NOT NULL AND arrival_recorded_at IS NOT NULL
            AND service_accepted_at >= arrived_at
            AND service_acceptance_recorded_at >= service_accepted_at
            AND service_acceptance_recorded_at >= arrival_recorded_at AND status = 'APPROVED'));

-- Cancelamento exige autoria, iniciativa, motivo e datas. Gratuidade não
-- consome franquia; perda tardia por iniciativa do estabelecimento corresponde
-- exatamente à estimativa e uma vaga, após o limite inclusivo de quatro horas.
-- Essa perda não representa coleta, pontos ou volume para certificados.
ALTER TABLE collection_request
    ADD CONSTRAINT ck_collection_request_cancellation
    CHECK ((status <> 'CANCELLED' AND cancelled_at IS NULL AND cancellation_recorded_at IS NULL
            AND cancelled_by IS NULL AND cancellation_initiative IS NULL AND cancellation_policy IS NULL
            AND cancellation_reason IS NULL AND forfeited_volume_liters = 0 AND forfeited_collection_slots = 0)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancellation_recorded_at IS NOT NULL
            AND cancelled_by IS NOT NULL AND cancellation_initiative IS NOT NULL AND cancellation_policy IS NOT NULL
            AND cancellation_reason IS NOT NULL AND BTRIM(cancellation_reason) <> ''
            AND isfinite(cancelled_at) AND isfinite(cancellation_recorded_at)
            AND cancelled_at >= request_at AND cancellation_recorded_at >= cancelled_at
            AND ((cancellation_policy = 'FREE' AND forfeited_volume_liters = 0 AND forfeited_collection_slots = 0)
                OR (cancellation_policy = 'LATE_FORFEITURE' AND cancellation_initiative = 'ESTABLISHMENT'
                    AND approved_at IS NOT NULL AND scheduled_at IS NOT NULL
                    AND cancelled_at > scheduled_at - INTERVAL '4 hours'
                    AND forfeited_volume_liters = estimated_volume_liters AND forfeited_collection_slots = 1))));

-- Complementa a regra anterior: falha operacional e antecedência permitem
-- gratuidade. A partir de uma hora de tolerância, ausência de chegada registrada
-- ou chegada atrasada mantêm a gratuidade enquanto não houver aceite.
-- Compara também o momento do registro: chegada informada depois do cancelamento
-- não o converte retroativamente em perda. A sequência concorrente das ações
-- e a preservação dos fatos anteriores dependem das rotinas e da auditoria.
ALTER TABLE collection_request
    ADD CONSTRAINT ck_collection_request_cancellation_timing
    CHECK (status <> 'CANCELLED' OR (
        (cancellation_policy = 'FREE' AND (cancellation_initiative = 'OPERATION'
            OR approved_at IS NULL OR scheduled_at IS NULL
            OR cancelled_at <= scheduled_at - INTERVAL '4 hours'
            OR (cancelled_at >= scheduled_at + INTERVAL '1 hour'
                AND (arrived_at IS NULL OR arrival_recorded_at > cancellation_recorded_at
                     OR arrived_at >= scheduled_at + INTERVAL '1 hour'))))
        OR (cancellation_policy = 'LATE_FORFEITURE'
            AND (cancelled_at < scheduled_at + INTERVAL '1 hour'
                OR (arrived_at IS NOT NULL AND arrival_recorded_at IS NOT NULL
                    AND arrival_recorded_at <= cancellation_recorded_at
                    AND arrived_at < scheduled_at + INTERVAL '1 hour')))));

-- Distingue agendamento inicial de reagendamento efetivo. O novo horário
-- precisa diferir do anterior, quando este existir; acordo antecede seu registro.
-- O CHECK não comprova que o estabelecimento concordou com a mudança.
ALTER TABLE collection_schedule_history
    ADD CONSTRAINT ck_collection_schedule_history_dates
    CHECK (isfinite(new_scheduled_at) AND isfinite(agreed_at) AND isfinite(changed_at) AND agreed_at <= changed_at AND (previous_scheduled_at IS NULL OR (isfinite(previous_scheduled_at) AND previous_scheduled_at <> new_scheduled_at)));

-- Preserva a justificativa do agendamento/reagendamento para explicar
-- alterações de horário e seus efeitos no prazo de cancelamento.
ALTER TABLE collection_schedule_history
    ADD CONSTRAINT ck_collection_schedule_history_reason
    CHECK (BTRIM(reason) <> '');

-- Admite visita realizada com zero recolhido, sem inventar volume físico.
-- Não permite recolher mais do que foi apresentado. Ausência de visita é outro
-- caso: não deve gerar coleta, mesmo com os volumes em zero.
ALTER TABLE collection
    ADD CONSTRAINT ck_collection_volumes
    CHECK (presented_volume_liters >= 0 AND presented_volume_liters <> 'NaN'::numeric
        AND collected_volume_liters >= 0 AND collected_volume_liters <= presented_volume_liters);

-- Zero apresentado exige NOT_ASSESSED, pois não houve material a avaliar.
-- Com material apresentado, a avaliação deve ser ACCEPTABLE ou UNACCEPTABLE.
ALTER TABLE collection
    ADD CONSTRAINT ck_collection_condition_presented
    CHECK ((presented_volume_liters = 0 AND oil_condition = 'NOT_ASSESSED')
        OR (presented_volume_liters > 0 AND oil_condition IN ('ACCEPTABLE','UNACCEPTABLE')));

-- Rejeita sucesso sem volume recolhido, com óleo inadequado ou ocorrência.
-- São condições necessárias: comparar a estimativa do pedido e aplicar a faixa
-- inclusiva de 90% a 110% exige a rotina, pois a estimativa está em outra tabela.
ALTER TABLE collection
    ADD CONSTRAINT ck_collection_success_conditions
    CHECK (result <> 'SUCCESSFUL' OR (collected_volume_liters > 0 AND oil_condition IS NOT NULL AND oil_condition = 'ACCEPTABLE' AND NOT has_compromising_occurrence));

-- Exige contexto nos casos de ausência de óleo, inadequação ou ocorrência.
-- Somente desvio de volume não obriga observação por esta regra.
ALTER TABLE collection
    ADD CONSTRAINT ck_collection_explanation
    CHECK (NOT (presented_volume_liters = 0 OR oil_condition = 'UNACCEPTABLE' OR has_compromising_occurrence)
        OR (observation IS NOT NULL AND BTRIM(observation) <> ''));

-- Coleta anulada tem efeito zero; malsucesso vigente aplica uma única -50.
-- Sucesso admite litros inteiros +50 e bônus de 0 a 100, em múltiplos de 10.
-- Este CHECK limita os valores possíveis; a rotina calcula o marco de recorrência
-- correto e mantém coerência com as movimentações e o saldo do estabelecimento.
ALTER TABLE collection
    ADD CONSTRAINT ck_collection_points
    CHECK ((record_status = 'ANNULLED' AND points_earned = 0)
        OR (record_status = 'RECORDED' AND ((result = 'UNSUCCESSFUL' AND points_earned = -50)
            OR (result = 'SUCCESSFUL' AND points_earned >= FLOOR(collected_volume_liters)::bigint + 50
                AND points_earned <= FLOOR(collected_volume_liters)::bigint + 150
                AND (points_earned - FLOOR(collected_volume_liters)::bigint - 50) % 10 = 0))));

-- A ordem de processamento é positiva e serve à reconstrução da pontuação.
-- Não deriva da data física; atribuição e imutabilidade dependem das rotinas.
ALTER TABLE collection
    ADD CONSTRAINT ck_collection_order
    CHECK (processing_order > 0);

-- Permite registrar uma visita depois que ocorreu, preservando os dois fatos.
-- Uma data física posterior ao próprio registro ou infinita não é aceita.
ALTER TABLE collection
    ADD CONSTRAINT ck_collection_dates
    CHECK (isfinite(collection_date) AND isfinite(created_at) AND collection_date <= created_at);

-- B2C registra somente óleo aceito positivo. Recusa total ou ausência de óleo
-- não gera entrega; frações menores que um litro continuam sendo registráveis.
ALTER TABLE delivery_pev
    ADD CONSTRAINT ck_delivery_pev_volume
    CHECK (oil_volume_liters > 0 AND oil_volume_liters <> 'NaN'::numeric);

-- B2C recebe somente litros completos, sem bônus ou penalidade B2B.
-- Assim, 0,7 L gera zero pontos; anulação zera o efeito vigente da entrega.
ALTER TABLE delivery_pev
    ADD CONSTRAINT ck_delivery_pev_points
    CHECK (points_earned = CASE WHEN record_status = 'ANNULLED' THEN 0 ELSE FLOOR(oil_volume_liters)::bigint END);

-- Distingue entrega física de registro posterior, sem aceitar fato futuro
-- em relação ao registro. A autorização do PEV é validada pela rotina.
ALTER TABLE delivery_pev
    ADD CONSTRAINT ck_delivery_pev_dates
    CHECK (isfinite(delivery_date) AND isfinite(created_at) AND delivery_date <= created_at);

-- Correções de coleta e entrega seguem a mesma regra: revisão inicial sem
-- metadados de correção; revisão posterior exige autor, instante e justificativa.
-- Anulação exige revisão posterior. A FK verifica ADMIN; incrementar a revisão,
-- preservar antes/depois e recalcular os efeitos são tarefas das rotinas.
ALTER TABLE collection
    ADD CONSTRAINT ck_collection_correction
    CHECK ((revision = 1 AND record_status = 'RECORDED' AND corrected_at IS NULL AND corrected_by IS NULL AND correction_reason IS NULL)
        OR (revision > 1 AND corrected_at IS NOT NULL AND corrected_by IS NOT NULL
            AND correction_reason IS NOT NULL AND BTRIM(correction_reason) <> ''
            AND isfinite(corrected_at) AND corrected_at >= created_at));

ALTER TABLE delivery_pev
    ADD CONSTRAINT ck_delivery_pev_correction
    CHECK ((revision = 1 AND record_status = 'RECORDED' AND corrected_at IS NULL AND corrected_by IS NULL AND correction_reason IS NULL)
        OR (revision > 1 AND corrected_at IS NOT NULL AND corrected_by IS NOT NULL
            AND correction_reason IS NOT NULL AND BTRIM(correction_reason) <> ''
            AND isfinite(corrected_at) AND corrected_at >= created_at));

-- O código do motivo precisa ser identificável para classificação e relatórios.
-- Seu significado não deve ser reaproveitado para outra regra; preservar histórico.
ALTER TABLE collection_failure_reason
    ADD CONSTRAINT ck_collection_failure_reason_code
    CHECK (BTRIM(code) <> '');

-- Cada cálculo tem uma única origem: coleta B2B ou entrega B2C.
-- Evita cálculo sem operação ou atribuído simultaneamente aos dois fluxos.
ALTER TABLE point_calculation
    ADD CONSTRAINT ck_point_calculation_source
    CHECK ((collection_id IS NOT NULL) <> (delivery_pev_id IS NOT NULL));

-- Aplica piso zero por evento: saldo 20 com penalidade -50 resulta em zero.
-- Não equivale a limitar apenas a soma final do histórico. O uso de numeric na
-- expressão evita overflow intermediário da soma de dois valores bigint.
ALTER TABLE point_calculation
    ADD CONSTRAINT ck_point_calculation_balance
    CHECK (balance_before >= 0 AND balance_after >= 0 AND balance_after = GREATEST(0::numeric, balance_before::numeric + points_total::numeric));

-- Contagem de sucessos é exclusiva do B2B e pode ser zero. O B2C não recebe
-- penalidade negativa nem usa recorrência. O valor acumulado correto é calculado
-- pela rotina; este CHECK não conta as coletas de outras linhas.
ALTER TABLE point_calculation
    ADD CONSTRAINT ck_point_calculation_success_count
    CHECK ((collection_id IS NOT NULL AND successful_collections_count IS NOT NULL AND successful_collections_count >= 0) OR (delivery_pev_id IS NOT NULL AND successful_collections_count IS NULL AND points_total >= 0));

-- Distingue cálculo inicial de reconstrução administrativa justificada.
-- Uma revisão pode decorrer da correção de evento anterior; o número não precisa
-- coincidir com a revisão da própria coleta. Versões anteriores são preservadas.
ALTER TABLE point_calculation
    ADD CONSTRAINT ck_point_calculation_revision
    CHECK (isfinite(calculated_at) AND ((revision = 1 AND recalculated_by IS NULL AND recalculation_reason IS NULL)
        OR (revision > 1 AND recalculated_by IS NOT NULL AND recalculation_reason IS NOT NULL AND BTRIM(recalculation_reason) <> '')));

-- Cada componente tem domínio próprio: volume não negativo, sucesso +50,
-- falha -50 e recorrência de 10 a 100 em marcos de 10. A unicidade por cálculo
-- está no 01; somar componentes e impedir combinações incompatíveis cabe à rotina.
ALTER TABLE point_transaction
    ADD CONSTRAINT ck_point_transaction_component
    CHECK ((component = 'VOLUME' AND points >= 0)
        OR (component = 'SUCCESS_BONUS' AND points = 50)
        OR (component = 'FAILURE_PENALTY' AND points = -50)
        OR (component = 'RECURRENCE_BONUS' AND points BETWEEN 10 AND 100 AND points % 10 = 0));

-- A meta precisa representar litros positivos. Esta regra não escolhe metas
-- e não comprova que o estabelecimento atingiu o volume necessário.
ALTER TABLE certificate_level
    ADD CONSTRAINT ck_certificate_level_volume
    CHECK (required_liters > 0 AND required_liters <> 'NaN'::numeric);

-- Evita certificado sem código utilizável para identificação; a unicidade
-- desse código e da concessão por estabelecimento/nível está no arquivo 01.
ALTER TABLE certificate
    ADD CONSTRAINT ck_certificate_code
    CHECK (BTRIM(certificate_code) <> '');

-- Preserva emissão, registro e mudanças de validade em ordem compatível.
-- Revogação e reativação podem manter suas datas históricas; esta regra não
-- reconstrói a sequência completa das mudanças, preservada na auditoria.
ALTER TABLE certificate
    ADD CONSTRAINT ck_certificate_dates
    CHECK (isfinite(issued_at) AND issued_at <= created_at AND (revoked_at IS NULL OR (isfinite(revoked_at) AND revoked_at >= issued_at)) AND (reactivated_at IS NULL OR (isfinite(reactivated_at) AND reactivated_at >= issued_at)));

-- Revogação exige data e justificativa. A rotina reavalia o volume oficial
-- e reativa o mesmo certificado ao atingir a meta, sem duplicar a concessão.
ALTER TABLE certificate
    ADD CONSTRAINT ck_certificate_revocation
    CHECK (status <> 'REVOKED' OR (revoked_at IS NOT NULL AND status_reason IS NOT NULL AND BTRIM(status_reason) <> ''));

-- Ao vincular evento a pagamento, exige também sua identidade externa.
-- Evita que NULL nessa parte permita contornar a verificação da FK composta.
ALTER TABLE payment_provider_event
    ADD CONSTRAINT ck_payment_provider_event_payment_identity
    CHECK (payment_id IS NULL OR provider_payment_id IS NOT NULL);

-- Identificadores externos opcionais podem aguardar a resposta do provedor.
-- Nos três campos abaixo, ausência é NULL; uma identificação vazia não é válida.
ALTER TABLE billing_charge
    ADD CONSTRAINT ck_billing_charge_provider_charge_id_not_blank
    CHECK (provider_charge_id IS NULL OR BTRIM(provider_charge_id) <> '');

ALTER TABLE payment_refund
    ADD CONSTRAINT ck_payment_refund_provider_refund_id_not_blank
    CHECK (provider_refund_id IS NULL OR BTRIM(provider_refund_id) <> '');

ALTER TABLE payment_provider_event
    ADD CONSTRAINT ck_payment_provider_event_provider_payment_id_not_blank
    CHECK (provider_payment_id IS NULL OR BTRIM(provider_payment_id) <> '');

-- Nomes preservados no ciclo e na cotação precisam ser legíveis, mesmo após
-- mudanças no catálogo. Estes CHECKs não garantem sua imutabilidade histórica.
ALTER TABLE subscription_cycle
    ADD CONSTRAINT ck_subscription_cycle_plan_name
    CHECK (BTRIM(plan_name) <> '');

ALTER TABLE billing_charge
    ADD CONSTRAINT ck_billing_charge_plan_name
    CHECK (BTRIM(plan_name) <> '');

-- Reembolso e evento podem não ter nova tentativa agendada. Se houver data,
-- ela deve ser finita; política de repetição e escolha do prazo são do backend.
ALTER TABLE payment_refund
    ADD CONSTRAINT ck_payment_refund_next_attempt
    CHECK (next_attempt_at IS NULL OR isfinite(next_attempt_at));

ALTER TABLE payment_provider_event
    ADD CONSTRAINT ck_payment_provider_event_next_attempt
    CHECK (next_attempt_at IS NULL OR isfinite(next_attempt_at));

-- Complementa os marcos de ativação: PENDING ainda não possui esses marcos,
-- ACTIVE não mantém encerramento e inativação não antecede ativação existente.
ALTER TABLE establishment_subscription
    ADD CONSTRAINT ck_establishment_subscription_status_dates
    CHECK ((status <> 'ACTIVE' OR inactivated_at IS NULL)
        AND (status <> 'PENDING' OR (activated_at IS NULL AND inactivated_at IS NULL))
        AND (activated_at IS NULL OR inactivated_at IS NULL OR inactivated_at >= activated_at));

-- Padrão comum aos nove cadastros/registros abaixo: criação e atualização
-- finitas, sem atualização anterior à criação. A rotina mantém updated_at;
-- essas validações não o preenchem automaticamente nem criam trilha de auditoria.
ALTER TABLE users
    ADD CONSTRAINT ck_users_timestamps
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at);

ALTER TABLE pev
    ADD CONSTRAINT ck_pev_timestamps
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at);

ALTER TABLE subscription_plan
    ADD CONSTRAINT ck_subscription_plan_timestamps
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at);

ALTER TABLE establishment_subscription
    ADD CONSTRAINT ck_establishment_subscription_timestamps
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at);

ALTER TABLE subscription_cycle
    ADD CONSTRAINT ck_subscription_cycle_timestamps
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at);

ALTER TABLE billing_order
    ADD CONSTRAINT ck_billing_order_timestamps
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at);

ALTER TABLE billing_charge
    ADD CONSTRAINT ck_billing_charge_timestamps
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at);

ALTER TABLE certificate_level
    ADD CONSTRAINT ck_certificate_level_timestamps
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at);

ALTER TABLE certificate
    ADD CONSTRAINT ck_certificate_timestamps
    CHECK (isfinite(created_at) AND isfinite(updated_at) AND updated_at >= created_at);

-- AUDITORIA — o mesmo trio de CHECKs se aplica a todas as tabelas de log abaixo.
-- Snapshot: INSERT guarda AFTER, DELETE guarda BEFORE e UPDATE admite ambos.
-- A presença do par BEFORE/AFTER em uma atualização depende das Triggers.
-- Autoria: USER exige usuário; DRIVER_FORM identifica motorista sem atribuir login;
-- SYSTEM não atribui usuário autenticado. Isso valida a forma dos dados, não
-- autentica quem forneceu o contexto. O instante da auditoria deve ser finito.
-- Esses CHECKs não geram logs nem impedem sua edição; rotinas e permissões fazem isso.
ALTER TABLE users_log
    ADD CONSTRAINT ck_users_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE users_log
    ADD CONSTRAINT ck_users_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE users_log
    ADD CONSTRAINT ck_users_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE addresses_log
    ADD CONSTRAINT ck_addresses_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE addresses_log
    ADD CONSTRAINT ck_addresses_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE addresses_log
    ADD CONSTRAINT ck_addresses_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE user_qr_code_log
    ADD CONSTRAINT ck_user_qr_code_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE user_qr_code_log
    ADD CONSTRAINT ck_user_qr_code_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE user_qr_code_log
    ADD CONSTRAINT ck_user_qr_code_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE citizens_log
    ADD CONSTRAINT ck_citizens_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE citizens_log
    ADD CONSTRAINT ck_citizens_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE citizens_log
    ADD CONSTRAINT ck_citizens_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE establishment_log
    ADD CONSTRAINT ck_establishment_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE establishment_log
    ADD CONSTRAINT ck_establishment_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE establishment_log
    ADD CONSTRAINT ck_establishment_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE pev_log
    ADD CONSTRAINT ck_pev_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE pev_log
    ADD CONSTRAINT ck_pev_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE pev_log
    ADD CONSTRAINT ck_pev_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE subscription_plan_log
    ADD CONSTRAINT ck_subscription_plan_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE subscription_plan_log
    ADD CONSTRAINT ck_subscription_plan_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE subscription_plan_log
    ADD CONSTRAINT ck_subscription_plan_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE establishment_subscription_log
    ADD CONSTRAINT ck_establishment_subscription_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE establishment_subscription_log
    ADD CONSTRAINT ck_establishment_subscription_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE establishment_subscription_log
    ADD CONSTRAINT ck_establishment_subscription_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE subscription_cycle_log
    ADD CONSTRAINT ck_subscription_cycle_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE subscription_cycle_log
    ADD CONSTRAINT ck_subscription_cycle_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE subscription_cycle_log
    ADD CONSTRAINT ck_subscription_cycle_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE billing_order_log
    ADD CONSTRAINT ck_billing_order_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE billing_order_log
    ADD CONSTRAINT ck_billing_order_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE billing_order_log
    ADD CONSTRAINT ck_billing_order_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE billing_charge_log
    ADD CONSTRAINT ck_billing_charge_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE billing_charge_log
    ADD CONSTRAINT ck_billing_charge_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE billing_charge_log
    ADD CONSTRAINT ck_billing_charge_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE payment_log
    ADD CONSTRAINT ck_payment_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE payment_log
    ADD CONSTRAINT ck_payment_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE payment_log
    ADD CONSTRAINT ck_payment_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE payment_application_log
    ADD CONSTRAINT ck_payment_application_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE payment_application_log
    ADD CONSTRAINT ck_payment_application_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE payment_application_log
    ADD CONSTRAINT ck_payment_application_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE payment_refund_log
    ADD CONSTRAINT ck_payment_refund_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE payment_refund_log
    ADD CONSTRAINT ck_payment_refund_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE payment_refund_log
    ADD CONSTRAINT ck_payment_refund_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE collection_request_log
    ADD CONSTRAINT ck_collection_request_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE collection_request_log
    ADD CONSTRAINT ck_collection_request_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE collection_request_log
    ADD CONSTRAINT ck_collection_request_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE collection_log
    ADD CONSTRAINT ck_collection_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE collection_log
    ADD CONSTRAINT ck_collection_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE collection_log
    ADD CONSTRAINT ck_collection_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE collection_failure_reason_log
    ADD CONSTRAINT ck_collection_failure_reason_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE collection_failure_reason_log
    ADD CONSTRAINT ck_collection_failure_reason_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE collection_failure_reason_log
    ADD CONSTRAINT ck_collection_failure_reason_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE collection_failure_log
    ADD CONSTRAINT ck_collection_failure_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE collection_failure_log
    ADD CONSTRAINT ck_collection_failure_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE collection_failure_log
    ADD CONSTRAINT ck_collection_failure_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE delivery_pev_log
    ADD CONSTRAINT ck_delivery_pev_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE delivery_pev_log
    ADD CONSTRAINT ck_delivery_pev_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE delivery_pev_log
    ADD CONSTRAINT ck_delivery_pev_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE point_calculation_log
    ADD CONSTRAINT ck_point_calculation_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE point_calculation_log
    ADD CONSTRAINT ck_point_calculation_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE point_calculation_log
    ADD CONSTRAINT ck_point_calculation_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE certificate_level_log
    ADD CONSTRAINT ck_certificate_level_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE certificate_level_log
    ADD CONSTRAINT ck_certificate_level_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE certificate_level_log
    ADD CONSTRAINT ck_certificate_level_log_performed_at
    CHECK (isfinite(performed_at));

ALTER TABLE certificate_log
    ADD CONSTRAINT ck_certificate_log_snapshot
    CHECK ((operation = 'INSERT' AND snapshot_kind = 'AFTER') OR (operation = 'DELETE' AND snapshot_kind = 'BEFORE') OR operation = 'UPDATE');

ALTER TABLE certificate_log
    ADD CONSTRAINT ck_certificate_log_actor
    CHECK ((actor_kind = 'USER' AND performed_by IS NOT NULL) OR (actor_kind = 'DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id IS NOT NULL) OR (actor_kind = 'SYSTEM' AND performed_by IS NULL));

ALTER TABLE certificate_log
    ADD CONSTRAINT ck_certificate_log_performed_at
    CHECK (isfinite(performed_at));