<template>
  <div>
    <div class="card">
      <h2>项目总览</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>项目编码</th><th>名称</th><th>状态</th><th>对象数</th><th>总字节</th><th>操作</th></tr></thead>
          <tbody>
          <tr v-for="p in projects" :key="p.code">
            <td class="mono">{{ p.code }}</td><td>{{ p.name }}</td>
            <td><span class="tag" :class="p.status === 'ACTIVE' ? 'tag-ok' : 'tag-muted'">{{ p.status }}</span></td>
            <td>{{ p.objectCount }}</td><td>{{ (p.totalBytes / 1e9).toFixed(2) }} GB</td>
            <td class="flex">
              <button class="btn btn-sm" @click="review(p.code)">审阅</button>
              <button v-if="p.status === 'ACTIVE'" class="btn btn-sm btn-danger" @click="withdraw(p.code)">项目退出</button>
              <button v-else class="btn btn-sm btn-ok" @click="reactivate(p.code)">恢复活跃</button>
            </td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="rv" class="card">
      <h2>项目审阅：{{ rv.projectCode }}</h2>
      <div class="grid grid-3" style="margin-bottom:14px">
        <div class="stat"><div class="k">名下对象</div><div class="v">{{ rv.objects.length }}</div></div>
        <div class="stat"><div class="k">对外共享引用</div><div class="v" :class="{fail: rv.sharedOutReferences.length}">{{ rv.sharedOutReferences.length }}</div></div>
        <div class="stat"><div class="k">引用他方对象</div><div class="v">{{ rv.sharedInReferences.length }}</div></div>
      </div>

      <h2>名下对象保留状态</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>对象</th><th>类别</th><th>容量</th><th>层</th><th>校验</th><th>保留截止</th><th>冻结</th><th>在档</th></tr></thead>
          <tbody>
          <tr v-for="o in rv.objects" :key="o.uid">
            <td class="mono">{{ o.uid }}</td><td>{{ o.category }}</td>
            <td>{{ (o.byteSize / 1e9).toFixed(2) }}GB</td><td>{{ o.tier }}</td>
            <td><span class="tag" :class="o.checksumStatus === 'OK' ? 'tag-ok' : 'tag-danger'">{{ o.checksumStatus }}</span></td>
            <td class="mono">{{ o.retentionDeadline || '无限期' }}</td>
            <td>{{ o.litigationHold ? '是' : '否' }}</td>
            <td>{{ o.directoryAvailable ? '是' : '否（缺件）' }}</td>
          </tr>
          </tbody>
        </table>
      </div>

      <h2 style="margin-top:14px">共享引用（冻结份额）</h2>
      <div class="grid grid-2">
        <div>
          <b class="muted">本项目对外提供</b>
          <div class="table-wrap" style="margin-top:6px">
            <table><thead><tr><th>对象</th><th>引用方</th><th>份额</th><th>依据</th></tr></thead>
              <tbody><tr v-for="(s, i) in rv.sharedOutReferences" :key="'o'+i">
                <td class="mono">{{ s.uid }}</td><td>{{ s.referencedByProject }}</td>
                <td>{{ (s.frozenShare * 100).toFixed(0) }}%</td>
                <td>{{ s.shareBasis === 'REFERENCE' ? '引用' : '使用' }}</td>
              </tr>
              <tr v-if="!rv.sharedOutReferences.length"><td colspan="4" class="muted">无</td></tr></tbody>
            </table>
          </div>
        </div>
        <div>
          <b class="muted">本项目引用他方</b>
          <div class="table-wrap" style="margin-top:6px">
            <table><thead><tr><th>对象</th><th>属主</th><th>份额</th><th>依据</th></tr></thead>
              <tbody><tr v-for="(s, i) in rv.sharedInReferences" :key="'i'+i">
                <td class="mono">{{ s.uid }}</td><td>{{ s.ownedByProject }}</td>
                <td>{{ (s.frozenShare * 100).toFixed(0) }}%</td>
                <td>{{ s.shareBasis === 'REFERENCE' ? '引用' : '使用' }}</td>
              </tr>
              <tr v-if="!rv.sharedInReferences.length"><td colspan="4" class="muted">无</td></tr></tbody>
            </table>
          </div>
        </div>
      </div>

      <h2 style="margin-top:14px">项目相关治理事件（传播记录）</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>事件</th><th>载荷</th><th>传播对象</th><th>时间</th></tr></thead>
          <tbody>
          <tr v-for="(e, i) in rv.governanceEvents" :key="i">
            <td><span class="tag tag-info">{{ e.eventType }}</span></td>
            <td class="mono muted" style="white-space:normal;max-width:300px">{{ JSON.stringify(e.payload) }}</td>
            <td class="mono" style="white-space:normal;max-width:300px">{{ (e.affectedUids || []).join(', ') }}</td>
            <td class="muted">{{ String(e.createdAt).slice(0, 16) }}</td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="wd" class="card">
      <h2>项目退出结果（共享对象保护）</h2>
      <div class="hint fail" v-if="wd.protectedSharedObjects?.length">
        <b>以下共享对象仍被其他活跃项目引用，不得整体删除：</b>
        <table style="margin-top:6px">
          <tr v-for="p in wd.protectedSharedObjects" :key="p.uid">
            <td class="mono">{{ p.uid }}</td><td>{{ p.verdict }}</td>
            <td>活跃引用项目数={{ p.activeReferencingProjects }}</td><td class="muted">{{ p.reason }}</td>
          </tr>
        </table>
      </div>
      <div>独占对象已标记退出（仍受保留期约束）：<span class="mono">{{ wd.withdrawnObjects.join(', ') }}</span></div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../api'

const projects = ref([])
const rv = ref(null)
const wd = ref(null)

async function load() { projects.value = (await api.get('/projects')).data }
async function review(code) { rv.value = (await api.get(`/projects/${code}/review`)).data; wd.value = null }
async function withdraw(code) {
  if (!confirm(`确认项目 ${code} 退出？共享对象将被保护不整体删除。`)) return
  wd.value = (await api.post(`/projects/${code}/withdraw`, { reason: '界面发起项目退出' })).data
  await load(); await review(code)
}
async function reactivate(code) {
  await api.post(`/projects/${code}/reactivate`); await load(); await review(code)
}
onMounted(load)
</script>
