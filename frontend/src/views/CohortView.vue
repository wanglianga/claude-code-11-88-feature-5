<template>
  <div>
    <div class="card cohort-intro">
      <h2>可复现研究队列抽取器 · 患者成组不拆分，覆盖疾病等级 / 设备 / 协议</h2>
      <div class="hint">
        同一患者的相邻复扫是<b>不可拆分分组</b>，从根上杜绝患者跨训练/验证泄漏。
        约束编译器把每分区患者数、类别/设备/协议覆盖编译为<b>硬约束</b>与<b>软目标</b>；
        抽取账固定<b>种子 + 输入水位 + 拒绝原因</b>；校验器独立核对跨分区、配额偏差与许可范围。
        罕见协议 <span class="mono">RARE_PERF_FMRI</span> 样例仅 8 名患者（7 名许可合格），
        当最低覆盖不可行时报告缺口，而不是从验证集挪患者造成泄漏。
      </div>
      <div class="tabs">
        <button v-for="t in tabs" :key="t.key" class="tab-btn"
                :class="{ active: tab === t.key }" @click="tab = t.key">{{ t.label }}</button>
      </div>
    </div>

    <!-- ============ 总览 ============ -->
    <div v-if="tab === 'overview' && ov" class="card">
      <h2>候选目录总览与输入水位</h2>
      <div class="grid grid-4">
        <div class="stat"><div class="k">候选患者</div><div class="v">{{ ov.patientCount }}</div></div>
        <div class="stat"><div class="k">扫描次数</div><div class="v">{{ ov.scanCount }}</div></div>
        <div class="stat"><div class="k">设备种类</div><div class="v">{{ ov.deviceCount }}</div></div>
        <div class="stat"><div class="k">许可合格患者</div><div class="v" style="color:var(--ok)">{{ ov.eligiblePatients }}</div></div>
        <div class="stat"><div class="k">罕见协议患者</div><div class="v" style="color:var(--warn)">{{ ov.rarePatients }} <span class="sub-v">/ 合格 {{ ov.rareEligible }}</span></div></div>
        <div class="stat"><div class="k">许可过期</div><div class="v" style="color:var(--danger)">{{ ov.consentExpired }}</div></div>
        <div class="stat"><div class="k">目录 QC 排除</div><div class="v" style="color:var(--danger)">{{ ov.catalogExcluded }}</div></div>
        <div class="stat"><div class="k">水位版本</div><div class="v mono" style="font-size:13px">{{ ov.watermark?.schema }}</div></div>
      </div>

      <div class="wm-box">
        <div><b>当前输入水位（每次抽取写入账本，用于变更重算/增量判断）</b></div>
        <div class="mono" style="margin-top:6px">{{ ov.inputWatermark }}</div>
        <div class="muted" style="margin-top:4px;font-size:12px">
          最大 source_updated_at：{{ ov.watermark?.maxSourceUpdatedAt }} ·
          全量目录 SHA-256：{{ ov.watermark?.catalogSha256 }}
        </div>
      </div>

      <div class="grid grid-2" style="margin-top:14px">
        <div>
          <b class="muted">疾病等级分布（总 / 合格）</b>
          <table style="margin-top:6px">
            <thead><tr><th>等级</th><th>标签</th><th>患者</th><th>合格</th></tr></thead>
            <tbody>
            <tr v-for="g in ov.diseaseGrades" :key="g.grade">
              <td>{{ g.grade }}</td><td>{{ g.label }}</td><td>{{ g.patients }}</td><td>{{ g.eligible }}</td>
            </tr>
            </tbody>
          </table>
        </div>
        <div>
          <b class="muted">设备覆盖（接触过该设备的患者数）</b>
          <table style="margin-top:6px">
            <thead><tr><th>设备</th><th>患者</th><th>合格</th></tr></thead>
            <tbody>
            <tr v-for="d in ov.devices" :key="d.code">
              <td class="mono">{{ d.code }}</td><td>{{ d.patients }}</td><td>{{ d.eligible }}</td>
            </tr>
            </tbody>
          </table>
          <b class="muted" style="display:block;margin-top:14px">协议分布</b>
          <div style="margin-top:6px">
            <span v-for="p in ov.protocols" :key="p.code" class="tag"
                  :class="p.code === 'RARE_PERF_FMRI' ? 'tag-warn' : 'tag-info'"
                  style="margin:2px 6px 2px 0">
              {{ p.code }} · {{ p.patients }}人<template v-if="p.code === 'RARE_PERF_FMRI'">（合格{{ p.eligible }}）</template>
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- ============ 新建抽取 ============ -->
    <div v-if="tab === 'extract'">
      <div class="card">
        <h2>约束编译器与求解路线</h2>
        <div class="grid grid-2">
          <div>
            <label class="f-label">约束画像</label>
            <select v-model="profileCode" @change="onProfileChange" style="width:100%">
              <option v-for="p in profiles" :key="p.code" :value="p.code">{{ p.name }}（{{ p.code }}）</option>
            </select>

            <label class="f-label" style="margin-top:12px">求解路线</label>
            <div class="strategy-grid">
              <div class="strategy-card" :class="{ active: strategy === 'GLOBAL_ASSIGN', disabled: incremental }"
                   @click="!incremental && (strategy = 'GLOBAL_ASSIGN')">
                <b>A · 一次性全局整数分配</b>
                <p>全量患者组建模，硬约束 + 软目标全局寻优，覆盖最优；组合规模大，目录变更需整体重算。</p>
              </div>
              <div class="strategy-card" :class="{ active: strategy === 'STREAM_STRATIFIED' }"
                   @click="strategy = 'STREAM_STRATIFIED'">
                <b>B · 流式分层 + 末轮交换修复</b>
                <p>逐患者组在线分层，常驻内存低、可按水位增量；末轮整患者交换修复配额，不保证全局最优。</p>
              </div>
            </div>

            <label class="f-label" style="margin-top:12px">复现种子 seed</label>
            <input v-model.number="seed" type="number" style="width:200px" />

            <div class="inc-box">
              <label><input type="checkbox" v-model="incremental" @change="onToggleIncremental" />
                <b>增量模式</b>：沿用所选历史账本的患者分配，仅分层输入水位之后的新患者（仅路线 B）</label>
              <select v-if="incremental" v-model="parentRunId" style="width:100%;margin-top:8px">
                <option :value="null" disabled>选择父账本…</option>
                <option v-for="r in streamRuns" :key="r.runId" :value="r.runId">
                  #{{ r.runId }} {{ r.profileCode }} seed={{ r.seed }}（{{ fmt(r.createdAt) }}）
                </option>
              </select>
            </div>

            <div class="toolbar" style="margin-top:14px">
              <button class="btn btn-ghost" @click="compile">编译约束（试算）</button>
              <button class="btn" :disabled="running || (incremental && !parentRunId)" @click="run">
                {{ running ? '求解中…' : '运行抽取并记账' }}
              </button>
              <span v-if="!auth.isAdmin" class="tag tag-warn">REVIEWER 只读，运行会返回 403</span>
            </div>
          </div>

          <div>
            <label class="f-label">约束规格 JSON（可编辑后直接运行；min=硬下限，target/weight=软目标）</label>
            <textarea v-model="specText" rows="18" class="mono spec-area"></textarea>
          </div>
        </div>

        <div v-if="compiled" style="margin-top:14px">
          <div v-for="(w, i) in compiled.feasibilityWarnings" :key="i" class="warn-line">⚠ {{ w }}</div>
          <div class="compiled-summary">
            <span class="tag tag-info">合格患者 {{ compiled.eligiblePatients }}</span>
            <span v-for="s in compiled.splits" :key="s.code" class="tag tag-muted">
              {{ s.code }} 目标{{ s.targetPatients }} · 硬区间[{{ s.minPatients }},{{ s.maxPatients }}]
            </span>
            <span class="tag tag-muted">{{ compiled.coverage.length }} 条覆盖规则</span>
          </div>
          <details style="margin-top:8px">
            <summary class="muted" style="cursor:pointer">查看编译后的完整硬约束/软目标</summary>
            <pre class="json">{{ JSON.stringify(compiled, null, 2) }}</pre>
          </details>
        </div>
      </div>

      <CohortRunDetail v-if="result" :run="result" />
    </div>

    <!-- ============ 账本 ============ -->
    <div v-if="tab === 'ledger'">
      <div class="card">
        <h2>抽取账本（种子 / 水位 / 状态 / 校验）</h2>
        <div class="toolbar">
          <button class="btn btn-ghost btn-sm" @click="loadRuns">刷新账本</button>
        </div>
        <div class="table-wrap" style="max-height:300px">
          <table>
            <thead><tr><th>#</th><th>路线</th><th>画像</th><th>种子</th><th>状态</th>
              <th>患者</th><th>扫描</th><th>增量</th><th>校验</th><th>水位</th><th>时间</th><th></th></tr></thead>
            <tbody>
            <tr v-for="r in runs" :key="r.runId">
              <td>{{ r.runId }}</td>
              <td><span class="tag" :class="r.strategy === 'GLOBAL_ASSIGN' ? 'tag-info' : 'tag-ok'">
                {{ r.strategy === 'GLOBAL_ASSIGN' ? '全局' : '流式' }}</span></td>
              <td class="mono">{{ r.profileCode }}</td>
              <td class="mono">{{ r.seed }}</td>
              <td><span class="tag" :class="r.status === 'OK' ? 'tag-ok' : 'tag-danger'">{{ r.status }}</span></td>
              <td>{{ r.assignedPatients }}/{{ r.eligiblePatients }}</td>
              <td>{{ r.assignedScans }}</td>
              <td>{{ r.incremental ? `↳#${r.parentRunId} +${r.deltaPatients}` : '—' }}</td>
              <td><span class="tag" :class="r.validationPassed ? 'tag-ok' : 'tag-danger'">
                {{ r.validationPassed ? '通过' : '缺口' }}</span></td>
              <td class="mono" :title="r.inputWatermark">{{ String(r.inputWatermark).slice(12, 34) }}…</td>
              <td class="muted">{{ fmt(r.createdAt) }}</td>
              <td><button class="btn btn-sm" @click="openRun(r.runId)">查看</button></td>
            </tr>
            </tbody>
          </table>
        </div>
      </div>
      <CohortRunDetail v-if="ledgerRun" :run="ledgerRun" />
    </div>

    <!-- ============ 候选目录 ============ -->
    <div v-if="tab === 'catalog'">
      <div class="card">
        <h2>候选患者目录（疾病标签 · 设备/序列特征 · 许可 · 排除原因）</h2>
        <div class="toolbar">
          <select v-model="eligibility" @change="loadPatients">
            <option value="all">全部</option>
            <option value="eligible">仅许可合格</option>
            <option value="excluded">仅被拒绝/排除</option>
          </select>
          <input v-model="patientQ" placeholder="按患者编号搜索" @keyup.enter="loadPatients" />
          <button class="btn btn-ghost btn-sm" @click="loadPatients">查询</button>
          <span style="flex:1"></span>
          <select v-model="newGrade" style="width:120px">
            <option :value="0">G0</option><option :value="1">G1</option>
            <option :value="2">G2</option><option :value="3">G3</option>
          </select>
          <label class="muted" style="font-size:12px"><input type="checkbox" v-model="newRare" /> 含罕见协议</label>
          <button class="btn btn-sm" @click="addTempPatient">模拟新增 CP-TMP 患者（增量演示）</button>
        </div>
        <div class="table-wrap" style="max-height:520px">
          <table>
            <thead><tr><th>患者</th><th>等级</th><th>主设备</th><th>协议特征</th><th>扫描</th>
              <th>许可</th><th>许可范围</th><th>排除原因</th><th>合格</th><th></th></tr></thead>
            <tbody>
            <tr v-for="p in patients" :key="p.patientUid">
              <td class="mono">{{ p.patientUid }}</td>
              <td>{{ p.diseaseGrade }}</td>
              <td class="mono">{{ p.primaryDevice }}</td>
              <td style="white-space:normal;max-width:260px">
                <span v-for="x in p.protocolFeatures" :key="x" class="tag"
                      :class="x === 'RARE_PERF_FMRI' ? 'tag-warn' : 'tag-muted'"
                      style="margin:1px 3px 1px 0">{{ x }}</span>
              </td>
              <td>{{ p.scanCount }}</td>
              <td><span class="tag" :class="p.consentStatus === 'ACTIVE' ? 'tag-ok' : 'tag-danger'">{{ p.consentStatus }}</span></td>
              <td class="mono" style="font-size:11px">{{ (p.consentScope || []).join('+') }}</td>
              <td><span v-if="p.exclusionReason" class="tag tag-warn">{{ p.exclusionReason }}</span>
                <span v-else class="muted">—</span></td>
              <td>{{ p.eligible ? '✔' : '✘' }}</td>
              <td class="flex">
                <button class="btn btn-sm btn-ghost" @click="showScans(p)">复扫</button>
                <button v-if="String(p.patientUid).startsWith('CP-TMP-')"
                        class="btn btn-sm btn-danger" @click="removeTemp(p.patientUid)">删除</button>
              </td>
            </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div v-if="scansPatient" class="card">
        <h2>{{ scansPatient.patientUid }} 的扫描（重复/相邻复扫整体成组，共 {{ scans.length }} 次）</h2>
        <div class="table-wrap" style="max-height:320px">
          <table>
            <thead><tr><th>扫描UID</th><th>设备</th><th>协议</th><th>序列</th>
              <th>场次</th><th>复扫</th><th>采集时间</th></tr></thead>
            <tbody>
            <tr v-for="s in scans" :key="s.scanUid">
              <td class="mono">{{ s.scanUid }}</td><td class="mono">{{ s.deviceCode }}</td>
              <td><span class="tag" :class="s.protocolCode === 'RARE_PERF_FMRI' ? 'tag-warn' : 'tag-info'">{{ s.protocolCode }}</span></td>
              <td>{{ s.sequenceName }}</td><td>{{ s.sessionNo }}</td>
              <td>{{ s.repeatRescan ? '是（重复扫描）' : '首次' }}</td>
              <td class="muted">{{ fmt(s.acquiredAt) }}</td>
            </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import api from '../api'
import { useAuthStore } from '../stores/auth'
import CohortRunDetail from '../components/CohortRunDetail.vue'

const auth = useAuthStore()
const tabs = [
  { key: 'overview', label: '① 总览 / 水位' },
  { key: 'extract', label: '② 约束与抽取' },
  { key: 'ledger', label: '③ 抽取账本' },
  { key: 'catalog', label: '④ 候选目录' }
]
const tab = ref('overview')

const ov = ref(null)
const profiles = ref([])
const runs = ref([])
const patients = ref([])

const profileCode = ref('DEFAULT_80_20')
const specText = ref('')
const strategy = ref('GLOBAL_ASSIGN')
const seed = ref(20261125)
const incremental = ref(false)
const parentRunId = ref(null)
const compiled = ref(null)
const running = ref(false)
const result = ref(null)
const ledgerRun = ref(null)

const eligibility = ref('all')
const patientQ = ref('')
const newGrade = ref(2)
const newRare = ref(false)
const scansPatient = ref(null)
const scans = ref([])

const streamRuns = computed(() => runs.value.filter(r => r.strategy === 'STREAM_STRATIFIED'))

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : ''
}

async function loadOverview() {
  ov.value = (await api.get('/cohort/overview')).data
}
async function loadProfiles() {
  profiles.value = (await api.get('/cohort/profiles')).data
  onProfileChange()
}
function onProfileChange() {
  const p = profiles.value.find(x => x.code === profileCode.value)
  if (p) specText.value = JSON.stringify(p.spec, null, 2)
  compiled.value = null
}
async function loadRuns() {
  runs.value = (await api.get('/cohort/runs')).data
}
async function loadPatients() {
  patients.value = (await api.get('/cohort/patients', {
    params: { eligibility: eligibility.value, q: patientQ.value || undefined }
  })).data
}

function parseSpec() {
  try {
    return JSON.parse(specText.value)
  } catch (e) {
    window.alert('约束 JSON 解析失败：' + e.message)
    return null
  }
}

async function compile() {
  const spec = parseSpec()
  if (!spec) return
  compiled.value = (await api.post('/cohort/constraints/compile', { profileCode: profileCode.value, spec })).data
}

function onToggleIncremental() {
  if (incremental.value) {
    strategy.value = 'STREAM_STRATIFIED'
    if (!parentRunId.value && streamRuns.value.length) parentRunId.value = streamRuns.value[0].runId
  }
}

async function run() {
  const spec = parseSpec()
  if (!spec) return
  running.value = true
  try {
    const body = {
      strategy: strategy.value,
      seed: Number(seed.value),
      profileCode: profileCode.value,
      spec
    }
    if (incremental.value) body.incrementalFromRunId = parentRunId.value
    result.value = (await api.post('/cohort/runs', body)).data
    compiled.value = result.value.compiled
    await Promise.all([loadRuns(), loadOverview()])
  } finally {
    running.value = false
  }
}

async function openRun(id) {
  ledgerRun.value = (await api.get(`/cohort/runs/${id}`)).data
}

async function showScans(p) {
  scansPatient.value = p
  scans.value = (await api.get('/cohort/scans', { params: { patientUid: p.patientUid } })).data
}

async function addTempPatient() {
  const uid = 'CP-TMP-WEB-' + Date.now().toString(36).toUpperCase()
  await api.post('/cohort/candidates', {
    patientUid: uid, diseaseGrade: newGrade.value, rare: newRare.value, scanCount: 5
  })
  await loadPatients()
  await loadOverview()
  window.alert(`已新增 ${uid}。可在“约束与抽取”中选流式增量，并以某账本为父账本运行。`)
}

async function removeTemp(uid) {
  if (!window.confirm(`确认删除临时患者 ${uid} 及其扫描？`)) return
  await api.delete(`/cohort/candidates/${encodeURIComponent(uid)}`)
  if (scansPatient.value?.patientUid === uid) {
    scansPatient.value = null
    scans.value = []
  }
  await loadPatients()
  await loadOverview()
}

onMounted(async () => {
  await Promise.all([loadOverview(), loadProfiles(), loadRuns(), loadPatients()])
})
</script>

<style scoped>
.cohort-intro h2 { margin-bottom: 8px; }
.tabs { display: flex; gap: 6px; margin-top: 14px; flex-wrap: wrap; }
.tab-btn { background: var(--panel-2); border: 1px solid var(--line); color: var(--muted);
  padding: 8px 16px; border-radius: 8px; cursor: pointer; font-size: 13px; }
.tab-btn.active { background: var(--accent); color: #fff; border-color: var(--accent); }
.f-label { display: block; color: var(--muted); font-size: 12px; margin-bottom: 6px; }
.spec-area { width: 100%; font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; }
.strategy-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 6px; }
.strategy-card { border: 1px solid var(--line); border-radius: 10px; padding: 12px; cursor: pointer;
  background: var(--panel-2); transition: border-color .15s; }
.strategy-card p { color: var(--muted); font-size: 12px; line-height: 1.6; margin: 6px 0 0; }
.strategy-card.active { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent) inset; }
.strategy-card.disabled { opacity: .6; }
.inc-box { margin-top: 14px; padding: 10px 12px; border: 1px dashed var(--line); border-radius: 8px; font-size: 13px; }
.wm-box { margin-top: 16px; background: var(--panel-2); border-radius: 10px; padding: 12px 14px; border: 1px solid var(--line); }
.warn-line { background: rgba(245,166,35,.12); border: 1px solid var(--warn); color: var(--warn);
  border-radius: 8px; padding: 8px 12px; font-size: 13px; margin-bottom: 8px; }
.compiled-summary { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.sub-v { font-size: 12px; color: var(--muted); font-weight: 400; }
.flex { display: flex; gap: 6px; }
@media (max-width: 1100px) { .strategy-grid { grid-template-columns: 1fr; } }
</style>
