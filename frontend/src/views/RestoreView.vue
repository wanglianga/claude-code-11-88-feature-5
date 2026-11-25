<template>
  <div>
    <div class="card">
      <h2>恢复计划（提前72h预约 · 4h RTO · 启动前可满足性检查）</h2>
      <div class="toolbar">
        <label>恢复目标
          <select v-model="form.targetUid">
            <option v-for="o in targets" :key="o.uid" :value="o.uid">{{ o.uid }}（{{ o.name }}）</option>
          </select>
        </label>
        <label>路线
          <select v-model="form.strategy">
            <option value="SCHEDULED_TIERING">预约分层 + DAG 提前72h取回</option>
            <option value="RESTORE_PACKAGE">可校验恢复包（4h RTO 层常驻）</option>
          </select>
        </label>
        <label>年频次
          <input v-model.number="form.frequencyPerYear" type="number" min="0.1" step="0.5" style="width:110px" />
        </label>
        <button class="btn" @click="createPlan">创建计划并检查</button>
      </div>
      <div class="hint">
        缺件注入演练：
        <button class="btn btn-sm btn-danger" @click="toggleAvail('CAL-A01')">移除/恢复校准文件 CAL-A01</button>
        <button class="btn btn-sm btn-danger" @click="toggleAvail('ALGO-PKG-01')">移除/恢复算法包 ALGO-PKG-01</button>
        <button class="btn btn-sm btn-ok" @click="validatePackage">校验恢复包 PKG-RESULT-A01</button>
        <span class="tag" :class="calAvailable ? 'tag-ok' : 'tag-danger'" style="margin-left:8px">
          CAL-A01 {{ calAvailable ? '在档' : '已移除' }}</span>
      </div>

      <div v-if="plan" class="hint" :class="plan.status === 'SATISFIABLE' ? '' : 'fail'">
        <div><b>计划 #{{ plan.planId }}</b> 状态：
          <span class="tag" :class="plan.status === 'SATISFIABLE' ? 'tag-ok' : 'tag-danger'">{{ plan.status }}</span>
          路线 {{ plan.strategy }} · 预计 {{ plan.estimatedHours }}h / RTO {{ plan.rtoHours }}h ·
          闭包 {{ plan.closureCount }} 个对象 · 预约时间 {{ plan.scheduledAt }}
        </div>
        <div v-if="plan.missingItems?.length" style="margin-top:8px">
          <b class="fail">缺项（任务启动前阻断）：</b>
          <table style="margin-top:6px">
            <tr v-for="(m, i) in plan.missingItems" :key="i">
              <td class="mono">{{ m.uid }}</td><td>{{ m.category }}</td>
              <td class="fail">{{ m.reasons.join('；') }}</td>
            </tr>
          </table>
        </div>
        <div style="margin-top:8px">
          单次取回费 ¥{{ plan.perRunRetrievalFee }} + 恢复计算费 ¥{{ plan.perRunComputeFee }}
          <span v-if="plan.packageMonthlyFee !== 'N/A'"> · 恢复包月费 ¥{{ plan.packageMonthlyFee }}</span>
        </div>
        <button class="btn btn-sm" :disabled="plan.status !== 'SATISFIABLE'"
                style="margin-top:8px" @click="execute(plan.planId)">执行恢复计划</button>
      </div>

      <div v-if="schedule.length" style="margin-top:14px">
        <b>72h 窗口 DAG 预取日程</b>
        <table style="margin-top:6px">
          <thead><tr><th>对象</th><th>依赖深度</th><th>层取回耗时(h)</th><th>窗口起(h)</th><th>窗口止(h)</th><th>72h内</th></tr></thead>
          <tbody><tr v-for="s in schedule" :key="s.uid">
            <td class="mono">{{ s.uid }}</td><td>{{ s.depth }}</td><td>{{ s.tierRetrievalHours }}</td>
            <td>{{ s.windowStartHour }}</td><td>{{ s.windowEndHour }}</td>
            <td><span class="tag" :class="s.within72hLead ? 'tag-ok' : 'tag-danger'">
              {{ s.within72hLead ? '是' : '超时' }}</span></td>
          </tr></tbody>
        </table>
      </div>
    </div>

    <div class="card">
      <h2>历史恢复计划</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>#</th><th>目标</th><th>项目</th><th>路线</th><th>状态</th><th>预计h</th>
            <th>频次/年</th><th>闭包</th><th>缺项</th><th>单次取回费</th><th>单次计算费</th></tr></thead>
          <tbody>
          <tr v-for="p in plans" :key="p.planId">
            <td>{{ p.planId }}</td><td class="mono">{{ p.targetUid }}</td><td>{{ p.projectCode }}</td>
            <td>{{ p.strategy }}</td>
            <td><span class="tag" :class="p.status === 'SATISFIABLE' || p.status === 'EXECUTED' ? 'tag-ok' : 'tag-danger'">
              {{ p.status }}</span></td>
            <td>{{ p.estimatedHours }}</td><td>{{ p.frequencyPerYear }}</td>
            <td>{{ p.closureCount }}</td>
            <td class="fail mono" style="white-space:normal;max-width:260px">
              {{ p.missingItems?.length ? JSON.stringify(p.missingItems.map(m => m.uid)) : '—' }}</td>
            <td>¥{{ p.perRunRetrievalFee }}</td><td>¥{{ p.perRunComputeFee }}</td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="pkgResult" class="card">
      <h2>恢复包校验结果：{{ pkgResult.packageUid }}</h2>
      <div class="tag" :class="pkgResult.valid ? 'tag-ok' : 'tag-danger'" style="margin-bottom:10px">
        {{ pkgResult.valid ? '可复现（清单/摘要/软件版本齐备）' : '不可复现' }}
      </div>
      <table>
        <thead><tr><th>对象</th><th>类别</th><th>目录可用</th><th>校验状态</th><th>软件版本</th></tr></thead>
        <tbody><tr v-for="c in pkgResult.checked" :key="c.uid">
          <td class="mono">{{ c.uid }}</td><td>{{ c.category }}</td>
          <td><span class="tag" :class="c.available ? 'tag-ok' : 'tag-danger'">{{ c.available ? '是' : '否' }}</span></td>
          <td><span class="tag" :class="c.checksumStatus === 'OK' ? 'tag-ok' : 'tag-danger'">{{ c.checksumStatus }}</span></td>
          <td class="mono">{{ c.softwareVersion || '—' }}</td>
        </tr></tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import api from '../api'

const targets = ref([])
const plans = ref([])
const plan = ref(null)
const schedule = ref([])
const pkgResult = ref(null)
const calAvailable = ref(true)
const form = reactive({ targetUid: 'RESULT-A01', strategy: 'SCHEDULED_TIERING', frequencyPerYear: 1 })

async function load() {
  targets.value = (await api.get('/catalog/objects')).data.filter(o =>
      ['ANALYSIS_RESULT', 'FEATURE'].includes(o.category))
  plans.value = (await api.get('/restore/plans')).data
  const cal = (await api.get('/catalog/objects')).data.find(o => o.uid === 'CAL-A01')
  calAvailable.value = cal.directoryAvailable
}
async function createPlan() {
  plan.value = (await api.post('/restore/plans', form)).data
  schedule.value = (await api.get('/restore/prefetch-schedule', { params: { targetUid: form.targetUid } })).data
  await load()
}
async function execute(id) {
  const r = await api.post(`/restore/plans/${id}/execute`)
  plan.value = { ...plan.value, ...r.data }
  await load()
}
async function toggleAvail(uid) {
  const objs = (await api.get('/catalog/objects')).data
  const cur = objs.find(o => o.uid === uid).directoryAvailable
  await api.post('/restore/availability', { uid, available: !cur })
  await load()
}
async function validatePackage() {
  pkgResult.value = (await api.post('/restore/packages/validate', { packageUid: 'PKG-RESULT-A01' })).data
}
onMounted(load)
</script>
