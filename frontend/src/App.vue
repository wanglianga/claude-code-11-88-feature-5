<template>
  <div v-if="!auth.isLogin" class="login-shell">
    <router-view />
  </div>
  <div v-else class="shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">湖仓</div>
        <div>
          <div class="brand-title">存储治理平台</div>
          <div class="brand-sub">Research Lakehouse Gov</div>
        </div>
      </div>
      <nav class="nav">
        <router-link v-for="m in menus" :key="m.path" :to="m.path" class="nav-item">
          <span class="nav-icon">{{ m.icon }}</span>{{ m.title }}
        </router-link>
      </nav>
      <div class="user-box">
        <div>{{ auth.user?.displayName }}（{{ auth.user?.role }}）</div>
        <button class="btn btn-ghost btn-sm" @click="auth.logout()">退出登录</button>
      </div>
    </aside>
    <main class="content">
      <header class="topbar">
        <h1>{{ $route.meta.title || '存储治理' }}</h1>
        <div class="topbar-meta">费率版本 RV-2026Q4 · 4h RTO 约束 · 1GB=10⁹B</div>
      </header>
      <section class="page">
        <router-view />
      </section>
    </main>
  </div>
</template>

<script setup>
import { useAuthStore } from './stores/auth'
const auth = useAuthStore()
const menus = [
  { path: '/catalog', title: '对象目录与分层', icon: '▤' },
  { path: '/lineage', title: '血缘 DAG 与共享', icon: '⇄' },
  { path: '/retention', title: '保留策略与传播', icon: '⛿' },
  { path: '/restore', title: '恢复计划与校验', icon: '↺' },
  { path: '/cost', title: '成本账与分摊', icon: '¥' },
  { path: '/snapshots', title: '归档快照回放', icon: '❏' },
  { path: '/cohort', title: '研究队列抽取', icon: '❂' },
  { path: '/projects', title: '项目审阅', icon: '⚲' }
]
</script>
