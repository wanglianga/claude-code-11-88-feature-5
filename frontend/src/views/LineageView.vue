<template>
  <div>
    <div class="card">
      <h2>血缘 DAG（输入 → 输出；虚线为跨项目共享引用）</h2>
      <div class="legend">
        <span><i class="dot" style="background:#4f8cff"></i>研究A 对象</span>
        <span><i class="dot" style="background:#37d0a8"></i>研究B 对象</span>
        <span><i class="dot" style="background:#f5a623"></i>共享对象</span>
        <span><i class="dot" style="background:#ef5b6b"></i>校验失败/缺件</span>
      </div>
      <div class="svg-wrap">
        <svg :width="svgWidth" :height="svgHeight">
          <defs>
            <marker id="arrow" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto">
              <path d="M0,0 L9,3 L0,6 Z" fill="#8fa2bf" />
            </marker>
          </defs>
          <line v-for="(e, i) in edgeGeom" :key="'e'+i"
                :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2"
                :stroke="e.shared ? '#f5a623' : '#8fa2bf'"
                :stroke-dasharray="e.shared ? '6 4' : ''"
                stroke-width="1.6" marker-end="url(#arrow)" />
          <g v-for="n in nodeGeom" :key="n.uid" :transform="`translate(${n.x},${n.y})`">
            <rect width="170" height="56" rx="9" :fill="nodeColor(n)" stroke="#2b3a55" />
            <text x="10" y="22" fill="#fff" font-size="12" class="mono">{{ n.uid }}</text>
            <text x="10" y="42" fill="#c6d3e8" font-size="11">{{ n.category }} · {{ n.tier }}</text>
          </g>
        </svg>
      </div>
      <div class="table-wrap" style="margin-top:14px">
        <table>
          <thead><tr><th>输入</th><th>输出</th><th>版本</th><th>配方</th><th>软件版本</th><th>共享引用</th></tr></thead>
          <tbody>
          <tr v-for="e in graph.edges || []" :key="e.id">
            <td class="mono">{{ e.fromUid }}</td><td class="mono">{{ e.toUid }}</td>
            <td>{{ e.edgeVersion }}</td><td class="mono">{{ e.recipe }}</td>
            <td>{{ e.softwareVersion }}</td>
            <td><span class="tag" :class="e.sharedReference ? 'tag-warn' : 'tag-muted'">
              {{ e.sharedReference ? '跨项目共享' : '项目内' }}</span></td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="card">
      <h2>共享引用与冻结份额</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>共享对象</th><th>名称</th><th>引用项目</th><th>冻结份额</th><th>份额依据</th></tr></thead>
          <tbody>
          <template v-for="g in shared" :key="g.uid">
            <tr v-for="(a, i) in g.allocations" :key="g.uid + i">
              <td v-if="i === 0" class="mono" :rowspan="g.allocations.length">{{ g.uid }}</td>
              <td v-if="i === 0" :rowspan="g.allocations.length">{{ g.name }}</td>
              <td>{{ a.projectCode }}</td>
              <td class="mono">{{ (a.frozenShare * 100).toFixed(0) }}%</td>
              <td>{{ a.shareBasis === 'REFERENCE' ? '引用份额' : '使用份额' }}</td>
            </tr>
          </template>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import api from '../api'

const graph = ref({ nodes: [], edges: [] })
const shared = ref([])
const COL_W = 220, ROW_H = 90, MARGIN_X = 30, MARGIN_Y = 24, NODE_W = 170, NODE_H = 56

// 以最长路径计算层级（源在左，派生结果在右）
const levels = computed(() => {
  const nodes = graph.value.nodes || []
  const edges = graph.value.edges || []
  const indeg = new Map(nodes.map(n => [n.uid, 0]))
  const adj = new Map(nodes.map(n => [n.uid, []]))
  edges.forEach(e => { adj.get(e.fromUid).push(e.toUid); indeg.set(e.toUid, (indeg.get(e.toUid) || 0) + 1) })
  const depth = new Map(nodes.map(n => [n.uid, 0]))
  const queue = nodes.filter(n => (indeg.get(n.uid) || 0) === 0).map(n => n.uid)
  while (queue.length) {
    const u = queue.shift()
    for (const v of adj.get(u) || []) {
      depth.set(v, Math.max(depth.get(v), depth.get(u) + 1))
      indeg.set(v, indeg.get(v) - 1)
      if (indeg.get(v) === 0) queue.push(v)
    }
  }
  const byLevel = new Map()
  nodes.forEach(n => {
    const d = depth.get(n.uid) || 0
    if (!byLevel.has(d)) byLevel.set(d, [])
    byLevel.get(d).push(n)
  })
  return byLevel
})

const nodeGeom = computed(() => {
  const out = []
  for (const [d, ns] of levels.value) {
    ns.forEach((n, i) => out.push({ ...n, x: MARGIN_X + d * COL_W, y: MARGIN_Y + i * ROW_H }))
  }
  return out
})
const edgeGeom = computed(() => {
  const pos = new Map(nodeGeom.value.map(n => [n.uid, n]))
  return (graph.value.edges || []).map(e => {
    const a = pos.get(e.fromUid), b = pos.get(e.toUid)
    return {
      x1: a.x + NODE_W, y1: a.y + NODE_H / 2,
      x2: b.x, y2: b.y + NODE_H / 2, shared: e.sharedReference
    }
  })
})
const svgWidth = computed(() => MARGIN_X * 2 + (Math.max(...[...levels.value.keys()], 0) + 1) * COL_W)
const svgHeight = computed(() => {
  let maxRows = 1
  for (const ns of levels.value.values()) maxRows = Math.max(maxRows, ns.length)
  return MARGIN_Y * 2 + maxRows * ROW_H
})
function nodeColor(n) {
  if (n.checksumStatus === 'FAILED') return '#7a2633'
  const sharedUids = new Set(shared.value.map(g => g.uid))
  if (sharedUids.has(n.uid)) return '#8a6116'
  return n.projectCode === 'PROJ-B' ? '#176b58' : '#244a86'
}
onMounted(async () => {
  graph.value = (await api.get('/catalog/lineage')).data
  shared.value = (await api.get('/catalog/shared')).data
})
</script>
