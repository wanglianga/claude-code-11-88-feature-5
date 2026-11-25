<template>
  <div>
    <div class="card">
      <h2>月账单与共享分摊（日字节时长 × 层费率；1GB=10⁹B，月=30天）</h2>
      <div class="toolbar">
        <input v-model="month" style="width:150px" placeholder="YYYY-MM" />
        <button class="btn" @click="generate">生成/重算 {{ month }} 账单</button>
        <button class="btn btn-ghost btn-sm" @click="loadBills">查询</button>
        <span v-if="lastCheck" class="tag" :class="lastCheck.allocationCheck ? 'tag-ok' : 'tag-danger'">
          分摊总额=月账单：{{ lastCheck.allocationCheck ? '通过' : '失败' }}
          （总额 ¥{{ lastCheck.grandTotal }}）</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>项目</th><th>账期</th><th>存储费</th><th>取回费</th><th>恢复计算费</th><th>合计</th><th>核对</th></tr></thead>
          <tbody>
          <tr v-for="b in bills" :key="b.projectCode + b.billingMonth">
            <td>{{ b.projectCode }}</td><td class="mono">{{ b.billingMonth }}</td>
            <td>¥{{ b.storageFee }}</td><td>¥{{ b.retrievalFee }}</td>
            <td>¥{{ b.restoreComputeFee }}</td><td><b>¥{{ b.totalFee }}</b></td>
            <td><span class="tag" :class="b.allocationCheck ? 'tag-ok' : 'tag-danger'">
              {{ b.allocationCheck ? '一致' : '不一致' }}</span></td>
          </tr>
          </tbody>
        </table>
      </div>
      <div v-if="lastCheck" style="margin-top:12px">
        <b>对象级分摊明细（共享对象按冻结份额）</b>
        <div class="table-wrap" style="margin-top:6px">
          <table>
            <thead><tr><th>对象</th><th>层</th><th>GB</th><th>月存储费</th><th>分摊（项目/份额/费用/依据）</th></tr></thead>
            <tbody>
            <tr v-for="a in lastCheck.objectAllocations" :key="a.uid">
              <td class="mono">{{ a.uid }}</td><td>{{ a.tier }}</td><td>{{ a.gb }}</td>
              <td>¥{{ a.monthlyStorageFee }}</td>
              <td style="white-space:normal">
                <span v-for="(x, i) in a.allocations" :key="i" class="tag tag-info" style="margin:2px">
                  项目#{{ x.projectId }} · {{ (x.share * 100).toFixed(0) }}% · ¥{{ x.storageFee }} · {{ x.basis }}
                </span>
              </td>
            </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <div class="card">
      <h2>两路线对比：预约分层取回 vs 可校验恢复包（目标 RESULT-A01）</h2>
      <div class="toolbar">
        <button class="btn btn-sm" @click="loadComparison">刷新对比（频次 1/4/12 次每年）</button>
        <span v-if="cmp" class="hint" style="margin:0">
          闭包 {{ cmp.closureSize }} 对象 · 单次按需取回合计 ¥{{ cmp.perRunTotalFee }}
          （取回 ¥{{ cmp.perRunRetrievalFee }} + 计算 ¥{{ cmp.perRunComputeFee }}） ·
          恢复包 {{ cmp.packageGb }}GB 月费 ¥{{ cmp.packageMonthlyFee }} ·
          <b>盈亏平衡 ≈ {{ cmp.breakevenFrequencyPerYear }} 次/年</b>
        </span>
      </div>
      <div v-if="cmp" class="table-wrap">
        <table>
          <thead><tr><th>年频次</th><th>预约分层年费</th><th>恢复包年费</th><th>更省路线</th><th>年节省</th>
            <th>费用对比（100px=较大值）</th></tr></thead>
          <tbody>
          <tr v-for="s in cmp.scenarios" :key="s.frequencyPerYear">
            <td class="mono">{{ s.frequencyPerYear }} 次/年{{ s.frequencyPerYear === 1 ? '（题设年度重分析）' : s.frequencyPerYear === 12 ? '（每月一次）' : '' }}</td>
            <td>¥{{ s.scheduledAnnualFee }}</td><td>¥{{ s.packageAnnualFee }}</td>
            <td><span class="tag" :class="s.cheaperStrategy === 'SCHEDULED_TIERING' ? 'tag-info' : 'tag-warn'">
              {{ s.cheaperStrategy === 'SCHEDULED_TIERING' ? '预约分层' : '恢复包' }}</span></td>
            <td>¥{{ s.saving }}</td>
            <td>
              <div class="flex" style="gap:4px">
                <div :style="bar(s.scheduledAnnualFee, maxFee)" class="bar-a">预约 ¥{{ s.scheduledAnnualFee }}</div>
                <div :style="bar(s.packageAnnualFee, maxFee)" class="bar-b">包 ¥{{ s.packageAnnualFee }}</div>
              </div>
            </td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="card">
      <h2>取回 / 恢复计算事件</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>#</th><th>对象</th><th>项目</th><th>层</th><th>字节</th><th>取回费</th><th>计算费</th><th>原因</th><th>时间</th></tr></thead>
          <tbody>
          <tr v-for="e in events" :key="e.id">
            <td>{{ e.id }}</td><td class="mono">{{ e.uid }}</td><td>{{ e.projectCode }}</td>
            <td>{{ e.tier }}</td><td>{{ (e.bytes / 1e9).toFixed(2) }}GB</td>
            <td>¥{{ e.retrievalFee }}</td><td>¥{{ e.computeFee }}</td>
            <td class="muted" style="white-space:normal;max-width:260px">{{ e.reason }}</td>
            <td class="muted">{{ String(e.eventTime).slice(0, 16) }}</td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import api from '../api'

const month = ref('2026-11')
const bills = ref([])
const lastCheck = ref(null)
const cmp = ref(null)
const events = ref([])
const maxFee = computed(() => Math.max(
  ...cmp.value?.scenarios.flatMap(s => [Number(s.scheduledAnnualFee), Number(s.packageAnnualFee)]) || [1]))

function bar(v, max) {
  return { width: Math.max(60, Math.round(Number(v) / max * 240)) + 'px' }
}
async function generate() {
  lastCheck.value = (await api.post('/cost/billing/generate', { billingMonth: month.value })).data
  await loadBills(); await loadEvents()
}
async function loadBills() { bills.value = (await api.get('/cost/billing', { params: { billingMonth: month.value } })).data }
async function loadEvents() { events.value = (await api.get('/cost/retrieval-events')).data }
async function loadComparison() {
  cmp.value = (await api.get('/cost/strategy-comparison', {
    params: { targetUid: 'RESULT-A01', frequencies: [1, 4, 12] }
  })).data
}
onMounted(async () => { await loadBills(); await loadEvents(); await loadComparison() })
</script>

<style scoped>
.bar-a, .bar-b { color: #fff; font-size: 11px; padding: 3px 8px; border-radius: 6px; white-space: nowrap; }
.bar-a { background: #4f8cff; }
.bar-b { background: #b8791f; }
</style>
