# 科研影像湖仓存储治理平台（Research Imaging Lakehouse Governance）

面向科研影像湖仓的存储治理平台，覆盖**对象目录、血缘 DAG、保留策略、恢复校验、成本分摊、归档快照、项目审阅、可复现研究队列抽取**八大能力。
技术栈：**Vue 3 + Vite + Pinia**（前端）、**Spring Boot 3 + Spring JDBC**（后端）、**PostgreSQL 18**（数据库，递归 CTE 实现血缘闭包与影响传播）。

## 1. 业务能力与题目对应

| 题目要求 | 实现位置 |
| --- | --- |
| 对象目录：对象ID/字节数/校验摘要/层/创建访问时间/责任项目/保留截止日 | `data_object` 表 + 「对象目录与分层」界面 |
| 血缘 DAG：输入输出方向、版本、处理配方、共享引用 | `lineage_edge` 表 + 递归 CTE（`LineageService`）+ SVG DAG 界面 |
| 保留策略：按类别×协议算最早可删除日；源未到期/冻结时派生物不得先销毁 | `retention_policy` + `RetentionService.recalculate`（祖先截止日传播） |
| 算法包/校准证据至少保留到最后依赖结果期限届满 | 同上，对 `SOFTWARE_PACKAGE`/`CALIBRATION` 做下游期限延展 |
| 恢复计划：沿血缘反向求依赖闭包，核对摘要/软件包/目录可用性 | `RestoreService.createPlan` |
| 4 小时 RTO、提前 72h 预约；缺件/超时启动前标记不可满足并列缺项 | 计划状态 `SATISFIABLE/UNSATISFIABLE`，执行阻断返回 409 |
| 只迁移结果无算法包，哈希通过也不可宣称复现 | 闭包缺 `SOFTWARE_PACKAGE` 即判缺件 |
| 两路线：预约分层 DAG 预取 vs 可校验恢复包（4h RTO 层） | `SCHEDULED_TIERING` / `RESTORE_PACKAGE` 双策略 + 盈亏平衡频次 |
| 成本账：日字节时长×层费率、取回费、恢复计算费 | `cost_daily`、`retrieval_event`、`CostService` |
| 独占归项目、共享按冻结引用/使用份额分摊，分摊总额=月账单 | `object_share` + `monthly_bill.allocation_check` 核对 |
| 归档快照：目录水位+依赖+费率版本，可回放迁移/保留/计费理由 | `archive_snapshot` + 「归档快照回放」界面 |
| 项目退出后共享对象仍被引用不得整体删除 | `ProjectService.withdraw` → `BLOCKED_SHARED_REFERENCE` |
| 校验失败/规则变化/争议冻结传播到所有依赖结果并保留原归档决定 | `governance_event` + 正向影响传播；快照不可变 |
| **研究队列：患者为不可拆分组，相邻复扫不得跨训练/验证** | `cohort_patient`/`cohort_scan` + `cohort_assignment`（`UNIQUE(run_id,patient_uid)`）+ `CohortService.validateRun` 扫描整组性核对 |
| **候选目录：疾病等级、设备/序列特征、研究许可、排除原因** | `cohort_patient`（grade/device/protocol JSONB、consent_status/scope、catalog_exclusion_reason），120 患者/1600 扫描确定性种子 |
| **约束编译器：分区患者数 + 类别/设备/协议覆盖 → 硬约束/软目标** | `com.lakegov.cohort.ConstraintCompiler`（min 硬下限、target/weight 软目标，并提前预警必不可行约束） |
| **路线 A：一次性带约束整数分配，优先全局覆盖** | `GlobalAssignmentSolver`（0-1 分配建模 + 稀缺优先构造 + 全邻域整患者交换/移动寻优 + 确定性多起点） |
| **路线 B：患者组流式分层 + 末轮交换修复，低内存、可增量** | `StreamingStratifiedSolver`（`RowCallbackHandler` 逐组读取、紧凑计数、末轮 `AssignmentOptimizer` 修复；`solveIncremental` 沿父账本） |
| **抽取账：种子、输入水位、拒绝原因** | `cohort_run`（seed/input_watermark/watermark_json/spec/compiled/stats）+ `cohort_rejection` |
| **校验器：跨分区、配额偏差、覆盖缺口、许可范围** | `cohort_validation.report_json`；`GET /api/cohort/runs/{id}` 回放，界面「研究队列抽取」页 |
| **罕见协议仅 8 名患者（7 名许可合格），覆盖不足报缺口而非挪患者** | 画像 `RARE_COVERAGE_GAP`（每分区硬下限 4 → 合计 8 > 合格池 7）；状态 `COVERAGE_GAP` + `coverageGaps`，泄漏/整组性仍全部通过 |

## 2. 目录结构

```
.
├── backend/            Spring Boot 3（Java 17 字节码，容器内 JDK 21/25 可运行）
│   ├── src/main/resources/db/schema.sql   数据库结构（含 cohort_* 队列抽取 7 张表）
│   ├── src/main/resources/db/data.sql     研究A/B 种子数据（幂等）
│   └── src/main/java/com/lakegov/
│       ├── cohort/      队列抽取内核：约束编译器、全局/流式求解器、交换修复、确定性 RNG
│       ├── config/CohortDataSeeder.java   120 患者/1600 扫描/2 设备/8 人罕见协议样例
│       ├── service/CohortService.java     输入水位/抽取账/双路线编排/独立校验器
│       └── web/CohortController.java      /api/cohort/** REST
├── frontend/           Vue 3 + Vite 八界面（新增 CohortView + CohortRunDetail）
├── scripts/verify_e2e.py  端到端业务验证脚本（仅标准库，111 项断言）
└── docker-compose.yml  一键启动 postgres + backend + frontend
```

## 3. 一键启动（Docker Compose）

```bash
docker compose up --build
# 前端 http://localhost:8088
# 后端 http://localhost:8080
# PostgreSQL localhost:5432 （库/用户/密码 ccdev/ccdev/ccdev）
```

容器首次启动自动建表并写入研究 A/B 种子数据、重算保留截止日。

## 4. 本地开发启动（容器内预装运行时）

前置：PostgreSQL 已在 127.0.0.1:5432 运行，库/用户/密码 `ccdev`。

```bash
# 后端（JDK 25 + Maven）
cd backend
mvn -DskipTests spring-boot:run          # http://127.0.0.1:8080，自动建表+种子

# 前端（Node 22）
cd frontend
npm install --cache ./.npm-cache         # 宿主 npm 缓存权限受限时使用本地缓存
npm run dev                              # http://127.0.0.1:5173 （/api 代理到 8080）
```

## 5. 测试账号

| 用户名 | 密码 | 角色 |
| --- | --- | --- |
| admin | admin123 | 平台管理员（全部操作） |
| reviewer | review123 | 项目审阅人 |

账号仅用于隔离开发/测试，不是生产配置。

## 6. 验证方式

### 6.1 自动化端到端验证（推荐）

后端启动后执行：

```bash
python3 scripts/verify_e2e.py
```

脚本覆盖 **111 项断言**（基线治理 51 + 研究队列 60），包括：

1. 研究 A 数据量（原始 2TB、标注 80GB、校准 12GB、掩膜 200GB、特征 5GB、结果 1GB）与 sha256 摘要；
2. `RESULT-A01` 反向依赖闭包 = 7 个对象（含校准与算法包），深度正确；
3. 原始影像保留 10 年（2036-01-15）；算法包/校准延展到最后依赖结果期限；
4. 争议冻结、校验失败沿血缘传播到全部下游结果；
5. 年 1 次预约计划：72h 提前量、3h 预计恢复 ≤ 4h RTO、闭包 7 对象；
6. 月账单存储费手工口径核对、共享份额（掩膜 70/30、算法包 60/40）、`allocationCheck=true`；
7. 执行恢复后取回费/恢复计算费入账；
8. **年 1 次时预约分层更省；月 1 次时恢复包更省**，输出盈亏平衡频次（约 3.83 次/年）；
9. 故意移除校准文件 → 计划 `UNSATISFIABLE`、执行 409 阻断、恢复包校验失败；恢复后复原；
10. 移除算法包 → 即使哈希通过也判定不可复现；
11. 研究 A 退出 → 共享掩膜/算法包被研究 B 引用保护，不得整体删除；
12. 归档快照固定目录水位/9 条血缘边/费率版本，并可回放全部治理决定；
13. **研究队列抽取（60 项断言）**：样例精确为 120 患者 / 1,600 扫描 / 2 设备 / 8 名罕见协议患者（7 名许可合格）；
    1. 双路线在默认画像下均通过校验：113 名合格患者全入组、配额在硬区间、各疾病等级/设备/协议最低覆盖满足；
    2. 同种子两次抽取分配完全一致（可复现），账本保存种子与输入水位；
    3. 5 名许可过期 + 2 名 QC 排除患者全部写入拒绝账且不进入分配；Python 侧逐患者复核扫描数与整组性；
    4. 罕见协议极限画像（每分区硬下限 4，合计需 8 > 合格池 7）：两路线均返回 `COVERAGE_GAP` 与显式缺口（一分区 4、另一分区 3、缺 1），同时跨分区泄漏=0、扫描拆分=0、许可违规=0，且只动用 7 名合格罕见协议患者——**报告缺口，不靠从验证集挪患者/拆扫描补数**；
    5. 流式增量：新增 1 名 CP-TMP 患者后增量轮 `delta=1` 且完成分配并通过硬约束；目录无变化时二次增量 `delta=0` 且分配与上一账本逐患者一致；
    6. REVIEWER 调用抽取写接口返回 403；账本列表/详情可回放规格、编译结果、校验报告与 113 条分配。

### 6.2 界面手工验证路径

1. 登录后进入「恢复计划与校验」，点击「移除/恢复校准文件 CAL-A01」→「创建计划并检查」，观察红色 `UNSATISFIABLE` 与缺项清单，执行按钮禁用；
2. 恢复校准后创建 `RESTORE_PACKAGE` 路线、年频次 12 的计划，观察可满足；
3. 「成本账与分摊」生成 2026-11 账单，观察两项目费用、共享份额标签与分摊核对标签；对比表展示年 1 次/季 1 次/月 1 次的两路线费用；
4. 「保留策略与传播」对 `RAW-IMG-A01` 点「争议冻结」，观察下游 `RESULT-A01` 同步变为冻结阻断；
5. 「项目审阅」对 PROJ-A 点「项目退出」，观察共享对象保护清单；
6. 「归档快照回放」生成快照并切换页签回放当时的保留/冻结/迁移/退出决定；
7. 「研究队列抽取」：
   - ① 总览/水位查看 120/1600/2/8 等计数、疾病/设备/协议分布与 SHA-256 水位；
   - ② 约束与抽取：选 `DEFAULT_80_20` 画像 →「编译约束」查看硬区间/软目标 → 分别用路线 A/B 运行，查看校验四要件、配额条与覆盖矩阵（全绿）；再选 `RARE_COVERAGE_GAP` 运行，观察红色缺口（罕见协议一分区缺 1）与“无泄漏/整组/许可”仍全绿；勾选「增量模式」并选历史流式账本可做增量；
   - ③ 抽取账本回放任意历史运行；④ 候选目录按许可状态筛选、查看任一患者的全部复扫，或新增/删除 `CP-TMP-` 临时患者。

## 7. 计费口径与样例数值（费率版本 RV-2026Q4）

- 计量：1 GB = 10⁹ 字节；月按 30 天；日存储费 = GB × 层 GB·月费率 / 30。
- 研究 A 完整闭包单次按需恢复（年 1 次场景）：取回费约 **¥12.16** + 恢复计算费约 **¥4.98** = **¥17.14/次**。
- 30GB 恢复包驻留 RESTORE_TIER（¥0.18/GB·月）：月费 **¥5.40**，年费 **¥64.80**。
- 盈亏平衡：当年频次 > 约 **3.83 次/年** 时，恢复包路线的额外占用成本低于重复取回成本；即题设年 1 次预约取回更省，改为月 1 次后恢复包更省。
- 全部费率集中在 `storage_tier` 表，调整后重新生成账单即可，快照会固化当时费率版本。

## 8. 可复现研究队列抽取器（本题新增能力）

### 8.1 候选目录与纳入规则

- `cohort_patient`：患者级候选，字段含疾病等级 `disease_grade(0..3)`、主设备与 `device_features`、`protocol_features`（序列/协议集合）、扫描数、`consent_status(ACTIVE/EXPIRED/REVOKED)`、`consent_scope`（许可可用分区，如仅 `["VAL"]`）、`catalog_exclusion_reason`（如 `QC_MOTION`）。
- `cohort_scan`：1,600 次扫描，带设备、协议、场次、`is_repeat_rescan` 与采集时间；扫描通过 `patient_uid` 归组到患者。
- **纳入规则（患者级）**：`consent_status='ACTIVE' AND catalog_exclusion_reason IS NULL`。样例 120 人中 5 人许可过期、2 人 QC 排除（互不重叠），合格 **113** 人；另有 3 人许可范围仅 VAL。
- 样例由 `CohortDataSeeder` 以固定生成种子 `20261125` 确定性生成：**恰好 120 患者 / 1,600 扫描 / 2 设备（`SKYRA_3T`、`DISCOVERY_MR750`）/ 仅 8 名患者携带罕见协议 `RARE_PERF_FMRI`**，其中 `CP-044` 许可过期，故合格罕见协议患者为 **7**。

### 8.2 约束编译器：硬约束 + 软目标

`POST /api/cohort/constraints/compile`（或运行时随账本固化 `compiled_json`）把声明式规格编译为按分区对齐的数组：

- **硬约束**：每名患者恰好进入一个其许可允许的分区；分区患者数 `[minPatients,maxPatients]`；每条覆盖规则的每分区 `min`（疾病等级/设备/协议）。
- **软目标**：分区目标人数（`targetRatio×合格数`）、覆盖 `target`（默认按分区比例分摊特征池，也可显式给 `target/targetShare`）与 `weight`，进入平方偏差目标函数。
- **可行性预警**：某覆盖规则各分区 `min` 之和 > 该特征合格池，或人数上下界合计与合格总数矛盾时，编译器直接给出必不可行预警（罕见协议画像即触发）。

内置画像：`DEFAULT_80_20`（TRAIN 0.8 容差 5、VAL 0.2 容差 4；每个疾病等级每分区 ≥4、每设备 ≥5、罕见协议 ≥2）与 `RARE_COVERAGE_GAP`（仅把罕见协议下限提高到每分区 4）。

### 8.3 两条求解路线（都保证患者不跨分区与可行的最低覆盖）

| 维度 | A · `GLOBAL_ASSIGN` 一次性全局整数分配 | B · `STREAM_STRATIFIED` 流式分层 + 末轮交换修复 |
| --- | --- | --- |
| 内存模型 | 全量患者组 + `患者×规则` 成员矩阵 | `RowCallbackHandler` 逐行读取，仅留紧凑计数/位矩阵；扫描行驻留恒为 0 |
| 算法 | 稀缺特征优先的硬覆盖构造 → 全邻域「整患者移动 + 跨分区整患者交换」最佳改进爬山 → 确定性多起点（8 组，含无抖动起点）取最优 | 每到一个患者按「硬缺口 ≫ 配额 ≫ 软目标」在线选层；末轮共用同一 `AssignmentOptimizer` 做交换/移动修复，必要时确定性扰动逃逸 |
| 最优性/成本 | 更接近全局覆盖最优；邻域 O(N²)，组合规模大、目录变更需整体重算 | 常驻内存低、可增量；单遍分层 + 局部修复，`solverStats.globalOptimumGuaranteed=false` 如实标注不保证全局最优 |
| 增量 | 不支持（整体重算） | `incrementalFromRunId` 沿用父账本分配，仅对新患者分层后统一修复；无变更时二次运行 `delta=0` 且分配逐患者不变 |

两条路线的任何邻域动作都只移动/交换**整患者组**，结构上不存在拆扫描的操作。确定性来自固定的 SplitMix64（`CohortRng`）与固定遍历/排序顺序：相同种子 + 相同水位 → 相同分配（已被 JUnit 与 e2e 双重断言）。

### 8.4 抽取账、输入水位与校验器

- 每次运行写 `cohort_run`：`seed`、`input_watermark`（形如 `cohort-wm/v1:p120:s1600:<sha256前16位>`）、完整 `watermark_json`（全量规范内容 SHA-256、最大 `source_updated_at`）、原始 `spec_json`、编译结果、目标分、求解统计、增量父账本；拒绝原因写 `cohort_rejection`；分配写 `cohort_assignment`，`UNIQUE(run_id,patient_uid)` 在库层杜绝同一患者多行。
- `CohortService.validateRun` **不采信求解器自报**，直接对库内分配执行 SQL/统计核对并写 `cohort_validation`：
  1. `patientLeakage`：`GROUP BY patient_uid HAVING count(DISTINCT split_code)>1`；
  2. `scanWholeness`：扫描 join 分配后同患者出现多分区即违规，并核对分配 `scan_count` 与目录扫描数一致；
  3. `consentScope`：已分配患者必须 `ACTIVE` 且 `jsonb_exists(consent_scope, split_code)`；
  4. `quota`：实际人数对目标/硬区间的偏差；
  5. `coverage/coverageGaps`：从库内分配重算每个维度×分区实际值，对照 min/target 列缺口。
- 全部硬条件满足 → `passed=true,status=OK`；否则 `status=COVERAGE_GAP` 并保留分配与缺口。

### 8.5 罕见协议场景的正确行为（题目关键核验）

`RARE_COVERAGE_GAP` 要求 TRAIN、VAL 各至少 4 名罕见协议患者，合计需 8 人，但合格池只有 7 人（第 8 人 `CP-044` 许可过期）。两路线的实测结果都是一个分区 4 人、另一分区 3 人，缺口 1：

- 返回 `status=COVERAGE_GAP` 与 `coverageGaps`（指明维度/取值/分区/要求/实际/缺额），编译器同时给出必不可行预警；
- `patientLeakage.leakageFree=true`、`scanWholeness.allScansWholeGroup=true`、`consentScope.consentScopeOk=true` 始终成立；
- 罕见协议分配恰好 7 人，**不会**为补齐训练侧下限而把验证集患者的扫描/复扫拆到训练侧（那会造成患者泄漏），也不会放行许可过期患者。缺口只能靠扩充候选、放宽下限或调整分区比例解决。

### 8.6 REST 一览（均在 `/api/cohort`，写操作仅 ADMIN）

| 方法/路径 | 说明 |
| --- | --- |
| `GET /overview` | 120/1600/2/8/7 等总览、分布、当前水位 |
| `GET /patients?eligibility=&q=` / `GET /scans?patientUid=` | 候选目录与逐患者复扫 |
| `GET /profiles` | 约束画像（含 spec JSON） |
| `POST /constraints/compile` | 约束编译器试算（硬/软 + 可行性预警） |
| `POST /runs` | 运行抽取：`{strategy,seed,profileCode,spec,incrementalFromRunId}`，落账并返回详情+校验 |
| `GET /runs` / `GET /runs/{id}` | 账本列表 / 完整回放（规格、编译、拒绝、分配、校验） |
| `POST /candidates` / `DELETE /candidates/{uid}` | 新增/删除 `CP-TMP-` 临时患者（增量演示与测试自清理） |

## 9. 实际验证结论（2026-11-25 执行）

- 后端 `mvn -DskipTests package` 成功产出 `target/lakegov-backend-1.0.0.jar`；全量 `mvn test` **15/15 通过**（基线 `LakeGovIntegrationTest` 8 项 + 新增 `CohortExtractionIntegrationTest` 7 项；队列测试覆盖双路线 ×5 个种子的硬约束、同种子复现、拒绝账、扫描整组、罕见缺口、增量稳定性与 403）；
- 用打包 jar 实启服务（JDK 25）后执行 `python3 scripts/verify_e2e.py`，**111 项断言全部通过、0 失败**（基线治理 51 + 研究队列 60），完整输出见 `docs/verify_e2e_output.txt`；
- 前端 `npm install`（npmmirror 源）与 `npm run build` 成功（含 `CohortView`/`CohortRunDetail` chunk），`npm run preview` 静态服务 **HTTP 200**；
- RBAC：reviewer 只读账号 GET 返回 200、抽取等写操作返回 403；admin 全部操作可用；
- 队列关键实测：默认画像下两路线均 `validation.passed=true`（TRAIN≈90/VAL≈23，硬区间内）；极限画像两路线均稳定给出罕见协议缺口 1（一分区 4、另一分区 3）且泄漏/整组/许可检查全绿；增量 `delta=1 → 0` 且无变更时分配逐患者一致；
- 关键计费测算实测（基线能力未回归）：单次按需恢复 **¥17.145**（取回 12.165 + 计算 4.98）、恢复包月费 **¥5.40**、盈亏平衡 **3.83 次/年**；年 1 次预约分层更省（¥17.15 vs ¥65.01），月 1 次恢复包更省（¥67.32 vs ¥205.74）；
- 本容器无 docker CLI/守护进程，Dockerfile/Compose 已交付但未在本机构建；已用预装 JDK 25 / Maven 3.9 / Node 22 / PostgreSQL 18 完成等价的构建、测试与端到端验证。
