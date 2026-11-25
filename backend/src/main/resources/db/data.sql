-- ============================================================
-- 种子数据（幂等，可重复启动）
-- 计费口径：1 GB = 10^9 字节（云存储十进制惯例），月按 30 天
-- ============================================================

-- ---------- 项目 ----------
INSERT INTO project (code, name, status) VALUES
  ('PROJ-A', '研究A：影像-掩膜-特征-论文结果复现链路', 'ACTIVE'),
  ('PROJ-B', '研究B：跨项目共享掩膜与算法包', 'ACTIVE')
ON CONFLICT (code) DO NOTHING;

-- ---------- 存储层（费率版本 RV-2026Q4） ----------
INSERT INTO storage_tier (code, name, gb_month_rate, retrieval_gb_rate, restore_compute_gb_rate,
                          retrieval_hours, rto_four_hour_capable, rate_version, description) VALUES
  ('HOT',         '热存层（在线）',     0.900, 0.010, 0.005, 0.50, true,  'RV-2026Q4', '高频访问，毫秒~分钟取回'),
  ('WARM',        '温存层（低频）',     0.450, 0.012, 0.006, 1.00, true,  'RV-2026Q4', '低频访问，小时级取回'),
  ('COLD',        '冷存层（归档预备）', 0.120, 0.005, 0.002, 3.00, true,  'RV-2026Q4', '大容量原始数据，3h 取回，满足4h目标'),
  ('ARCHIVE',     '深度归档层',         0.035, 0.030, 0.010, 8.00, false, 'RV-2026Q4', '最便宜但取回需8h，不满足4h RTO'),
  ('RESTORE_TIER','可校验恢复包层',     0.180, 0.005, 0.002, 0.50, true,  'RV-2026Q4', '固定恢复包专层，4h RTO 内可校验恢复')
ON CONFLICT (code) DO NOTHING;

-- ---------- 保留策略（类别 × 研究协议） ----------
INSERT INTO retention_policy (category, protocol_code, retention_years, rule_text) VALUES
  ('RAW_IMAGE',       'PROTO-A', 10.00, '原始扫描法定保留10年'),
  ('ANNOTATION',      'PROTO-A', 10.00, '标注与原始影像同期限保留'),
  ('CALIBRATION',     'PROTO-A', 10.00, '校准证据基础保留10年，且至少保留至最后一个依赖结果期限届满'),
  ('DERIVED_MASK',    'PROTO-A',  8.00, '派生掩膜保留8年；源对象未到期或冻结时不得先销毁'),
  ('FEATURE',         'PROTO-A',  8.00, '特征保留8年，依赖掩膜与算法版本'),
  ('ANALYSIS_RESULT', 'PROTO-A',  7.00, '论文分析结果保留7年'),
  ('SOFTWARE_PACKAGE','PROTO-A',  5.00, '算法包基础保留5年，且至少保留至最后一个依赖结果期限届满'),
  ('RESTORE_PACKAGE', 'PROTO-A',  7.00, '恢复包与目标结果同期限'),
  ('RAW_IMAGE',       'PROTO-B',  7.00, '研究B原始影像保留7年'),
  ('FEATURE',         'PROTO-B',  6.00, '研究B特征保留6年')
ON CONFLICT (category, protocol_code) DO NOTHING;

-- ---------- 对象目录（1 GB = 10^9 字节） ----------
INSERT INTO data_object (object_uid, name, category, byte_size, checksum, checksum_status,
    tier_code, owning_project_id, created_at, last_accessed_at, software_version, recipe, note)
SELECT * FROM (VALUES
  ('RAW-IMG-A01',  '研究A 原始扫描集',     'RAW_IMAGE',       2000000000000,
   'a11dfc3e7b9440f68a2d2c9e0f51b7d34c8e9a02f6d1b7c4e9f20a8d6c3b5e11', 'OK',
   'COLD', (SELECT id FROM project WHERE code='PROJ-A'),
   TIMESTAMP '2026-01-15 09:00:00', TIMESTAMP '2026-10-02 11:30:00', NULL, NULL,
   '2TB 原始影像，法定保留10年'),
  ('ANN-A01',      '研究A 专家标注',       'ANNOTATION',         80000000000,
   'b22ea7d41c8550f79b3e3d0f1a62c8e45d9f0b13a7e2c8d5f0a31b9e7d4c6f22', 'OK',
   'WARM', (SELECT id FROM project WHERE code='PROJ-A'),
   TIMESTAMP '2026-01-20 10:00:00', TIMESTAMP '2026-09-18 15:10:00', NULL, NULL,
   '80GB 标注'),
  ('CAL-A01',      '研究A 校准证据文件',   'CALIBRATION',        12000000000,
   'c33fb8e52d9661f80c4f4e1a2b73d9f56e0a1c24b8f3d9e6a1b42c0f8e5d7a33', 'OK',
   'WARM', (SELECT id FROM project WHERE code='PROJ-A'),
   TIMESTAMP '2026-01-20 10:05:00', TIMESTAMP '2026-08-11 09:00:00', NULL, NULL,
   '12GB 校准文件，复算必需证据'),
  ('ALGO-PKG-01',  '分割/特征算法包',      'SOFTWARE_PACKAGE',    2000000000,
   'd440c9f63ea772f91d5a5f2b3c84e0a67f1b2d35c9a4e0f7b2c53d1a9f6e8b44', 'OK',
   'WARM', (SELECT id FROM project WHERE code='PROJ-A'),
   TIMESTAMP '2026-01-10 08:00:00', TIMESTAMP '2026-10-20 14:00:00', 'segfeat-v3.2.1',
   'conda env lock + 容器镜像 digest + 源码 tag segfeat-v3.2.1',
   '跨项目共享算法包；缺它则哈希通过也不可宣称复现'),
  ('MASK-A01',     '研究A 派生掩膜',       'DERIVED_MASK',      200000000000,
   'e551d0a74fb883f02e6b6a3c4d95f1b78a2c3e46d0b5f1a8c3d64e2b0a7f9c55', 'OK',
   'COLD', (SELECT id FROM project WHERE code='PROJ-A'),
   TIMESTAMP '2026-02-01 12:00:00', TIMESTAMP '2026-07-30 16:45:00', 'segfeat-v3.2.1',
   'recipe#mask-v3：输入 RAW+ANN+CAL，参数 tile=512,thr=0.47',
   '200GB 掩膜，依赖原始影像/标注/校准；与研究B共享'),
  ('FEAT-A01',     '研究A 特征矩阵',       'FEATURE',             5000000000,
   'f662e1b85ac994f13f7c7b4d5e0a6a2c89b3d4f57e1c6a2b9d4e7f5c3b1a8d66', 'OK',
   'COLD', (SELECT id FROM project WHERE code='PROJ-A'),
   TIMESTAMP '2026-02-05 13:00:00', TIMESTAMP '2026-09-01 10:20:00', 'segfeat-v3.2.1',
   'recipe#feat-v2：输入 MASK，算法 segfeat-v3.2.1，参数 dim=2048',
   '5GB 特征，依赖掩膜与算法版本'),
  ('RESULT-A01',   '研究A 论文分析结果',   'ANALYSIS_RESULT',     1000000000,
   'a773f2c96bd005f24a8d8c5e6f1b7b3d90c4e5a68f2d7b3c0e5f8a6d4c2b9e77', 'OK',
   'WARM', (SELECT id FROM project WHERE code='PROJ-A'),
   TIMESTAMP '2026-02-10 17:00:00', TIMESTAMP '2026-11-10 11:00:00', 'analysis-v1.8.0',
   'recipe#paper-v1：输入 FEAT，统计模型 analysis-v1.8.0',
   '1GB 论文结果，年度全量重分析的恢复目标'),
  ('PKG-RESULT-A01','RESULT-A01 可校验恢复包','RESTORE_PACKAGE',  30000000000,
   'b884a3da7ce116a35b9e9d6f7a2c8c4e01d5f6b79a3e8c4d1f6a9be7d5c3af88', 'OK',
   'RESTORE_TIER', (SELECT id FROM project WHERE code='PROJ-A'),
   TIMESTAMP '2026-03-01 09:00:00', TIMESTAMP '2026-11-01 09:00:00', 'segfeat-v3.2.1',
   '恢复包清单=RESULT-A01 完整依赖闭包（含校准与算法包），清单哈希随包封存',
   '30GB 固化恢复包，4h RTO 层'),
  ('RAW-IMG-B01',  '研究B 原始扫描集',     'RAW_IMAGE',         100000000000,
   'c995b4eb8df227b46c0f0e7a8b3d9d5f12e6a7c80b4f9d2e3a4b5c6d7e8f0a99', 'OK',
   'COLD', (SELECT id FROM project WHERE code='PROJ-B'),
   TIMESTAMP '2026-03-12 09:30:00', TIMESTAMP '2026-10-05 13:30:00', NULL, NULL,
   '100GB 研究B原始影像'),
  ('FEAT-B01',     '研究B 特征矩阵（共享A的掩膜/算法）','FEATURE', 3000000000,
   'da06c5fc9ea338c57d1a1f8b9c4e0e6a23f7b8d91c5a0e3f4b5c6d7e8f0a1b00', 'OK',
   'COLD', (SELECT id FROM project WHERE code='PROJ-B'),
   TIMESTAMP '2026-03-20 15:00:00', TIMESTAMP '2026-10-22 10:00:00', 'segfeat-v3.2.1',
   'recipe#feat-v2：输入 RAW-B + 共享 MASK-A01 + 共享 ALGO-PKG-01',
   '3GB，引用研究A的共享对象')
) v
WHERE NOT EXISTS (SELECT 1 FROM data_object o WHERE o.object_uid = v.column1);

-- ---------- 血缘 DAG（from=输入 → to=输出） ----------
INSERT INTO lineage_edge (from_object_id, to_object_id, edge_version, recipe, software_version, shared_reference)
SELECT f.id, t.id, x.edge_version, x.recipe, x.software_version, x.shared_reference
FROM (VALUES
  ('RAW-IMG-A01','MASK-A01','v1','recipe#mask-v3','segfeat-v3.2.1', false),
  ('ANN-A01',    'MASK-A01','v1','recipe#mask-v3','segfeat-v3.2.1', false),
  ('CAL-A01',    'MASK-A01','v1','recipe#mask-v3','segfeat-v3.2.1', false),
  ('MASK-A01',   'FEAT-A01','v1','recipe#feat-v2','segfeat-v3.2.1', false),
  ('ALGO-PKG-01','FEAT-A01','v1','recipe#feat-v2','segfeat-v3.2.1', false),
  ('FEAT-A01',   'RESULT-A01','v1','recipe#paper-v1','analysis-v1.8.0', false),
  ('RAW-IMG-B01','FEAT-B01','v1','recipe#feat-v2','segfeat-v3.2.1', false),
  ('ALGO-PKG-01','FEAT-B01','v1','recipe#feat-v2','segfeat-v3.2.1', true),
  ('MASK-A01',   'FEAT-B01','v1','recipe#feat-v2','segfeat-v3.2.1', true)
) AS x(ff, tt, edge_version, recipe, software_version, shared_reference)
JOIN data_object f ON f.object_uid = x.ff
JOIN data_object t ON t.object_uid = x.tt
WHERE NOT EXISTS (
  SELECT 1 FROM lineage_edge e WHERE e.from_object_id=f.id AND e.to_object_id=t.id);

-- ---------- 共享引用份额（冻结） ----------
INSERT INTO object_share (object_id, project_id, frozen_share, share_basis)
SELECT o.id, p.id, v.share, v.basis
FROM (VALUES
  ('MASK-A01','PROJ-A',0.70,'REFERENCE'),
  ('MASK-A01','PROJ-B',0.30,'REFERENCE'),
  ('ALGO-PKG-01','PROJ-A',0.60,'USAGE'),
  ('ALGO-PKG-01','PROJ-B',0.40,'USAGE')
) AS v(uid, pcode, share, basis)
JOIN data_object o ON o.object_uid = v.uid
JOIN project p ON p.code = v.pcode
WHERE NOT EXISTS (
  SELECT 1 FROM object_share s WHERE s.object_id=o.id AND s.project_id=p.id);

-- ---------- 恢复包登记 ----------
UPDATE data_object SET is_restore_package = true, package_target_uid = 'RESULT-A01'
WHERE object_uid = 'PKG-RESULT-A01';
INSERT INTO restore_package (target_object_id, package_object_id, project_id, validated, validation_detail)
SELECT t.id, k.id, t.owning_project_id, true,
  '{"manifest":["RAW-IMG-A01","ANN-A01","CAL-A01","ALGO-PKG-01","MASK-A01","FEAT-A01","RESULT-A01"],"checks":"all present, sha256 verified, software segfeat-v3.2.1 locked"}'::jsonb
FROM data_object t, data_object k
WHERE t.object_uid='RESULT-A01' AND k.object_uid='PKG-RESULT-A01'
  AND NOT EXISTS (SELECT 1 FROM restore_package rp WHERE rp.package_object_id = k.id);
