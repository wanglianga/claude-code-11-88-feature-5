<template>
  <div>
    <div class="card">
      <h2>保留策略（类别 × 研究协议）</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>类别</th><th>协议</th><th>保留年限</th><th>规则</th></tr></thead>
          <tbody>
          <tr v-for="p in policies" :key="p.category + p.protocol">
            <td>{{ p.category }}</td><td>{{ p.protocol }}</td>
            <td class="mono">{{ p.retentionYears }} 年</td><td class="muted">{{ p.ruleText }}</td>
          </tr>
          </tbody>
        </table>
      </div>
      <div class="toolbar" style="margin-top:12px">
        <input v-model="policyForm.category" placeholder="类别 如 FEATURE" style="width:180px" />
        <input v-model="policyForm.protocol" placeholder="协议 如 PROTO-A" style="width:140px" />
        <input v-model.number="policyForm.retentionYears" type="number" step="0.5" placeholder="年限" style="width:100px" />
        <input v-model="policyForm.ruleText" placeholder="规则说明" style="width:300px" />
        <button class="btn btn-sm" @click="updatePolicy">更新策略（触发传播重算）</button>
        <button class="btn btn-sm btn-ghost" @click="recalc">重算全部截止日</button>
      </div>
    </div>

    <div class="card">
      <h2>对象保留水位与处置</h2>
      <div class="table-wrap">
        <table>
          <thead><tr>
            <th>对象</th><th>项目</th><th>基础截止日</th><th>有效截止日（传播后）</th>
            <th>剩余天数</th><th>处置</th><th>争议冻结</th><th>操作</th>
          </tr></thead>
          <tbody>
          <tr v-for="r in overview" :key="r.uid">
            <td class="mono">{{ r.uid }}</td><td>{{ r.projectCode }}</td>
            <td class="mono">{{ r.baseDeadline }}</td>
            <td class="mono">{{ r.effectiveDeadline || '无限期' }}</td>
            <td>{{ r.daysRemaining ?? '—' }}</td>
            <td><span class="tag" :class="dispositionTag(r.disposition)">{{ dispositionText(r.disposition) }}</span></td>
            <td><span class="tag" :class="r.litigationHold ? 'tag-danger' : 'tag-muted'">
              {{ r.litigationHold ? '冻结中' : '无' }}</span></td>
            <td class="flex">
              <button class="btn btn-sm" :class="r.litigationHold ? 'btn-ghost' : 'btn-danger'"
                      @click="toggleHold(r)">{{ r.litigationHold ? '解除冻结' : '争议冻结' }}</button>
              <button class="btn btn-sm btn-warn" @click="failChecksum(r)">模拟校验失败</button>
              <button class="btn btn-sm btn-ghost" @click="deletionCheck(r.uid)">销毁检查</button>
            </td>
          </tr>
          </tbody>
        </table>
      </div>
      <div v-if="deletion" class="hint" style="margin-top:12px">
        销毁检查 <b class="mono">{{ deletion.uid }}</b> →
        <span :class="deletion.verdict === 'DELETABLE' ? 'pass' : 'fail'">{{ deletion.verdict }}</span>
        <div v-if="deletion.otherReferences?.length" style="margin-top:6px">
          其他项目引用：<span v-for="x in deletion.otherReferences" :key="x.projectCode" class="mono">
          {{ x.projectCode }}（{{ x.status }}，份额 {{ x.share }}） </span>
        </div>
      </div>
    </div>

    <div class="card">
      <h2>治理事件与传播记录</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>#</th><th>事件</th><th>载荷</th><th>已传播到</th><th>时间</th></tr></thead>
          <tbody>
          <tr v-for="e in events" :key="e.id">
            <td>{{ e.id }}</td>
            <td><span class="tag tag-info">{{ e.eventType }}</span></td>
            <td class="mono muted">{{ JSON.stringify(e.payload) }}</td>
            <td class="mono" style="white-space:normal;max-width:380px">{{ (e.affectedUids || []).join(', ') }}</td>
            <td class="muted">{{ fmt(e.createdAt) }}</td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import api from '../api'

const overview = ref([])
const policies = ref([])
const events = ref([])
const deletion = ref(null)
const policyForm = reactive({ category: 'FEATURE', protocol: 'PROTO-A', retentionYears: 9, ruleText: '策略调整演示' })

function dispositionText(d) {
  return { RETAINED: '保留中', ELIGIBLE: '可销毁', BLOCKED_HOLD: '冻结阻断', PENDING_RECALC: '待重算' }[d] || d
}
function dispositionTag(d) {
  return { RETAINED: 'tag-info', ELIGIBLE: 'tag-ok', BLOCKED_HOLD: 'tag-danger', PENDING_RECALC: 'tag-warn' }[d] || 'tag-muted'
}
function fmt(t) { return t ? String(t).slice(0, 16) : '' }

async function load() {
  overview.value = (await api.get('/retention/overview')).data
  policies.value = (await api.get('/retention/policies')).data
  events.value = (await api.get('/retention/events')).data
}
async function recalc() { await api.post('/retention/recalculate'); await load() }
async function toggleHold(r) {
  await api.post('/retention/hold', {
    uid: r.uid, litigationHold: !r.litigationHold,
    reason: r.litigationHold ? '解除争议冻结' : '界面登记争议冻结'
  }); await load()
}
async function failChecksum(r) {
  await api.post('/retention/checksum-failed', { uid: r.uid, reason: '界面模拟 sha256 不匹配' }); await load()
}
async function deletionCheck(uid) {
  deletion.value = (await api.get(`/retention/deletion-check/${uid}`)).data
}
async function updatePolicy() {
  await api.post('/retention/policy', policyForm); await load()
}
onMounted(load)
</script>
