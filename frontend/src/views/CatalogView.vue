<template>
  <div>
    <div class="grid grid-4">
      <div class="stat"><div class="k">对象总数</div><div class="v">{{ objects.length }}</div></div>
      <div class="stat"><div class="k">总容量</div><div class="v">{{ totalGb }} GB</div></div>
      <div class="stat"><div class="k">校验失败</div><div class="v fail">{{ failedCount }}</div></div>
      <div class="stat"><div class="k">争议冻结</div><div class="v" :class="{fail: holdCount}">{{ holdCount }}</div></div>
    </div>

    <div class="card">
      <h2>对象目录</h2>
      <div class="toolbar">
        <select v-model="filter.category">
          <option value="">全部类别</option>
          <option v-for="c in categories" :key="c">{{ c }}</option>
        </select>
        <select v-model="filter.tier">
          <option value="">全部存储层</option>
          <option v-for="t in tiers" :key="t.code">{{ t.code }}（¥{{ t.gbMonthRate }}/GB·月）</option>
        </select>
        <button class="btn btn-ghost btn-sm" @click="load">刷新</button>
        <span class="spacer"></span>
        <button class="btn btn-sm" @click="showRec = !showRec">访问热度分层建议</button>
      </div>
      <div v-if="showRec" class="hint">
        规则：{{ rec.rule }}
        <table style="margin-top:8px">
          <tr><th>对象</th><th>当前层</th><th>建议层</th><th>闲置天数</th><th>操作</th></tr>
          <tr v-for="r in rec.recommendations || []" :key="r.uid">
            <td class="mono">{{ r.uid }}</td>
            <td><span class="tag tag-info">{{ r.currentTier }}</span></td>
            <td><span class="tag" :class="r.migrate ? 'tag-warn' : 'tag-ok'">{{ r.recommendedTier }}</span></td>
            <td>{{ r.idleDays }}</td>
            <td><button v-if="r.migrate" class="btn btn-sm btn-warn"
                        @click="migrate(r.uid, r.recommendedTier)">迁移</button>
                <span v-else class="muted">已在目标层</span></td>
          </tr>
        </table>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr>
            <th>对象ID</th><th>名称</th><th>类别</th><th>容量</th><th>层</th>
            <th>校验</th><th>项目</th><th>创建时间</th><th>最近访问</th><th>保留截止</th><th>状态</th>
          </tr></thead>
          <tbody>
          <tr v-for="o in filtered" :key="o.uid">
            <td class="mono">{{ o.uid }}<div v-if="o.restorePackage" class="tag tag-info" style="margin-top:2px">恢复包→{{ o.packageTarget }}</div></td>
            <td>{{ o.name }}</td>
            <td>{{ o.category }}</td>
            <td>{{ gb(o.byteSize) }} GB</td>
            <td><span class="tag tag-info">{{ o.tier }}</span></td>
            <td><span class="tag" :class="checkTag(o.checksumStatus)">{{ o.checksumStatus }}</span></td>
            <td>{{ o.projectCode }}</td>
            <td class="muted">{{ fmt(o.createdAt) }}</td>
            <td class="muted">{{ fmt(o.lastAccessedAt) }}</td>
            <td class="mono">{{ o.retentionDeadline || '冻结/无限期' }}</td>
            <td>
              <span v-if="o.litigationHold" class="tag tag-danger">争议冻结</span>
              <span v-else-if="!o.directoryAvailable" class="tag tag-danger">缺件</span>
              <span v-else-if="o.withdrawn" class="tag tag-muted">已退出</span>
              <span v-else class="tag tag-ok">在档</span>
            </td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import api from '../api'

const objects = ref([])
const tiers = ref([])
const rec = ref({})
const showRec = ref(false)
const filter = reactive({ category: '', tier: '' })
const categories = computed(() => [...new Set(objects.value.map(o => o.category))])
const totalGb = computed(() =>
  (objects.value.reduce((s, o) => s + Number(o.byteSize), 0) / 1e9).toFixed(1))
const failedCount = computed(() => objects.value.filter(o => o.checksumStatus === 'FAILED').length)
const holdCount = computed(() => objects.value.filter(o => o.litigationHold).length)
const filtered = computed(() => objects.value.filter(o =>
  (!filter.category || o.category === filter.category) &&
  (!filter.tier || o.tier === filter.tier)))

function gb(b) { return (Number(b) / 1e9).toLocaleString(undefined, { maximumFractionDigits: 2 }) }
function fmt(t) { return t ? String(t).slice(0, 16) : '' }
function checkTag(s) { return s === 'OK' ? 'tag-ok' : s === 'FAILED' ? 'tag-danger' : 'tag-warn' }

async function load() {
  objects.value = (await api.get('/catalog/objects')).data
  tiers.value = (await api.get('/catalog/tiers')).data
}
async function loadRec() { rec.value = (await api.get('/catalog/tier-recommendations')).data }
async function migrate(uid, targetTier) {
  await api.post('/catalog/migrate', { uid, targetTier, reason: '按访问热度自动分层' })
  await load(); await loadRec()
}
onMounted(async () => { await load(); await loadRec() })
</script>
