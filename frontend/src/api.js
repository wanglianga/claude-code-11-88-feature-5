import axios from 'axios'

const api = axios.create({ baseURL: '/api', timeout: 20000 })

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('lakegov_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use(
  (resp) => resp,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('lakegov_token')
      if (location.hash !== '#/login') location.hash = '#/login'
    } else if (error.response?.status === 403) {
      window.alert('权限不足：REVIEWER 审阅人账号为只读角色，请使用 admin 账号执行写操作。')
    } else if (error.response?.data?.message) {
      window.alert(error.response.data.message)
    }
    return Promise.reject(error)
  }
)

export default api
