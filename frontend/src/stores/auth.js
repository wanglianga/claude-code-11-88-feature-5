import { defineStore } from 'pinia'
import api from '../api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('lakegov_token') || '',
    user: JSON.parse(localStorage.getItem('lakegov_user') || 'null')
  }),
  getters: {
    isLogin: (s) => !!s.token,
    isAdmin: (s) => s.user?.role === 'ADMIN'
  },
  actions: {
    async login(username, password) {
      const { data } = await api.post('/auth/login', { username, password })
      this.token = data.token
      this.user = data.user
      localStorage.setItem('lakegov_token', data.token)
      localStorage.setItem('lakegov_user', JSON.stringify(data.user))
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('lakegov_token')
      localStorage.removeItem('lakegov_user')
    }
  }
})
