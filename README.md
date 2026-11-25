# QQBindMod

[![Forge 1.20.1](https://img.shields.io/badge/Forge-1.20.1-orange?style=flat-square)](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html)
[![NeoForge 1.21.1](https://img.shields.io/badge/NeoForge-1.21.1-yellow?style=flat-square)](https://projects.neoforged.net/)
[![Fabric](https://img.shields.io/badge/Fabric-1.20.1%20%7C%201.21.1-9cf?style=flat-square)](https://fabricmc.net/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)
[![Docs](https://img.shields.io/badge/Docs-GitHub%20Pages-2f855a?style=flat-square)](https://banxxx.github.io/QQBindMod/)

一个通过 HTTP API 与 QQ 机器人联动，实现玩家 QQ 号与游戏 ID 绑定的 Minecraft 服务端模组。支持 **Forge 1.20.1**、**NeoForge 1.21.1** 和 **Fabric 1.20.1 / 1.21.1**。

> 📚 **完整文档：<https://banxxx.github.io/QQBindMod/>**
>
> 第一次使用请从 [快速开始](https://banxxx.github.io/QQBindMod/guide/getting-started) 开始；配置字段、OneBot / QQ 官方接入、API 和排错都在文档站。开发阶段的历史记录已移到 [开发状态记录](https://banxxx.github.io/QQBindMod/dev/status)。

## 📖 简介

**QQBindMod** 是一个服务端模组。它内置轻量 HTTP API，供 QQ 机器人插件调用，完成 QQ 号与游戏 ID 的绑定/解绑、查询和白名单同步。

支持本地 JSON、中心数据库和混合模式。中心库模式可通过 MySQL / TiDB 兼容数据库实现跨服共享，同时保留本地模式用于单服或向后兼容。


## ✨ 功能特性

- 🔐 **QQ 绑定**：通过 `/api/bind` 绑定 QQ 号和游戏 ID，支持反向查询
- 🚫 **未绑定拦截**：未绑定的玩家无法进入服务器（可配置）
- ⚙️ **白名单同步**：绑定成功自动添加原版白名单，解绑自动移除
- 🌐 **跨服数据共享**：绑定数据存储于云端数据库，所有服务器共享同一份数据
- 📊 **玩家统计**：提供 `/api/stats` 获取玩家详细数据（移动距离、击杀、死亡等）
- 🖥️ **在线列表**：`/api/status` 返回当前在线玩家信息（含 UUID）
- 📈 **TPS 查询**：`/api/tps` 返回服务器当前 TPS
- 📢 **广播**：`/api/broadcast` 向全服发送消息
- 🔄 **命令支持**：游戏内 `/qqbind` 命令（重载配置、列表、解绑）
- 🧩 **多平台**：同时支持 Forge 1.20.1、NeoForge 1.21.1 和 Fabric 1.20.1 / 1.21.1
- 🛡️ **本地缓存**：混合模式下自动缓存查询结果，减少数据库请求，提高响应速度

## 🏗️ 项目架构

```text
QQBindMod/
├── common-src/                    # 平台无关的核心代码
│   ├── api/                       # HTTP API 服务（WebServer）
│   ├── core/                      # 绑定管理、命令执行
│   ├── storage/                   # JSON 存储及接口
│   ├── utils/                     # 统计工具
│   └── QQBindConfig.java          # 配置管理
├── docs/                          # VitePress 用户文档站
├── examples/                      # 配置模板
├── forge/                         # Forge 1.20.1 平台模块
│   ├── src/main/java/...forge/
│   │   ├── QQBindMod.java         # 模组入口
│   │   ├── ForgeServerProvider.java
│   │   ├── EventHandler.java
│   │   └── ServerCommands.java
│   └── src/main/resources/META-INF/mods.toml
├── neoforge/                      # NeoForge 1.21.1 平台模块
    ├── src/main/java/...neoforge/
    │   ├── QQBindMod.java         # 模组入口（构造器注入）
    │   ├── NeoForgeServerProvider.java
    │   ├── EventHandler.java
    │   └── ServerCommands.java
    └── src/main/resources/META-INF/neoforge.mods.toml
├── fabric-1.20.1/                 # Fabric 1.20.1 平台模块
└── fabric-1.21.1/                 # Fabric 1.21.1 平台模块
```

## 🔧 快速开始

### 环境要求

- Java 17（Forge / Fabric 1.20.1）/ Java 21（NeoForge / Fabric 1.21.1）
- Gradle（使用项目 wrapper）
- Minecraft 服务端（Forge / NeoForge / Fabric）

### 构建

```bash
git clone https://github.com/banxxx/QQBindMod.git
cd QQBindMod
```

**构建 Forge 版本：**
```bash
./gradlew :forge:build
```
JAR 文件位于 `forge/build/libs/qqbind-*-forge-1.20.1.jar`

**构建 NeoForge 版本：**
```bash
./gradlew :neoforge:build
```
JAR 文件位于 `neoforge/build/libs/qqbind-*-neoforge-1.21.1.jar`

**构建 Fabric 版本：**
```bash
./gradlew :fabric-1.20.1:build   # 1.20.1
./gradlew :fabric-1.21.1:build   # 1.21.1
```
JAR 位于对应模块的 `build/libs/` 下。

### 安装

1. 将对应平台的 JAR 文件放入服务端的 `mods` 文件夹
2. 启动服务器，模组会在 `config/qqbind/` 下生成默认配置文件 `qqbind-config.jsonc`（旧版 `qqbind-config.json` 仍可读取）
3. 修改配置文件中的 `apiToken`（**必须修改！**）、`onebot.qqGroup` 和 `httpPort`（默认 25566）
4. 按 [配置文档](https://banxxx.github.io/QQBindMod/guide/configuration) 选择 OneBot / QQ 官方模式和存储方式
5. 端口、存储模式、绑定平台或官方运行期参数修改后需要完整停启；普通字段可用 `/qqbind reload`

## ⚙️ 配置

配置文件路径：`config/qqbind/qqbind-config.jsonc`（旧版 `qqbind-config.json` 仍兼容）。新安装会生成一份包含 `onebot` 和 `qq_official` 两个平台段的 JSONC 模板。

- [配置文件字段与严格校验](https://banxxx.github.io/QQBindMod/guide/configuration)
- [OneBot 模式](https://banxxx.github.io/QQBindMod/guide/onebot)
- [QQ 官方模式](https://banxxx.github.io/QQBindMod/guide/qq-official)
- [存储与跨服](https://banxxx.github.io/QQBindMod/guide/storage)
- 配置模板：[`examples/qqbind-config.jsonc.example`](examples/qqbind-config.jsonc.example)、[`examples/qqbind-config-official.jsonc.example`](examples/qqbind-config-official.jsonc.example)

### 最少需要改的字段

| 字段 | 说明 |
| --- | --- |
| `apiToken` | 机器人插件访问 HTTP API 的 Bearer Token。必须是随机长字符串，不能保留默认占位值，否则 HTTP API 不启动。 |
| `onebot.qqGroup` | OneBot 模式下填真实 QQ 群号；既用于展示，也用于数据隔离。 |
| `httpPort` | HTTP API 端口，默认 `25566`。修改后需重启。 |
| `httpBindHost` | 监听地址，默认 `0.0.0.0`；建议改成 `127.0.0.1` 或内网地址。 |
| `bindingPlatform` | `onebot` 或 `qq_official`。切换平台必须完整停启。 |

最小 OneBot 配置：

```jsonc
{
  "configVersion": 2,
  "bindingPlatform": "onebot",
  "httpPort": 25566,
  "httpBindHost": "127.0.0.1",
  "apiToken": "replace-with-a-long-random-token",
  "onebot": {
    "qqGroup": "123456789",
    "serverId": "<SERVER_UUID>",
    "storageMode": "local"
  }
}
```

> `storageMode: local` 时两个平台都会自动忽略数据库字段，不会建立连接；改成 `hybrid` / `remote` 后才读取并校验。普通字段可用 `/qqbind reload`；端口、存储模式、绑定平台和官方运行期参数必须完整停启。

---

## API 文档

所有接口需要 Authorization: Bearer <apiToken> 头（如已配置）；`/api/console` 例外，使用独立的 `consoleToken`。

| 端点 | 方法 | 说明 |
|------|------|------|
| /api/bind | POST | 绑定 QQ 和游戏 ID（请求体：{"qq":"123","gameId":"player"}） |
| /api/unbind | POST | 解绑（请求体：{"gameId":"player"} 或 {"qq":"123"}） |
| /api/check | GET | 查询绑定状态（参数：?gameId=player 或 ?qq=123） |
| /api/status | GET | 获取在线玩家列表、TPS、延迟等 |
| /api/stats/{player} | GET | 获取指定玩家的详细统计 |
| /api/tps | GET | 获取当前 TPS |
| /api/broadcast | POST | 广播消息（请求体：{"message":"Hello"}） |
| /api/validate_token | GET | 验证绑定令牌（用于机器人端） |
| /api/cache/invalidate | POST | 清除指定玩家的缓存（用于机器人端主动刷新） |
| /api/console | POST | 以控制台身份执行命令并回显输出（请求体：{"command":"whitelist list","actor":"qq:123"}，需 `consoleToken`） |

> `/api/console` 等同于服务器控制台，默认关闭，只有配置了独立的 `consoleToken` 才会注册。完整的安全限制、硬禁命令、限流和请求示例见 [HTTP API 文档](https://banxxx.github.io/QQBindMod/guide/api)。

---

## 机器人集成

模组提供了完整的 HTTP API，推荐使用 AstrBot 的插件 [astrbot_plugin_mcsight](https://github.com/banxxx/astrbot_plugin_mcsight) 实现 QQ 绑定功能。

### 插件配置（新增中心数据库支持）

在 AstrBot 插件配置中，除了原有的 mod_api_token 等字段，新增中心数据库开关：

    {
      "use_central_db": true,
      "worker_api_url": "https://your-worker.workers.dev/api/query",
      "worker_api_token": "your-worker-api-token",
      "default_group_id": "123456789"
    }

- 当 use_central_db 为 true 时，机器人将直接调用 Worker API 写入/删除云端数据库，实现跨服绑定。
- 当 use_central_db 为 false 时，机器人回退到旧模式，仅调用模组 API（各服务器独立）。

### 支持的机器人命令（需配合插件）

- /绑定 或 /bind <6位令牌>：输入游戏内显示的令牌完成绑定（自动验证群昵称格式）
- /解绑 或 /unbind <游戏ID>：解绑（管理员可用）
- /查绑定 或 /check <游戏ID>：查询绑定状态
- /查绑定 QQ <QQ号>：通过 QQ 号查询绑定的游戏 ID
- /在线 或 /status：查看在线玩家
- /广播 <消息>：向全服广播消息
- /tps：查看服务器 TPS

---

## 🛠️ 开发指南

### 代码结构

- common-src/：所有平台无关代码，修改时注意不要引入平台特定 API
- `forge`/ / `neoforge`/ / `fabric-*`：平台特定入口、事件、命令注册
- 新增平台时参考现有模块结构

### 调试

- 在 IDEA 中分别以 :forge:runServer、:neoforge:runServer 或 :fabric-1.20.1:runServer 启动
- 默认监听 `0.0.0.0:25566`，可通过本地浏览器或 curl 测试 API
- 测试 API 示例：
  ```bash
  curl -H "Authorization: Bearer your-token" http://127.0.0.1:25566/api/check?gameId=test
  ```

### 文档

文档站源码在 `docs/`，使用 VitePress：

```bash
npm install
npm run docs:dev     # 本地预览
npm run docs:build   # 构建静态站点
```

推送到 `master` 后由 GitHub Actions 自动部署到 GitHub Pages。首次使用需在仓库 **Settings → Pages** 中把 Source 设为 **GitHub Actions**。

### 提交 PR

欢迎提交 Issue 和 Pull Request。请确保：

- 代码编译通过（对应加载器）
- 添加必要的注释
- 功能或配置变化同步更新 `docs/` 文档和 `CHANGELOG.md`

## 📄 许可证

本项目采用 **MIT License**，完全开源，允许任何人自由使用、修改、分发，包括商业用途，只需保留原始版权声明。详见 [LICENSE](LICENSE) 文件。

## 🙏 致谢

- Minecraft Forge & NeoForge & Fabric 社区
- [AstrBot](https://github.com/AstrBotDevs/AstrBot) 提供机器人框架支持
- 所有使用和反馈的玩家
