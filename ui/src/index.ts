import { definePlugin } from '@halo-dev/ui-shared'
import { markRaw } from 'vue'
import ClawBotMenuIcon from '@/components/ClawBotMenuIcon.vue'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/clawbot/records',
        name: 'ClawBotRecords',
        component: () => import('./views/RecordsView.vue'),
        meta: { title: 'ClawBot 记录', permissions: ['*'], searchable: true, hideFooter: true },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/clawbot/commands',
        redirect: (to) => ({ path: '/clawbot/records', query: to.query }),
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/clawbot',
        name: 'ClawBotConnection',
        component: () => import('./views/ConnectionView.vue'),
        meta: {
          title: '微信 ClawBot',
          hideFooter: true,
          permissions: ['*'],
          searchable: true,
          menu: {
            name: '微信 ClawBot',
            group: '工具',
            icon: markRaw(ClawBotMenuIcon),
            priority: 0,
          },
        },
      },
    },
  ],
  extensionPoints: {},
})
