<template>
  <div>
    <div class="card">
      <h2>归档快照（固定目录水位 · 依赖 · 费率版本 · 决定回放）</h2>
      <div class="toolbar">
        <button class="btn" @click="create">生成当前快照</button>
        <button class="btn btn-ghost btn-sm" @click="load">刷新列表</button>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>#</th><th>快照日</th><th>费率版本</th><th>目录对象</th><th>血缘边</th>
            <th>账单条目</th><th>治理决定</th><th>生成时间</th><th>操作</th></tr></thead>
          <tbody>
          <tr v-for="s in snapshots" :key="s.snapshotId">
            <td>{{ s.snapshotId }}</td><td class="mono">{{ s.snapshotDate }}</td>
            <td><span class="tag tag-info">{{ s.rateVersion }}</span></td>
            <td>{{ s.catalogObjects }}</td><td>{{ s.lineageEdges }}</td>
            <td>{{ s.billingEntries }}</td><td>{{ s.decisions }}</td>
            <td class="muted">{{ String(s.createdAt).slice(0, 16) }}</td>
            <td><button class="btn btn-sm" @click="replay(s.snapshotId)">回放</button></td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="detail" class="card">
      <h2>快照 #{{ detail.snapshotId }} 回放 —— 当时为何迁移、保留或计费</h2>
      <div class="toolbar">
        <button class="btn btn-sm btn-ghost" @click="tab = 'decisions'">治理决定</button>
        <button class="btn btn-sm btn-ghost" @click="tab = 'catalog'">目录水位</button>
        <button class="btn btn-sm btn-ghost" @click="tab = 'lineage'">依赖水位</button>
        <button class="btn btn-sm btn-ghost" @click="tab = 'billing'">账单水位</button>
      </div>
      <div v-if="tab === 'decisions'">
        <div class="table-wrap">
          <table>
            <thead><tr><th>类型</th><th>对象/载荷</th><th>理由/时间</th></tr></thead>
            <tbody>
            <tr v-for="(d, i) in detail.decisions" :key="i">
              <td><span class="tag tag-info">{{ d.kind }}</span></td>
              <td class="mono" style="white-space:normal;max-width:420px">
                {{ d.uid || JSON.stringify(d.payload || {}) }}
                <div v-if="d.affectedUids" class="muted">→ {{ d.affectedUids.join(', ') }}</div>
              </td>
              <td class="muted" style="white-space:normal;max-width:360px">
                {{ d.reason || d.disposition || '' }} {{ d.decidedAt ? String(d.decidedAt).slice(0,16) : '' }}
              </td>
            </tr>
            </tbody>
          </table>
        </div>
      </div>
      <pre v-else class="json">{{ JSON.stringify(detail[tab], null, 2) }}</pre>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../api'

const snapshots = ref([])
const detail = ref(null)
const tab = ref('decisions')

async function load() { snapshots.value = (await api.get('/snapshots')).data }
async function create() { await api.post('/snapshots'); await load() }
async function replay(id) { detail.value = (await api.get(`/snapshots/${id}/replay`)).data; tab.value = 'decisions' }
onMounted(load)
</script>
