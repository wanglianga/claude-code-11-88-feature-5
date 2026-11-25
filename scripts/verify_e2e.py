#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
科研影像湖仓存储治理平台 —— 端到端业务验证脚本（仅标准库）。
覆盖题目全部硬性场景：
  1. 对象目录与研究A数据量
  2. 血缘反向依赖闭包
  3. 保留策略：10年原始影像、算法包/校准延展、冻结/校验失败传播
  4. 恢复计划：72h预约、4h RTO、缺件（移除校准）阻断、恢复包校验
  5. 成本：GB月费、取回费、恢复计算、共享分摊总额=月账单
  6. 两路线盈亏平衡（年1次 vs 月1次）
  7. 项目退出共享对象保护
  8. 归档快照回放
  9. 可复现研究队列抽取：患者成组不跨分区、双路线硬约束、种子复现、
     许可失效排除、拒绝账/水位、扫描整组性、罕见协议缺口报告（不靠挪患者补覆盖）、
     流式增量稳定性与 REVIEWER 403
用法: python3 verify_e2e.py [base_url]
"""
import json
import sys
import urllib.request
import urllib.error

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080") + "/api"
PASS, FAIL = [], []


def call(method, path, body=None, token=None, allow_fail=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        payload = e.read().decode()
        if allow_fail:
            return {"__http_status__": e.code, "__body__": payload}
        raise AssertionError(f"{method} {path} -> {e.code}: {payload}")


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(f"[{'PASS' if cond else 'FAIL'}] {name} {detail}")


def money(x):
    return round(float(x), 2)


# ---------- 登录 ----------
tok = call("POST", "/auth/login", {"username": "admin", "password": "admin123"})["token"]
H = tok
check("登录获取令牌", bool(tok))

# ---------- 1. 对象目录 ----------
objs = {o["uid"]: o for o in call("GET", "/catalog/objects", token=H)}
check("目录对象数=10", len(objs) == 10, f"actual={len(objs)}")
expect_sizes = {
    "RAW-IMG-A01": 2_000_000_000_000, "ANN-A01": 80_000_000_000,
    "CAL-A01": 12_000_000_000, "MASK-A01": 200_000_000_000,
    "FEAT-A01": 5_000_000_000, "RESULT-A01": 1_000_000_000,
}
for uid, size in expect_sizes.items():
    check(f"对象 {uid} 字节数={size/1e9:.0f}GB", objs[uid]["byteSize"] == size)
check("每个对象有 sha256 校验摘要", all(len(o["checksum"]) == 64 for o in objs.values()))

# ---------- 2. 依赖闭包 ----------
closure = call("GET", "/retention/closure/RESULT-A01", token=H)
cuids = {c["uid"] for c in closure}
expect_closure = {"RESULT-A01", "FEAT-A01", "MASK-A01", "RAW-IMG-A01",
                  "ANN-A01", "CAL-A01", "ALGO-PKG-01"}
check("RESULT-A01 完整依赖闭包=7个对象且含算法包/校准", cuids == expect_closure, str(sorted(cuids)))
depth = {c["uid"]: c["depth"] for c in closure}
check("闭包深度：RESULT=0, FEAT=1, MASK=2, RAW=3",
      depth["RESULT-A01"] == 0 and depth["FEAT-A01"] == 1
      and depth["MASK-A01"] == 2 and depth["RAW-IMG-A01"] == 3, str(depth))

# ---------- 3. 保留策略 ----------
call("POST", "/retention/recalculate", token=H)
ov = {r["uid"]: r for r in call("GET", "/retention/overview", token=H)}
check("原始影像保留10年（2036-01-15）",
      str(ov["RAW-IMG-A01"]["baseDeadline"]) == "2036-01-15", str(ov["RAW-IMG-A01"]["baseDeadline"]))
check("标注与原始同期限10年", str(ov["ANN-A01"]["baseDeadline"]) == "2036-01-20")
# 算法包基础5年(2031)，延展到最后依赖结果期限：FEAT 2034 / MASK 2034
check("算法包至少保留至最后依赖结果期限届满（≥2034）",
      str(ov["ALGO-PKG-01"]["effectiveDeadline"]) >= "2034-02-05",
      str(ov["ALGO-PKG-01"]["effectiveDeadline"]))
check("校准证据同样延展（≥2034）",
      str(ov["CAL-A01"]["effectiveDeadline"]) >= "2034-02-01",
      str(ov["CAL-A01"]["effectiveDeadline"]))
check("派生物不早于源对象到期（MASK 截止≥RAW）",
      ov["MASK-A01"]["effectiveDeadline"] is not None
      and ov["MASK-A01"]["effectiveDeadline"] >= ov["RAW-IMG-A01"]["effectiveDeadline"])

# 争议冻结传播
hold = call("POST", "/retention/hold",
            {"uid": "RAW-IMG-A01", "litigationHold": True, "reason": "e2e冻结测试"}, token=H)
check("冻结传播到全部下游依赖结果",
      set(hold["propagatedTo"]) >= {"RAW-IMG-A01", "MASK-A01", "FEAT-A01", "RESULT-A01"},
      str(hold["propagatedTo"]))
ov2 = {r["uid"]: r for r in call("GET", "/retention/overview", token=H)}
check("冻结对象无截止日（无限期保留）", ov2["RAW-IMG-A01"]["disposition"] == "BLOCKED_HOLD")
check("冻结下游结果同样不可销毁", ov2["RESULT-A01"]["disposition"] == "BLOCKED_HOLD")
call("POST", "/retention/hold",
     {"uid": "RAW-IMG-A01", "litigationHold": False, "reason": "解除冻结"}, token=H)

# 校验失败传播
cf = call("POST", "/retention/checksum-failed",
          {"uid": "CAL-A01", "reason": "e2e注入校验失败"}, token=H)
check("校验失败传播到 MASK/FEAT/RESULT",
      set(cf["propagatedTo"]) >= {"CAL-A01", "MASK-A01", "FEAT-A01", "RESULT-A01"},
      str(cf["propagatedTo"]))
call("POST", "/retention/checksum-ok", {"uid": "CAL-A01"}, token=H)

# ---------- 4. 恢复计划（年1次，提前72h，4h RTO） ----------
plan_y = call("POST", "/restore/plans",
              {"targetUid": "RESULT-A01", "strategy": "SCHEDULED_TIERING",
               "frequencyPerYear": 1}, token=H)
check("年1次预约计划可满足", plan_y["status"] == "SATISFIABLE", str(plan_y.get("missingItems")))
check("预约提前72小时", plan_y["leadHours"] == 72)
check("预计恢复时间≤4小时 RTO", plan_y["estimatedHours"] <= 4,
      f"{plan_y['estimatedHours']}h")
check("闭包对象数=7", plan_y["closureCount"] == 7)
per_run = money(plan_y["perRunRetrievalFee"] + plan_y["perRunComputeFee"])
print(f"    单次恢复：取回费={money(plan_y['perRunRetrievalFee'])} "
      f"恢复计算费={money(plan_y['perRunComputeFee'])} 合计={per_run} 元")

# 72h 预取日程
sched = call("GET", "/restore/prefetch-schedule?targetUid=RESULT-A01", token=H)
check("72h窗口内完成全部预取", all(s["within72hLead"] for s in sched),
      f"累计={sched[-1]['windowEndHour']}h")

# ---------- 5. 月账单与分摊（未发生取回，仅存储） ----------
bill1 = call("POST", "/cost/billing/generate", {"billingMonth": "2026-11"}, token=H)
check("分摊总额等于月账单（allocationCheck）", bill1["allocationCheck"] is True,
      f"总额={bill1['grandTotal']}")
bills_a = [b for b in bill1["bills"] if b["projectCode"] == "PROJ-A"][0]
bills_b = [b for b in bill1["bills"] if b["projectCode"] == "PROJ-B"][0]
print(f"    月存储费：PROJ-A={money(bills_a['storageFee'])} PROJ-B={money(bills_b['storageFee'])} "
      f"合计={money(bill1['grandTotal'])} 元/月")
# 手算研究A独占+共享存储费（GB×费率），含算法包 2GB×0.45（A占60%份额）
manual_exclusive = (2000 * 0.120 + 80 * 0.450 + 12 * 0.450 + 5 * 0.120
                    + 1 * 0.450 + 30 * 0.180)
manual_mask_a = 200 * 0.120 * 0.70          # 掩膜A承担70%
manual_algo_a = 2 * 0.450 * 0.60            # 算法包A承担60%
manual_shared = manual_exclusive + manual_mask_a + manual_algo_a
check("PROJ-A 存储费与手工口径一致（含共享份额扣减）",
      abs(money(bills_a["storageFee"]) - money(manual_shared)) < 0.05,
      f"api={money(bills_a['storageFee'])} manual={money(manual_shared)}")
# 共享份额校验：MASK 30% 应计入 PROJ-B
mask_alloc = [a for a in bill1["objectAllocations"] if a["uid"] == "MASK-A01"][0]
share_b = [x for x in mask_alloc["allocations"] if x["projectId"] == 2]
check("共享掩膜按冻结份额 30% 分摊给 PROJ-B",
      bool(share_b) and abs(float(share_b[0]["share"]) - 0.30) < 1e-6)
# 份额之和=1
for a in bill1["objectAllocations"]:
    if len(a["allocations"]) > 1:
        s = sum(float(x["share"]) for x in a["allocations"])
        check(f"共享对象 {a['uid']} 份额之和=1", abs(s - 1.0) < 1e-9)

# ---------- 6. 执行恢复计划，取回费/恢复计算入账 ----------
exe = call("POST", f"/restore/plans/{plan_y['planId']}/execute", token=H)
check("恢复计划执行成功且在RTO内", exe["status"] == "EXECUTED" and exe["completedWithinRto"])
check("恢复对象数=7", exe["restoredCount"] == 7)
bill2 = call("POST", "/cost/billing/generate", {"billingMonth": "2026-11"}, token=H)
b2a = [b for b in bill2["bills"] if b["projectCode"] == "PROJ-A"][0]
check("取回费已计入月账单", money(b2a["retrievalFee"]) > 0, f"{b2a['retrievalFee']}")
check("恢复计算费已计入月账单", money(b2a["restoreComputeFee"]) > 0, f"{b2a['restoreComputeFee']}")
check("含取回后分摊核对仍通过", bill2["allocationCheck"] is True)
annual_storage = money(bill2["grandTotal"]) * 12
print(f"    年存储费（两项目合计口径）≈{annual_storage} 元；"
      f"研究A单次恢复合计={per_run} 元，年1次取回年成本={per_run} 元")

# ---------- 7. 两路线盈亏平衡 ----------
cmp1 = call("GET", "/cost/strategy-comparison?targetUid=RESULT-A01&frequencies=1", token=H)
sc1 = cmp1["scenarios"][0]
check("年1次：按需预取路线更省",
      sc1["cheaperStrategy"] == "SCHEDULED_TIERING",
      f"按需={sc1['scheduledAnnualFee']} 恢复包={sc1['packageAnnualFee']}")
cmp12 = call("GET", "/cost/strategy-comparison?targetUid=RESULT-A01&frequencies=12", token=H)
sc12 = cmp12["scenarios"][0]
check("月1次：恢复包路线更省（额外占用低于重复取回）",
      sc12["cheaperStrategy"] == "RESTORE_PACKAGE",
      f"按需={sc12['scheduledAnnualFee']} 恢复包={sc12['packageAnnualFee']}")
print(f"    盈亏平衡频次≈{cmp1['breakevenFrequencyPerYear']} 次/年；"
      f"恢复包月费={cmp1['packageMonthlyFee']} 元（30GB×0.18）")
be = float(cmp1["breakevenFrequencyPerYear"])
check("盈亏平衡点落在年1次与月1次之间", 1 < be < 12, f"f*={be}")

# ---------- 8. 故意移除校准文件 → 阻断 ----------
call("POST", "/restore/availability", {"uid": "CAL-A01", "available": False}, token=H)
bad = call("POST", "/restore/plans",
           {"targetUid": "RESULT-A01", "strategy": "SCHEDULED_TIERING",
            "frequencyPerYear": 1}, token=H)
check("缺校准件时计划启动前标记 UNSATISFIABLE", bad["status"] == "UNSATISFIABLE")
missing_uids = {m["uid"] for m in bad["missingItems"]}
check("缺件清单列出 CAL-A01", "CAL-A01" in missing_uids, str(missing_uids))
blocked = call("POST", f"/restore/plans/{bad['planId']}/execute", token=H, allow_fail=True)
check("不可满足计划执行被阻断（HTTP 409）", blocked.get("__http_status__") == 409,
      str(blocked.get("__http_status__")))
# 恢复包校验同样失败
pv = call("POST", "/restore/packages/validate", {"packageUid": "PKG-RESULT-A01"}, token=H)
check("恢复包校验失败并指出校准缺件", pv["valid"] is False and
      any(m["uid"] == "CAL-A01" for m in pv["missingItems"]))
# 恢复后重新可满足
call("POST", "/restore/availability", {"uid": "CAL-A01", "available": True}, token=H)
pv2 = call("POST", "/restore/packages/validate", {"packageUid": "PKG-RESULT-A01"}, token=H)
check("校准恢复后恢复包校验通过", pv2["valid"] is True)
good = call("POST", "/restore/plans",
            {"targetUid": "RESULT-A01", "strategy": "RESTORE_PACKAGE",
             "frequencyPerYear": 12}, token=H)
check("恢复包路线月频计划可满足且≤4h",
      good["status"] == "SATISFIABLE" and good["estimatedHours"] <= 4)

# 算法包缺失场景：仅结果文件迁移，哈希通过也不可复现
call("POST", "/restore/availability", {"uid": "ALGO-PKG-01", "available": False}, token=H)
no_algo = call("POST", "/restore/plans",
               {"targetUid": "RESULT-A01", "strategy": "SCHEDULED_TIERING",
                "frequencyPerYear": 1}, token=H)
check("缺算法包时即使哈希通过也判定不可复现",
      any(m["category"] == "SOFTWARE_PACKAGE" for m in no_algo["missingItems"]))
call("POST", "/restore/availability", {"uid": "ALGO-PKG-01", "available": True}, token=H)

# ---------- 9. 项目退出与共享保护 ----------
# 研究A退出，但其共享掩膜/算法包仍被活跃的研究B引用 → 不得整体删除
wd = call("POST", "/projects/PROJ-A/withdraw", {"reason": "e2e项目退出"}, token=H)
protected = {p["uid"] for p in wd["protectedSharedObjects"]}
check("项目A退出后共享掩膜/算法包被研究B引用保护，不得整体删除",
      {"MASK-A01", "ALGO-PKG-01"} <= protected, str(protected))
check("项目A独占对象标记退出（RAW-IMG-A01）", "RAW-IMG-A01" in set(wd["withdrawnObjects"]))
check("研究B对象不受项目A退出影响", "FEAT-B01" not in set(wd["withdrawnObjects"]))
dc = call("GET", "/retention/deletion-check/MASK-A01", token=H)
check("共享掩膜销毁检查=BLOCKED_SHARED_REFERENCE",
      dc["verdict"] == "BLOCKED_SHARED_REFERENCE", dc["verdict"])
call("POST", "/projects/PROJ-A/reactivate", token=H)

# ---------- 10. 归档快照回放 ----------
snap = call("POST", "/snapshots", token=H)
check("快照固定目录水位/依赖/费率版本",
      snap["catalogObjects"] >= 10 and snap["lineageEdges"] >= 9
      and snap["rateVersion"] == "RV-2026Q4", str(snap))
replay = call("GET", f"/snapshots/{snap['snapshotId']}/replay", token=H)
decisions = replay["decisions"] if isinstance(replay["decisions"], list) else []
kinds = {d.get("kind") for d in decisions if isinstance(d, dict)}
check("快照可回放保留/冻结/迁移/退出等治理决定",
      {"RETENTION", "LITIGATION_HOLD", "CHECKSUM_FAILED", "PROJECT_WITHDRAWN"} <= kinds,
      str(kinds))

# ---------- 11. 可复现研究队列抽取 ----------
print("\n---- 11. 研究队列抽取（患者成组 / 双路线 / 缺口报告 / 增量） ----")

# 11.0 清理历次脚本遗留的 CP-TMP 临时患者，保证 120/1600 基线可重复核验
for p in call("GET", "/cohort/patients?q=CP-TMP", token=H):
    call("DELETE", f"/cohort/candidates/{p['patientUid']}", token=H)

ov = call("GET", "/cohort/overview", token=H)
check("样例=120 名患者", ov["patientCount"] == 120, str(ov["patientCount"]))
check("样例=1,600 次扫描", ov["scanCount"] == 1600, str(ov["scanCount"]))
check("恰好 2 种设备", ov["deviceCount"] == 2, str(ov["deviceCount"]))
check("罕见协议仅 8 名患者", ov["rarePatients"] == 8, str(ov["rarePatients"]))
check("罕见协议合格池=7（1 人许可过期）", ov["rareEligible"] == 7, str(ov["rareEligible"]))
check("许可过期患者=5", ov["consentExpired"] == 5, str(ov["consentExpired"]))
check("目录 QC 排除=2", ov["catalogExcluded"] == 2, str(ov["catalogExcluded"]))
check("许可合格患者=113", ov["eligiblePatients"] == 113, str(ov["eligiblePatients"]))
check("输入水位含计数与 SHA",
      ov["inputWatermark"].startswith("cohort-wm/v1:p120:s1600:")
      and len(ov["watermark"]["catalogSha256"]) == 64, ov["inputWatermark"])

# 约束编译器：默认规格无可行性预警；缺口画像必须提前预警罕见协议必不可行
comp_ok = call("POST", "/cohort/constraints/compile",
               {"profileCode": "DEFAULT_80_20"}, token=H)
check("编译器：默认规格合格患者=113 且无必不可行预警",
      comp_ok["eligiblePatients"] == 113 and not comp_ok["feasibilityWarnings"],
      str(comp_ok["feasibilityWarnings"]))
comp_gap = call("POST", "/cohort/constraints/compile",
                {"profileCode": "RARE_COVERAGE_GAP"}, token=H)
check("编译器：提前预警罕见协议硬下限必不可行",
      any("RARE_PERF_FMRI" in w for w in comp_gap["feasibilityWarnings"]),
      str(comp_gap["feasibilityWarnings"]))

eligible_patients = call("GET", "/cohort/patients?eligibility=eligible", token=H)
expect_assigned_scans = sum(int(p["scanCount"]) for p in eligible_patients)
all_scans = call("GET", "/cohort/scans", token=H)
scans_by_patient = {}
for s in all_scans:
    scans_by_patient[s["patientUid"]] = scans_by_patient.get(s["patientUid"], 0) + 1


def assign_map(run):
    return {a["patientUid"]: a["split"] for a in run["assignments"]}


def assert_run_hard(name, run):
    v = run["validation"]
    check(f"{name}：校验通过（患者数/覆盖硬约束全满足）", v["passed"] is True,
          f"gaps={v['coverageGaps']}")
    check(f"{name}：无患者跨分区泄漏", v["patientLeakage"]["leakageFree"] is True
          and v["patientLeakage"]["crossSplitPatientCount"] == 0)
    check(f"{name}：扫描整组性成立", v["scanWholeness"]["allScansWholeGroup"] is True
          and v["scanWholeness"]["splitViolationCount"] == 0
          and v["scanWholeness"]["scanCountMismatchCount"] == 0)
    check(f"{name}：许可范围全部合法", v["consentScope"]["consentScopeOk"] is True
          and v["consentScope"]["violationCount"] == 0)
    check(f"{name}：配额均在硬区间内", all(q["withinBounds"] for q in v["quota"]),
          str([(q["split"], q["actual"], q["min"], q["max"]) for q in v["quota"]]))
    check(f"{name}：113 名合格患者全部入组", run["assignedPatients"] == 113,
          str(run["assignedPatients"]))
    check(f"{name}：入组扫描数=合格患者扫描总和",
          run["assignedScans"] == expect_assigned_scans,
          f"{run['assignedScans']} vs {expect_assigned_scans}")
    check(f"{name}：账本保存种子与输入水位",
          int(run["seed"]) == 20261125 and run["inputWatermark"] == ov["inputWatermark"])
    # Python 侧独立复核：每名入组患者库内扫描数与分配行 scanCount 一致，且只在一个分区
    amap = assign_map(run)
    check(f"{name}：分配映射无重复患者（结构不跨分区）",
          len(amap) == len(run["assignments"]))
    mismatch = [a["patientUid"] for a in run["assignments"]
                if int(a["scanCount"]) != scans_by_patient.get(a["patientUid"], 0)]
    check(f"{name}：逐患者扫描数与目录一致", not mismatch, str(mismatch[:5]))
    multi = [a for a in run["assignments"] if int(a["scanCount"]) > 1]
    check(f"{name}：存在 100+ 多扫描患者且全部整体分配", len(multi) >= 100, str(len(multi)))


# 11.1 路线 A 全局整数分配 + 种子复现
g1 = call("POST", "/cohort/runs",
          {"strategy": "GLOBAL_ASSIGN", "profileCode": "DEFAULT_80_20", "seed": 20261125}, token=H)
assert_run_hard("路线A全局", g1)
g2 = call("POST", "/cohort/runs",
          {"strategy": "GLOBAL_ASSIGN", "profileCode": "DEFAULT_80_20", "seed": 20261125}, token=H)
check("路线A：相同种子分配完全可复现", assign_map(g1) == assign_map(g2))

# 11.2 路线 B 流式分层 + 末轮交换修复 + 种子复现
s1 = call("POST", "/cohort/runs",
          {"strategy": "STREAM_STRATIFIED", "profileCode": "DEFAULT_80_20", "seed": 20261125}, token=H)
assert_run_hard("路线B流式", s1)
s2 = call("POST", "/cohort/runs",
          {"strategy": "STREAM_STRATIFIED", "profileCode": "DEFAULT_80_20", "seed": 20261125}, token=H)
check("路线B：相同种子分配完全可复现", assign_map(s1) == assign_map(s2))

# 11.3 许可失效/QC 排除：拒绝账与“不得分配”
reason_by_uid = {r["patientUid"]: r["reasonCode"] for r in g1["rejections"]}
for uid in ["CP-013", "CP-044", "CP-061", "CP-097", "CP-115"]:
    check(f"许可过期患者 {uid} 记 CONSENT_EXPIRED",
          reason_by_uid.get(uid) == "CONSENT_EXPIRED", str(reason_by_uid.get(uid)))
check("QC 不合格 CP-026/CP-083 记 QC_MOTION",
      reason_by_uid.get("CP-026") == "QC_MOTION"
      and reason_by_uid.get("CP-083") == "QC_MOTION")
g_assigned = assign_map(g1)
check("全部被拒绝患者均不在分配中",
      all(uid not in g_assigned for uid in reason_by_uid),
      str([u for u in reason_by_uid if u in g_assigned]))

# 11.4 罕见协议覆盖不足：报告缺口，而不是挪患者/拆扫描造成泄漏
for strat in ["GLOBAL_ASSIGN", "STREAM_STRATIFIED"]:
    gr = call("POST", "/cohort/runs",
              {"strategy": strat, "profileCode": "RARE_COVERAGE_GAP", "seed": 7}, token=H)
    gv = gr["validation"]
    rare_gaps = [x for x in gv["coverageGaps"] if x["value"] == "RARE_PERF_FMRI"]
    check(f"[{strat}] 罕见协议缺口被显式报告（≥1）",
          gr["status"] == "COVERAGE_GAP" and gv["passed"] is False
          and bool(rare_gaps) and int(rare_gaps[0]["missing"]) >= 1, str(rare_gaps))
    check(f"[{strat}] 缺口下仍无跨分区泄漏/扫描拆分/许可违规",
          gv["patientLeakage"]["leakageFree"] and gv["scanWholeness"]["allScansWholeGroup"]
          and gv["consentScope"]["consentScopeOk"])
    gamap = assign_map(gr)
    check(f"[{strat}] 分配行数=患者键数（无人同时落入两侧）",
          len(gamap) == len(gr["assignments"]))
    rare_assigned = [a for a in gr["assignments"] if "RARE_PERF_FMRI" in a["protocolFeatures"]]
    check(f"[{strat}] 罕见协议仅动用 7 名合格患者（不越池、不挪验证集扫描）",
          len(rare_assigned) == 7, str(len(rare_assigned)))
    rare_cells = next(c for c in gv["coverage"] if c["value"] == "RARE_PERF_FMRI")["bySplit"]
    check(f"[{strat}] 至少一个分区罕见协议实际数低于硬下限（缺口属实）",
          any(not cell["hardMet"] for cell in rare_cells.values()), str(rare_cells))

# 11.5 流式增量：新增患者只处理增量，无变更时分配稳定
tmp_uid = "CP-TMP-E2E-INC"
try:
    call("DELETE", f"/cohort/candidates/{tmp_uid}", token=H, allow_fail=True)
    base = call("POST", "/cohort/runs",
                {"strategy": "STREAM_STRATIFIED", "profileCode": "DEFAULT_80_20", "seed": 555}, token=H)
    created = call("POST", "/cohort/candidates",
                   {"patientUid": tmp_uid, "diseaseGrade": 3, "rare": True, "scanCount": 6}, token=H)
    check("新增 CP-TMP 候选患者成功", created.get("patientUid") == tmp_uid)
    inc1 = call("POST", "/cohort/runs",
                {"strategy": "STREAM_STRATIFIED", "profileCode": "DEFAULT_80_20",
                 "seed": 555, "incrementalFromRunId": base["runId"]}, token=H)
    check("增量轮识别 ≥1 名新患者并完成分配",
          int(inc1["deltaPatients"]) >= 1 and tmp_uid in assign_map(inc1),
          str(inc1.get("deltaPatients")))
    check("增量轮仍满足全部硬约束", inc1["validation"]["passed"] is True)
    inc2 = call("POST", "/cohort/runs",
                {"strategy": "STREAM_STRATIFIED", "profileCode": "DEFAULT_80_20",
                 "seed": 555, "incrementalFromRunId": inc1["runId"]}, token=H)
    check("无目录变更时二次增量 delta=0", int(inc2["deltaPatients"]) == 0,
          str(inc2.get("deltaPatients")))
    check("无变更增量分配与上一账本完全一致（不抖动）",
          assign_map(inc1) == assign_map(inc2))
finally:
    call("DELETE", f"/cohort/candidates/{tmp_uid}", token=H, allow_fail=True)

# 11.6 账本列表/详情持久化
runs_list = call("GET", "/cohort/runs", token=H)
check("账本列表包含本次抽取记录", len(runs_list) >= 5 and any(r["validationPassed"] for r in runs_list))
detail = call("GET", f"/cohort/runs/{g1['runId']}", token=H)
check("账本详情可回放规格/编译结果/校验报告/分配",
      detail.get("compiled") is not None and detail.get("validation") is not None
      and len(detail.get("assignments", [])) == 113)

# 11.7 REVIEWER 对抽取写操作返回 403
rev_tok = call("POST", "/auth/login",
               {"username": "reviewer", "password": "review123"})["token"]
denied = call("POST", "/cohort/runs", {"strategy": "STREAM_STRATIFIED"},
              token=rev_tok, allow_fail=True)
check("REVIEWER 运行抽取被拒（HTTP 403）", denied.get("__http_status__") == 403,
      str(denied.get("__http_status__")))

# ---------- 汇总 ----------
print("\n================ 验证汇总 ================")
print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
if FAIL:
    print("失败项：")
    for f in FAIL:
        print(" -", f)
    sys.exit(1)
print("全部场景验证通过")
