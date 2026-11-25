<template>
  <div class="detail-wrap">
    <!-- 状态总横幅 -->
    <div class="banner" :class="run.validation && run.validation.passed ? 'banner-ok' : 'banner-gap'">
      <div class="banner-title">
        <span class="tag" :class="strategyClass">{{ strategyLabel(run.strategy) }}</span>
        <b style="font-size:15px">账本 #{{ run.runId }} · {{ run.runUid }}</b>
        <span v-if="run.incremental" class="tag tag-info">增量 ↳ #{{ run.parentRunId }}（新患者 {{ run.deltaPatients }}）</span>
        <span class="tag" :class="run.validationPassed ? 'tag-ok' : 'tag-danger'">
          {{ run.validationPassed ? '校验通过 · 硬约束全部满足' : '存在覆盖/配额缺口 · 已报告而非泄漏补数' }}
        </span>
      </div>
      <div class="banner-meta">
        种子 <span class="mono">{{ run.seed }}</span> ·
        水位 <span class="mono" :title="run.inputWatermark">{{ shortWm }}</span> ·
        耗时 {{ run.solverStats?.solveElapsedMs ?? '—' }} ms ·
        目标分 {{ Number(run.objectiveScore || 0).toFixed(1) }} ·
        {{ fmt(run.createdAt) }}
      </div>
    </div>

    <!-- 关键计数 -->
    <div class="grid grid-4" style="margin:12px 0">
      <div class="stat"><div class="k">候选患者</div><div class="v">{{ run.candidatePatients }}</div></div>
      <div class="stat"><div class="k">拒绝（许可/排除）</div><div class="v" style="color:var(--warn)">{{ run.rejectedPatients }}</div></div>
      <div class="stat"><div class="k">入组患者</div><div class="v">{{ run.assignedPatients }}</div></div>
      <div class="stat"><div class="k">整组分配扫描</div><div class="v">{{ run.assignedScans }}</div></div>
    </div>

    <!-- 校验四要件 -->
    <div v-if="v" class="grid grid-4" style="margin-bottom:14px">
      <div class="check-cell" :class="v.patientLeakage.leakageFree ? 'ok' : 'bad'">
        <div class="check-k">患者跨分区泄漏</div>
        <div class="check-v">{{ v.patientLeakage.crossSplitPatientCount }} 例</div>
        <div class="check-sub">{{ v.patientLeakage.leakageFree ? '✔ 无患者跨越训练/验证' : '✘ 发现跨分区' }}</div>
      </div>
      <div class="check-cell" :class="v.scanWholeness.allScansWholeGroup ? 'ok' : 'bad'">
        <div class="check-k">复扫整组性</div>
        <div class="check-v">{{ v.scanWholeness.splitViolationCount }} 拆分</div>
        <div class="check-sub">{{ v.scanWholeness.multiScanAssignedPatients }} 名多扫描患者整体分配</div>
      </div>
      <div class="check-cell" :class="v.consentScope.consentScopeOk ? 'ok' : 'bad'">
        <div class="check-k">许可范围</div>
        <div class="check-v">{{ v.consentScope.violationCount }} 违规</div>
        <div class="check-sub">{{ v.consentScope.consentScopeOk ? '✔ 全部在许可分区内' : '✘ 存在超范围使用' }}</div>
      </div>
      <div class="check-cell" :class="allQuotaOk ? 'ok' : 'bad'">
        <div class="check-k">配额区间</div>
        <div class="check-v">{{ quotaBad }} 个分区越界</div>
        <div class="check-sub">目标偏差：{{ quotaDeviations }}</div>
      </div>
    </div>

    <!-- 缺口告警 -->
    <div v-if="v && v.coverageGaps && v.coverageGaps.length" class="gap-box">
      <b>⚠ 覆盖缺口（硬下限无法由合格患者池满足；系统如实报告，不会从验证集挪患者或拆分扫描）</b>
      <div class="table-wrap" style="max-height:220px;margin-top:8px">
        <table>
          <thead><tr><th>维度</th><th>取值</th><th>分区</th><th>最低要求</th><th>实际</th><th>缺口</th></tr></thead>
          <tbody>
          <tr v-for="(g, i) in v.coverageGaps" :key="i">
            <td>{{ dimLabel(g.dimension) }}</td><td class="mono">{{ g.value }}</td>
            <td><span class="tag tag-info">{{ g.split }}</span></td>
            <td>{{ g.requiredMin }}</td><td>{{ g.actual }}</td>
            <td><span class="tag tag-danger">缺 {{ g.missing }}</span></td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 配额条 -->
    <div class="card inner-card">
      <h2>分区患者配额（实际 / 目标 / 硬区间）</h2>
      <div v-for="q in (v ? v.quota : [])" :key="q.split" class="quota-row">
        <div class="quota-head">
          <span class="tag tag-info">{{ q.split }}</span>
          <span :class="q.withinBounds ? 'muted' : 'fail-text'">
            <b>{{ q.actual }}</b> / 目标 {{ q.target }} / 区间 [{{ q.min }}, {{ q.max }}]
            · 偏差 {{ q.deviation >= 0 ? '+' : '' }}{{ q.deviation }}
            {{ q.withinBounds ? '✔' : '✘ 越界' }}
          </span>
        </div>
        <div class="bar">
          <div class="bar-fill" :class="q.withinBounds ? '' : 'bar-bad'"
               :style="{ width: barWidth(q.actual) + '%' }"></div>
          <div class="bar-target" :style="{ left: barWidth(q.target) + '%' }"></div>
        </div>
      </div>
    </div>

    <!-- 覆盖矩阵 -->
    <div class="card inner-card">
      <h2>类别 / 设备 / 协议覆盖矩阵（实际 · 下限 / 软目标）</h2>
      <div class="table-wrap" style="max-height:360px">
        <table>
          <thead>
          <tr><th>维度</th><th>取值</th><th>可抽取池</th>
            <th v-for="s in splitCodes" :key="s">{{ s }}</th></tr>
          </thead>
          <tbody>
          <tr v-for="(c, i) in (v ? v.coverage : [])" :key="i">
            <td>{{ dimLabel(c.dimension) }}</td>
            <td class="mono">{{ c.value }}<span v-if="c.value === 'RARE_PERF_FMRI'" class="tag tag-warn" style="margin-left:6px">罕见</span></td>
            <td>{{ c.featurePool }}</td>
            <td v-for="s in splitCodes" :key="s">
              <span class="cov-cell" :class="c.bySplit[s]?.hardMet ? 'cov-ok' : 'cov-bad'">
                <b>{{ c.bySplit[s]?.actual }}</b>
                <span class="muted"> / 硬{{ c.bySplit[s]?.min }} · 目{{ c.bySplit[s]?.target }}</span>
                <span v-if="!c.bySplit[s]?.hardMet" class="tag tag-danger" style="margin-left:4px">
                  缺{{ c.bySplit[s]?.hardGap }}
                </span>
              </span>
            </td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 求解统计 -->
    <div class="card inner-card">
      <h2>求解与账本统计</h2>
      <div class="kv-grid">
        <div><span class="muted">路线</span> {{ strategyLabel(run.strategy) }}</div>
        <div><span class="muted">内存模式</span> {{ run.solverStats?.memoryMode }}</div>
        <div><span class="muted">驻留患者组</span> {{ run.solverStats?.groupsResident }}</div>
        <div><span class="muted">扫描行驻留</span> {{ run.solverStats?.scanRowsResident }}</div>
        <div v-if="run.solverStats?.restarts != null"><span class="muted">全局起点</span> {{ run.solverStats.restarts }}（最佳 #{{ run.solverStats.bestRestart }}）</div>
        <div><span class="muted">修复轮数</span> {{ run.solverStats?.repairPasses }}</div>
        <div><span class="muted">整患者交换</span> {{ run.solverStats?.swapsApplied }}</div>
        <div><span class="muted">整患者移动</span> {{ run.solverStats?.movesApplied }}</div>
        <div v-if="run.solverStats?.deltaNewGroups != null"><span class="muted">增量新组</span> {{ run.solverStats.deltaNewGroups }}</div>
        <div v-if="run.solverStats?.reusedBaselineGroups != null"><span class="muted">沿用基线组</span> {{ run.solverStats.reusedBaselineGroups }}</div>
        <div class="kv-wide muted">{{ run.solverStats?.note || '一次性构建完整 0-1 分配模型，多起点全局寻优；目录变更后需整体重算。' }}</div>
      </div>
    </div>

    <!-- 拒绝账 -->
    <div class="card inner-card">
      <h2>拒绝原因账（{{ run.rejections?.length || 0 }}）</h2>
      <div class="table-wrap" style="max-height:240px">
        <table>
          <thead><tr><th>患者</th><th>原因码</th><th>说明</th></tr></thead>
          <tbody>
          <tr v-for="(r, i) in run.rejections" :key="i">
            <td class="mono">{{ r.patientUid }}</td>
            <td><span class="tag tag-warn">{{ r.reasonCode }}</span></td>
            <td style="white-space:normal">{{ r.detail }}</td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 分配明细 -->
    <div class="card inner-card">
      <h2>患者分区分配（{{ run.assignments?.length || 0 }} 个不可拆分组）</h2>
      <div class="toolbar">
        <button class="btn btn-sm" :class="filterSplit === '' ? '' : 'btn-ghost'" @click="filterSplit = ''">全部</button>
        <button v-for="s in splitCodes" :key="s" class="btn btn-sm"
                :class="filterSplit === s ? '' : 'btn-ghost'" @click="filterSplit = s">{{ s }}</button>
        <input v-model="q" placeholder="搜索患者编号" style="margin-left:auto" />
      </div>
      <div class="table-wrap" style="max-height:380px">
        <table>
          <thead><tr><th>患者</th><th>分区</th><th>疾病等级</th><th>主设备</th>
            <th>协议特征</th><th>扫描数</th><th>决策路径</th></tr></thead>
          <tbody>
          <tr v-for="a in filteredAssignments" :key="a.patientUid">
            <td class="mono">{{ a.patientUid }}</td>
            <td><span class="tag tag-info">{{ a.split }}</span></td>
            <td>{{ a.diseaseGrade }} · {{ a.diseaseLabel }}</td>
            <td class="mono">{{ a.primaryDevice }}</td>
            <td style="white-space:normal;max-width:280px">
              <span v-for="p in a.protocolFeatures" :key="p" class="tag"
                    :class="p === 'RARE_PERF_FMRI' ? 'tag-warn' : 'tag-muted'"
                    style="margin:1px 3px 1px 0">{{ p }}</span>
            </td>
            <td>{{ a.scanCount }}</td>
            <td><span class="tag tag-muted">{{ pathLabel(a.decisionPath) }}</span></td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({ run: { type: Object, required: true } })
const filterSplit = ref('')
const q = ref('')

const v = computed(() => props.run.validation || null)
const splitCodes = computed(() => props.run.compiled?.splitCodes || ['TRAIN', 'VAL'])
const shortWm = computed(() => String(props.run.inputWatermark || '').slice(0, 40) + '…')

const filteredAssignments = computed(() => {
  return (props.run.assignments || []).filter(a => {
    if (filterSplit.value && a.split !== filterSplit.value) return false
    if (q.value && !a.patientUid.toLowerCase().includes(q.value.toLowerCase())) return false
    return true
  })
})

const allQuotaOk = computed(() => (v.value?.quota || []).every(x => x.withinBounds))
const quotaBad = computed(() => (v.value?.quota || []).filter(x => !x.withinBounds).length)
const quotaDeviations = computed(() =>
  (v.value?.quota || []).map(x => `${x.split} ${x.deviation >= 0 ? '+' : ''}${x.deviation}`).join('，'))

const maxTarget = computed(() => Math.max(1, ...(v.value?.quota || []).map(x => Math.max(x.actual, x.target, x.max))))
function barWidth(x) {
  return Math.min(100, Math.round(100 * x / maxTarget.value))
}
function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : ''
}
function strategyLabel(s) {
  return s === 'GLOBAL_ASSIGN' ? 'A · 一次性全局整数分配'
    : s === 'STREAM_STRATIFIED' ? 'B · 流式分层 + 末轮交换修复' : s
}
function strategyClass(s) {
  return s === 'GLOBAL_ASSIGN' ? 'tag-info' : 'tag-ok'
}
function dimLabel(d) {
  return { DISEASE_GRADE: '疾病等级', DEVICE: '设备', PROTOCOL: '协议' }[d] || d
}
function pathLabel(p) {
  return {
    DIRECT: '直接分配', COVERAGE_SEED: '覆盖播种', STRATIFIED: '在线分层',
    SWAP_REPAIR: '交换修复', GLOBAL_INIT: '全局构造', GLOBAL_OPTIMIZE: '全局寻优',
    INCREMENTAL: '增量沿用/分配'
  }[p] || p
}
</script>

<style scoped>
.banner { border-radius: 12px; padding: 14px 16px; border: 1px solid var(--line); }
.banner-ok { background: rgba(55, 208, 168, 0.08); border-color: var(--ok); }
.banner-gap { background: rgba(239, 91, 107, 0.08); border-color: var(--danger); }
.banner-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.banner-meta { color: var(--muted); font-size: 12px; margin-top: 8px; }
.check-cell { border: 1px solid var(--line); border-radius: 10px; padding: 12px; background: var(--panel-2); }
.check-cell.ok { border-color: rgba(55,208,168,.5); }
.check-cell.bad { border-color: var(--danger); background: rgba(239,91,107,.1); }
.check-k { color: var(--muted); font-size: 12px; }
.check-v { font-size: 20px; font-weight: 700; margin: 4px 0; }
.check-sub { font-size: 11px; color: var(--muted); }
.check-cell.ok .check-sub { color: var(--ok); }
.check-cell.bad .check-sub { color: var(--danger); }
.gap-box { border: 1px solid var(--danger); background: rgba(239,91,107,.08); border-radius: 10px; padding: 12px 14px; margin-bottom: 14px; }
.inner-card { background: var(--panel-2); }
.quota-row { margin-bottom: 14px; }
.quota-head { display: flex; justify-content: space-between; font-size: 12px; margin-bottom: 6px; }
.fail-text { color: var(--danger); }
.bar { position: relative; height: 12px; background: var(--panel); border-radius: 999px; overflow: visible; }
.bar-fill { height: 12px; border-radius: 999px; background: linear-gradient(90deg, var(--accent), #7a5cff); }
.bar-fill.bar-bad { background: var(--danger); }
.bar-target { position: absolute; top: -3px; width: 2px; height: 18px; background: var(--warn); }
.cov-cell { white-space: nowrap; }
.cov-ok b { color: var(--ok); }
.cov-bad b { color: var(--danger); }
.kv-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px 18px; font-size: 13px; }
.kv-grid > div { min-width: 0; }
.kv-wide { grid-column: 1 / -1; }
@media (max-width: 1100px) { .kv-grid { grid-template-columns: repeat(2, 1fr); } }
</style>
