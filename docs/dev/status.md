# 开发状态记录

::: info 历史记录
本页保留开发阶段的构建/验证记录，方便回溯。用户部署请以 [快速开始](/guide/getting-started) 和 [配置文件](/guide/configuration) 为准。
:::

## 2026-11-19 完整开发构建

QQ 官方兼容开发已补齐原生管理员解绑/list/operation retry/cancel、原请求人显式未决恢复、持久写命令防重、remote/local 权威及原子状态。配套插件本人排行和各服后台管理也已接入；四个最新模块完整构建/依赖打包通过，源码与构建快照一致，Jar 中的 Mixin/refmap、新入口和 Java 目标已核对。

最新产物：`build/qqofficial-development/2026-11-19-complete-development/`，详见其中 `BUILD_INFO.md` 和开发记录。正式 QQ / MC / SQL / 浏览器验收尚未完成，不当作已上线版本。部署、秘密配置、DDL 和备份由用户按插件 `QQ_OFFICIAL_DELIVERY.md` 自行执行。

## 2026-11-20 源码更新

已修中心 operations 读取失败时本服持久预占回执分流，不据本地事实提交中心变更；新增只读 `check?view=active_roster` 分页供插件管理员绑定检测，游标绑定本服/查询模式/limit；`/api/status` 显示当前内存就绪状态。配套插件已修现行 QQ 消息协议并补管理/查询差异。

当时按要求未测试、未重新构建四模块，2026-11-19 的旧 Jar 不包含该轮修改，`SOURCE_HASHES` 也不能当作该轮源码一致证明。后续需集中必要验证和重新打包，再按插件 `QQ_OFFICIAL_TEST_CHECKLIST.md` 验收；真实环境仍未验收。

## 构建与测试说明

正式构建使用仓库中的 Gradle Wrapper 和各模块 `build.gradle`；运行 Gradle 前由用户自行准备构建属性。此前的隔离构建、缓存快照和交付打包脚本依赖开发电脑路径，现仅本机保留并从 Git 过滤。

核心 Java 回归、测试替身和合成夹具保留在仓库；`tests/` 通过 `.gitattributes` 从发布源码归档排除，不进入模组运行 Jar。`build/`、`.scratch/`、缓存及生成 Jar 继续忽略。

## 用户文档入口

- [快速开始](/guide/getting-started)
- [配置文件](/guide/configuration)
- [OneBot 模式](/guide/onebot)
- [QQ 官方模式](/guide/qq-official)
- [常见问题](/guide/faq)
