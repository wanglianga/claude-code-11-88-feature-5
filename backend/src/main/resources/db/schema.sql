-- ============================================================
-- 科研影像湖仓存储治理平台 - 数据库结构
-- PostgreSQL 18
-- ============================================================

-- ---------- 应用用户（演示用，非生产配置） ----------
CREATE TABLE IF NOT EXISTS app_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64) NOT NULL UNIQUE,
    password_hash   VARCHAR(128) NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    role            VARCHAR(32)  NOT NULL CHECK (role IN ('ADMIN','REVIEWER')),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

-- ---------- 项目 ----------
CREATE TABLE IF NOT EXISTS project (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(32) NOT NULL UNIQUE,
    name            VARCHAR(256) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','WITHDRAWN')),
    withdrawn_at    TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

-- ---------- 存储层与费率版本 ----------
CREATE TABLE IF NOT EXISTS storage_tier (
    code                    VARCHAR(32) PRIMARY KEY,
    name                    VARCHAR(64) NOT NULL,
    gb_month_rate           NUMERIC(12,6) NOT NULL,   -- 元 / GB·月
    retrieval_gb_rate       NUMERIC(12,6) NOT NULL,   -- 取回费 元/GB
    restore_compute_gb_rate NUMERIC(12,6) NOT NULL,   -- 恢复计算费 元/GB
    retrieval_hours         NUMERIC(8,2)  NOT NULL,   -- 该层取回耗时（小时）
    rto_four_hour_capable   BOOLEAN NOT NULL DEFAULT false, -- 是否满足4h恢复目标
    rate_version            VARCHAR(32) NOT NULL DEFAULT 'RV-2026Q4',
    description             VARCHAR(256)
);

-- ---------- 对象目录 ----------
CREATE TABLE IF NOT EXISTS data_object (
    id                      BIGSERIAL PRIMARY KEY,
    object_uid              VARCHAR(64) NOT NULL UNIQUE,
    name                    VARCHAR(256) NOT NULL,
    category                VARCHAR(48) NOT NULL,
    byte_size               BIGINT NOT NULL CHECK (byte_size >= 0),
    checksum                VARCHAR(128) NOT NULL,      -- sha256
    checksum_status         VARCHAR(16) NOT NULL DEFAULT 'OK'
                            CHECK (checksum_status IN ('OK','FAILED','UNVERIFIED')),
    tier_code               VARCHAR(32) NOT NULL REFERENCES storage_tier(code),
    owning_project_id       BIGINT NOT NULL REFERENCES project(id),
    created_at              TIMESTAMP NOT NULL,
    last_accessed_at        TIMESTAMP NOT NULL,
    base_retention_deadline DATE,                       -- 策略基础保留截止日
    retention_deadline      DATE,                       -- 经血缘/冻结传播后的有效截止日
    litigation_hold         BOOLEAN NOT NULL DEFAULT false,
    software_version        VARCHAR(64),                -- 算法包/派生处理的软件版本
    recipe                  TEXT,                       -- 处理配方
    directory_available     BOOLEAN NOT NULL DEFAULT true,
    is_restore_package      BOOLEAN NOT NULL DEFAULT false,
    package_target_uid      VARCHAR(64),                -- 恢复包对应的复现目标
    withdrawn               BOOLEAN NOT NULL DEFAULT false,
    note                    VARCHAR(512)
);
CREATE INDEX IF NOT EXISTS idx_object_project ON data_object(owning_project_id);
CREATE INDEX IF NOT EXISTS idx_object_category ON data_object(category);
CREATE INDEX IF NOT EXISTS idx_object_tier ON data_object(tier_code);

-- ---------- 血缘 DAG ----------
CREATE TABLE IF NOT EXISTS lineage_edge (
    id                  BIGSERIAL PRIMARY KEY,
    from_object_id      BIGINT NOT NULL REFERENCES data_object(id), -- 输入
    to_object_id        BIGINT NOT NULL REFERENCES data_object(id), -- 输出
    direction           VARCHAR(32) NOT NULL DEFAULT 'INPUT_TO_OUTPUT',
    edge_version        VARCHAR(32) NOT NULL DEFAULT 'v1',
    recipe              TEXT,
    software_version    VARCHAR(64),
    shared_reference    BOOLEAN NOT NULL DEFAULT false, -- 是否为跨项目共享引用
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (from_object_id, to_object_id),
    CHECK (from_object_id <> to_object_id)
);
CREATE INDEX IF NOT EXISTS idx_edge_from ON lineage_edge(from_object_id);
CREATE INDEX IF NOT EXISTS idx_edge_to ON lineage_edge(to_object_id);

-- ---------- 共享引用份额（冻结） ----------
CREATE TABLE IF NOT EXISTS object_share (
    id              BIGSERIAL PRIMARY KEY,
    object_id       BIGINT NOT NULL REFERENCES data_object(id),
    project_id      BIGINT NOT NULL REFERENCES project(id),
    frozen_share    NUMERIC(7,6) NOT NULL CHECK (frozen_share BETWEEN 0 AND 1),
    share_basis     VARCHAR(16) NOT NULL CHECK (share_basis IN ('REFERENCE','USAGE')),
    frozen_at       TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (object_id, project_id)
);

-- ---------- 保留策略（按对象类别 × 研究协议） ----------
CREATE TABLE IF NOT EXISTS retention_policy (
    id                  BIGSERIAL PRIMARY KEY,
    category            VARCHAR(48) NOT NULL,
    protocol_code       VARCHAR(32) NOT NULL,
    retention_years     NUMERIC(6,2) NOT NULL,
    rule_text           VARCHAR(512) NOT NULL,
    UNIQUE (category, protocol_code)
);

-- ---------- 成本日账（字节时长 × 层费率） ----------
CREATE TABLE IF NOT EXISTS cost_daily (
    id                  BIGSERIAL PRIMARY KEY,
    object_id           BIGINT NOT NULL REFERENCES data_object(id),
    day                 DATE NOT NULL,
    byte_hours          NUMERIC(20,3) NOT NULL,  -- 当日字节×24
    tier_code           VARCHAR(32) NOT NULL,
    gb_month_rate       NUMERIC(12,6) NOT NULL,  -- 费率版本快照
    storage_charge      NUMERIC(14,6) NOT NULL,  -- (GB) × 月费率/30
    UNIQUE (object_id, day)
);

-- ---------- 取回 / 恢复计算事件 ----------
CREATE TABLE IF NOT EXISTS retrieval_event (
    id                  BIGSERIAL PRIMARY KEY,
    object_id           BIGINT NOT NULL REFERENCES data_object(id),
    project_id          BIGINT NOT NULL REFERENCES project(id),
    plan_id             BIGINT,
    bytes               BIGINT NOT NULL,
    tier_code           VARCHAR(32) NOT NULL,
    retrieval_fee       NUMERIC(14,6) NOT NULL,
    restore_compute_fee NUMERIC(14,6) NOT NULL,
    event_time          TIMESTAMP NOT NULL DEFAULT now(),
    reason              VARCHAR(256)
);

-- ---------- 恢复计划 ----------
CREATE TABLE IF NOT EXISTS restore_plan (
    id                      BIGSERIAL PRIMARY KEY,
    target_object_id        BIGINT NOT NULL REFERENCES data_object(id),
    project_id              BIGINT NOT NULL REFERENCES project(id),
    strategy                VARCHAR(32) NOT NULL
                            CHECK (strategy IN ('SCHEDULED_TIERING','RESTORE_PACKAGE')),
    scheduled_at            TIMESTAMP,
    rto_hours               NUMERIC(6,2) NOT NULL DEFAULT 4,
    lead_hours              NUMERIC(6,2) NOT NULL DEFAULT 72,
    frequency_per_year      NUMERIC(6,2) NOT NULL DEFAULT 1,
    status                  VARCHAR(32) NOT NULL DEFAULT 'DRAFT'
                            CHECK (status IN ('DRAFT','SATISFIABLE','UNSATISFIABLE','EXECUTED','BLOCKED')),
    estimated_hours         NUMERIC(8,2),
    closure_count           INT,
    missing_items           JSONB,
    per_run_retrieval_fee   NUMERIC(14,6),
    per_run_compute_fee     NUMERIC(14,6),
    package_monthly_fee     NUMERIC(14,6),
    created_at              TIMESTAMP NOT NULL DEFAULT now(),
    note                    VARCHAR(512)
);

-- ---------- 恢复包 ----------
CREATE TABLE IF NOT EXISTS restore_package (
    id                  BIGSERIAL PRIMARY KEY,
    target_object_id    BIGINT NOT NULL REFERENCES data_object(id),
    package_object_id   BIGINT NOT NULL REFERENCES data_object(id),
    project_id          BIGINT NOT NULL REFERENCES project(id),
    validated           BOOLEAN NOT NULL DEFAULT false,
    validation_detail   JSONB,
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);

-- ---------- 月账单与项目分摊 ----------
CREATE TABLE IF NOT EXISTS monthly_bill (
    id                  BIGSERIAL PRIMARY KEY,
    project_id          BIGINT NOT NULL REFERENCES project(id),
    billing_month       CHAR(7) NOT NULL,           -- 'YYYY-MM'
    storage_fee         NUMERIC(14,6) NOT NULL,
    retrieval_fee       NUMERIC(14,6) NOT NULL,
    restore_compute_fee NUMERIC(14,6) NOT NULL,
    total_fee           NUMERIC(14,6) NOT NULL,
    allocation_json     JSONB NOT NULL,
    allocation_check    BOOLEAN NOT NULL,
    UNIQUE (project_id, billing_month)
);

-- ---------- 归档快照（目录水位 + 依赖 + 费率版本 + 决定） ----------
CREATE TABLE IF NOT EXISTS archive_snapshot (
    id              BIGSERIAL PRIMARY KEY,
    snapshot_date   DATE NOT NULL,
    rate_version    VARCHAR(32) NOT NULL,
    catalog_json    JSONB NOT NULL,   -- 目录水位
    lineage_json    JSONB NOT NULL,   -- 依赖
    billing_json    JSONB NOT NULL,   -- 账单与分摊
    decisions_json  JSONB NOT NULL,   -- 迁移/保留/计费理由（可回放）
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

-- ---------- 治理事件（校验失败/规则变化/冻结，需传播） ----------
CREATE TABLE IF NOT EXISTS governance_event (
    id              BIGSERIAL PRIMARY KEY,
    event_type      VARCHAR(48) NOT NULL
                    CHECK (event_type IN (
                        'CHECKSUM_FAILED','RETENTION_RULE_CHANGED','LITIGATION_HOLD',
                        'LITIGATION_HOLD_RELEASED','PROJECT_WITHDRAWN','RESTORE_BLOCKED',
                        'TIER_MIGRATION','SHARE_FROZEN')),
    object_id       BIGINT REFERENCES data_object(id),
    project_id      BIGINT REFERENCES project(id),
    payload         JSONB,
    propagated      BOOLEAN NOT NULL DEFAULT false,
    affected_uids   JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

-- ============================================================
-- 可复现研究队列抽取（Cohort Extraction）
-- 患者为不可拆分分组：同一患者的全部复扫必须落在同一分区
-- ============================================================

-- ---------- 队列候选患者（聚合疾病/设备/协议特征、许可、排除原因） ----------
CREATE TABLE IF NOT EXISTS cohort_patient (
    id                        BIGSERIAL PRIMARY KEY,
    patient_uid               VARCHAR(32) NOT NULL UNIQUE,
    disease_grade             INT NOT NULL CHECK (disease_grade BETWEEN 0 AND 3),
    disease_label             VARCHAR(32) NOT NULL,
    primary_device            VARCHAR(48) NOT NULL,
    device_features           JSONB NOT NULL DEFAULT '[]',   -- 接触过的全部设备
    protocol_features         JSONB NOT NULL DEFAULT '[]',   -- 出现过的全部序列/协议
    scan_count                INT NOT NULL DEFAULT 0,
    consent_status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                              CHECK (consent_status IN ('ACTIVE','EXPIRED','REVOKED')),
    consent_scope             JSONB NOT NULL DEFAULT '["TRAIN","VAL"]', -- 许可可用分区范围
    consent_expires_at        DATE,
    catalog_exclusion_reason  VARCHAR(48),                   -- 目录标注的排除原因（QC_FAILED 等）
    exclusion_detail          VARCHAR(256),
    source_updated_at         TIMESTAMP NOT NULL,            -- 输入水位依据
    created_at                TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cohort_patient_eligible
    ON cohort_patient(consent_status, catalog_exclusion_reason);

-- ---------- 队列候选扫描（1600 次；重复/相邻复扫按 patient_uid 成组） ----------
CREATE TABLE IF NOT EXISTS cohort_scan (
    id                  BIGSERIAL PRIMARY KEY,
    scan_uid            VARCHAR(40) NOT NULL UNIQUE,
    patient_uid         VARCHAR(32) NOT NULL REFERENCES cohort_patient(patient_uid),
    device_code         VARCHAR(48) NOT NULL,
    protocol_code       VARCHAR(48) NOT NULL,
    sequence_name       VARCHAR(64),
    session_no          INT NOT NULL DEFAULT 1,
    is_repeat_rescan    BOOLEAN NOT NULL DEFAULT false,
    acquired_at         TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_cohort_scan_patient ON cohort_scan(patient_uid);
CREATE INDEX IF NOT EXISTS idx_cohort_scan_protocol ON cohort_scan(protocol_code);

-- ---------- 约束画像（编译器输入：患者数/类别/设备/协议覆盖的硬下限与软目标） ----------
CREATE TABLE IF NOT EXISTS cohort_constraint_profile (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(48) NOT NULL UNIQUE,
    name          VARCHAR(128) NOT NULL,
    spec_json     JSONB NOT NULL,
    builtin       BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

-- ---------- 抽取账（种子、输入水位、路线、状态、统计） ----------
CREATE TABLE IF NOT EXISTS cohort_run (
    id                    BIGSERIAL PRIMARY KEY,
    run_uid               VARCHAR(48) NOT NULL UNIQUE,
    profile_code          VARCHAR(48),
    run_name              VARCHAR(128),
    strategy              VARCHAR(24) NOT NULL
                          CHECK (strategy IN ('GLOBAL_ASSIGN','STREAM_STRATIFIED')),
    seed                  BIGINT NOT NULL,                  -- 复现种子
    spec_json             JSONB NOT NULL,                   -- 原始约束
    compiled_json         JSONB,                            -- 编译后的硬约束/软目标
    input_watermark       VARCHAR(160) NOT NULL,            -- 输入水位（计数+摘要）
    watermark_json        JSONB,
    status                VARCHAR(24) NOT NULL DEFAULT 'OK'
                          CHECK (status IN ('OK','COVERAGE_GAP')),
    candidate_patients    INT NOT NULL DEFAULT 0,
    eligible_patients     INT NOT NULL DEFAULT 0,
    rejected_patients     INT NOT NULL DEFAULT 0,
    assigned_patients     INT NOT NULL DEFAULT 0,
    assigned_scans        INT NOT NULL DEFAULT 0,
    objective_score       NUMERIC(20,6),
    solver_stats          JSONB,
    incremental           BOOLEAN NOT NULL DEFAULT false,
    parent_run_id         BIGINT REFERENCES cohort_run(id),
    delta_patients        INT NOT NULL DEFAULT 0,
    created_by            VARCHAR(64),
    created_at            TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cohort_run_created ON cohort_run(id DESC);

-- ---------- 账本：拒绝原因（许可失效 / 目录排除等） ----------
CREATE TABLE IF NOT EXISTS cohort_rejection (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL REFERENCES cohort_run(id) ON DELETE CASCADE,
    patient_uid     VARCHAR(32) NOT NULL,
    reason_code     VARCHAR(48) NOT NULL,
    detail          VARCHAR(512),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cohort_rejection_run ON cohort_rejection(run_id);

-- ---------- 患者分区分配（每名患者在每次抽取中仅一行 → 结构上不可跨分区） ----------
CREATE TABLE IF NOT EXISTS cohort_assignment (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL REFERENCES cohort_run(id) ON DELETE CASCADE,
    patient_uid     VARCHAR(32) NOT NULL,
    split_code      VARCHAR(16) NOT NULL,
    scan_count      INT NOT NULL DEFAULT 0,
    decision_path   VARCHAR(24) NOT NULL DEFAULT 'DIRECT'
                    CHECK (decision_path IN (
                        'DIRECT','COVERAGE_SEED','STRATIFIED','SWAP_REPAIR',
                        'GLOBAL_INIT','GLOBAL_OPTIMIZE','INCREMENTAL')),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (run_id, patient_uid)
);
CREATE INDEX IF NOT EXISTS idx_cohort_assignment_run_split
    ON cohort_assignment(run_id, split_code);

-- ---------- 校验报告（跨分区/配额偏差/覆盖缺口/许可范围） ----------
CREATE TABLE IF NOT EXISTS cohort_validation (
    run_id          BIGINT PRIMARY KEY REFERENCES cohort_run(id) ON DELETE CASCADE,
    passed          BOOLEAN NOT NULL,
    report_json     JSONB NOT NULL,
    validated_at    TIMESTAMP NOT NULL DEFAULT now()
);
