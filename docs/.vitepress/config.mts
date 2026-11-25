import { defineConfig } from 'vitepress'

const base = process.env.DOCS_BASE || '/QQBindMod/'
const repo = 'https://github.com/banxxx/QQBindMod'

export default defineConfig({
  lang: 'zh-CN',
  title: 'QQBindMod',
  description: 'Minecraft QQ 绑定模组：把 QQ 号与游戏 ID 绑定起来，并同步白名单。',
  base,
  cleanUrls: false,
  srcExclude: ['QQ_OFFICIAL_DEVELOPMENT.md'],
  lastUpdated: true,
  head: [
    ['meta', { name: 'theme-color', content: '#2f855a' }],
    ['meta', { name: 'og:type', content: 'website' }],
    ['meta', { name: 'og:title', content: 'QQBindMod 文档' }],
    ['meta', { name: 'og:description', content: 'QQBindMod 安装、配置、平台接入与 API 文档。' }]
  ],
  themeConfig: {
    nav: [
      { text: '指南', link: '/guide/getting-started' },
      { text: '配置', link: '/guide/configuration' },
      { text: 'OneBot', link: '/guide/onebot' },
      { text: 'QQ 官方', link: '/guide/qq-official' },
      { text: 'API', link: '/guide/api' },
      { text: '常见问题', link: '/guide/faq' }
    ],
    sidebar: {
      '/guide/': [
        {
          text: '开始使用',
          items: [
            { text: '快速开始', link: '/guide/getting-started' },
            { text: '配置文件', link: '/guide/configuration' }
          ]
        },
        {
          text: '平台接入',
          items: [
            { text: 'OneBot 模式', link: '/guide/onebot' },
            { text: 'QQ 官方模式', link: '/guide/qq-official' }
          ]
        },
        {
          text: '进阶',
          items: [
            { text: '存储与跨服', link: '/guide/storage' },
            { text: 'HTTP API', link: '/guide/api' },
            { text: '游戏内与机器人命令', link: '/guide/commands' }
          ]
        },
        {
          text: '其他',
          items: [
            { text: '常见问题', link: '/guide/faq' },
            { text: '开发指南', link: '/guide/development' },
            { text: '开发状态记录', link: '/dev/status' }
          ]
        }
      ],
      '/dev/': [
        {
          text: '开发',
          items: [
            { text: '开发状态记录', link: '/dev/status' },
            { text: '开发指南', link: '/guide/development' }
          ]
        }
      ]
    },
    socialLinks: [{ icon: 'github', link: repo }],
    search: {
      provider: 'local',
      options: {
        translations: {
          button: { buttonText: '搜索文档', buttonAriaLabel: '搜索文档' },
          modal: {
            noResultsText: '没有找到结果',
            resetButtonTitle: '清除查询',
            footer: {
              selectText: '选择',
              navigateText: '切换',
              closeText: '关闭'
            }
          }
        }
      }
    },
    editLink: {
      pattern: `${repo}/edit/master/docs/:path`,
      text: '在 GitHub 上编辑此页'
    },
    lastUpdated: {
      text: '最后更新于',
      formatOptions: { dateStyle: 'short', timeStyle: 'short' }
    },
    outline: { level: [2, 3], label: '本页目录' },
    docFooter: { prev: '上一页', next: '下一页' },
    returnToTopLabel: '回到顶部',
    sidebarMenuLabel: '菜单',
    darkModeSwitchLabel: '主题',
    lightModeSwitchLabel: '主题',
    langMenuLabel: '语言',
    footer: {
      message: '基于 MIT License 发布',
      copyright: 'QQBindMod · Minecraft QQ 绑定模组'
    }
  }
})
