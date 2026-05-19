import { createRouter, createWebHistory } from 'vue-router'
import EventList from '../pages/EventList.vue'
import EventDetail from '../pages/EventDetail.vue'
import Login from '../pages/Login.vue'
import MyOrders from '../pages/MyOrders.vue'
import AiChat from '../pages/AiChat.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/events' },
    { path: '/events', component: EventList },
    { path: '/events/:id', component: EventDetail, props: true },
    { path: '/login', component: Login },
    { path: '/orders', component: MyOrders, meta: { requiresAuth: true } },
    { path: '/ai', component: AiChat, meta: { requiresAuth: true } }
  ]
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !localStorage.getItem('accessToken')) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
})

export default router
