---
layout: home

hero:
  name: QQBindMod
  text: Minecraft QQ 绑定模组
  tagline: 通过 HTTP API 连接 QQ 机器人，把 QQ 号与游戏 ID 绑定起来，并自动同步原版白名单。
  actions:
    - theme: brand
      text: 快速开始
      link: /guide/getting-started
    - theme: alt
      text: 查看 GitHub
      link: https://github.com/banxxx/QQBindMod

features:
  - title: 双平台接入
    details: 支持 OneBot 群机器人和 QQ 官方机器人，两个平台使用同一份配置，切换时只改 bindingPlatform。
  - title: 本地 / 远程 / 混合存储
    details: 单服可以用本地 JSON；需要跨服时接入 MySQL / TiDB 中心库；混合模式兼顾缓存与降级。
  - title: 白名单自动同步
    details: 绑定成功自动加入原版白名单，解绑自动移除，并可拦截未绑定玩家。
  - title: HTTP API
    details: 绑定、解绑、查询、在线列表、玩家统计、TPS、广播，以及可选的控制台命令接口。
  - title: 多加载器
    details: Forge 1.20.1、NeoForge 1.21.1、Fabric 1.20.1 / 1.21.1 使用同一套核心逻辑。
  - title: 严格的配置校验
    details: JSONC 注释、字段类型与范围校验、跨平台字段隔离；配置写错时整份拒绝，不会半套生效。
---

## 从这里开始

- 第一次安装：阅读 [快速开始](/guide/getting-started)，完成模组安装和必改配置。
- 不确定怎么填配置：先看 [配置文件](/guide/configuration)，再按平台查看 [OneBot 模式](/guide/onebot) 或 [QQ 官方模式](/guide/qq-official)。
- 需要跨服共享：阅读 [存储与跨服](/guide/storage)。
- 机器人/插件对接：阅读 [HTTP API](/guide/api) 和 [命令](/guide/commands)。
- 启动失败或玩家看不到绑定提示：直接看 [常见问题](/guide/faq)。

## 项目状态

QQ 官方模式仍在持续验证中。生产环境使用前，请先在测试服完整验证绑定、解绑、白名单、重启恢复和中心库同步流程。最新变化见 [更新日志](https://github.com/banxxx/QQBindMod/blob/master/CHANGELOG.md)。
