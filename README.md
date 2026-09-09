# QQBindMod

[![Forge 1.20.1](https://img.shields.io/badge/Forge-1.20.1-orange?style=flat-square)](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html)
[![NeoForge 1.21.1](https://img.shields.io/badge/NeoForge-1.21.1-yellow?style=flat-square)](https://projects.neoforged.net/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)

一个通过 HTTP API 与 QQ 机器人联动，实现玩家 QQ 号与游戏 ID 绑定的 Minecraft 模组。支持 **Forge 1.20.1**、**NeoForge 1.21.1** 和 **Fabric 1.20.1 / 1.21.1**。

## 📖 简介

**QQBindMod** 是一个服务端模组，它内置了一个轻量级 HTTP 服务器，为机器人程序（如基于 AstrBot 的 QQ 机器人）提供 RESTful API，用于绑定/解绑 QQ 号与游戏内玩家 ID，并自动同步原版白名单。

**新增核心特性**：支持 **“中心数据库模式”**，通过 Cloudflare D1 或兼容的 HTTP API 数据库实现跨服数据共享，真正实现 **“一次绑定，全服通用”**。同时保留了原有的 **“本地模式”** 以实现向后兼容。

## ✨ 功能特性

- 🔐 **QQ 绑定**：通过 `/api/bind` 绑定 QQ 号和游戏 ID，支持反向查询
- 🚫 **未绑定拦截**：未绑定的玩家无法进入服务器（可配置）
- ⚙️ **白名单同步**：绑定成功自动添加原版白名单，解绑自动移除
- 🌐 **跨服数据共享**（新模式）：绑定数据存储于云端数据库，所有服务器共享同一份数据
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
├── forge/                         # Forge 1.20.1 平台模块
│   ├── src/main/java/...forge/
│   │   ├── QQBindMod.java         # 模组入口
│   │   ├── ForgeServerProvider.java
│   │   ├── EventHandler.java
│   │   └── ServerCommands.java
│   └── src/main/resources/META-INF/mods.toml
└── neoforge/                      # NeoForge 1.21.1 平台模块
    ├── src/main/java/...neoforge/
    │   ├── QQBindMod.java         # 模组入口（构造器注入）
    │   ├── NeoForgeServerProvider.java
    │   ├── EventHandler.java
    │   └── ServerCommands.java
    └── src/main/resources/META-INF/neoforge.mods.toml
└── fabric-1.20.1/                 # Fabric 1.20.1 平台模块
└── fabric-1.21.1/                 # Fabric 1.21.1 平台模块
```

## 🔧 快速开始

### 环境要求

- Java 17（Forge / Fabric 1.20.1）/ Java 21（NeoForge / Fabric 1.21.1）
- Gradle（使用项目 wrapper）
- Minecraft 服务端（Forge / NeoForge / Fabric）

### 构建

```bash
git clone https://github.com/yourusername/QQBindMod.git
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
JAR 位于对应子项目的 `fabric/build/libs/qqbind-*-fabric-1.2*.1.jar` 下。

### 安装

1. 将对应平台的 JAR 文件放入服务端的 `mods` 文件夹
2. 启动服务器，模组会在 `config/qqbind/` 下生成默认配置文件 `qqbind-config.json`
3. 修改配置文件中的 `apiToken`（**必须修改！**）和 `httpPort`（默认 25566）
4. 根据需要设置存储模式（详见下方配置说明）
5. 重启服务器生效

### 配置示例

```json
{
  "httpPort": 25566,
  "apiToken": "your-strong-random-token",
  "enableWhitelistCheck": true,
  "dataFilePath": "qqbind/bindings.json",
  "serverId": "my-server",
  "qqGroup": "123456789",
  "titleTemplate": "§c您尚未绑定游戏ID！",
  "subtitleTemplate": "§e请加入QQ群 {qqGroup} 发送 §b/绑定 {token} §e完成绑定。",
  "actionBarTemplate": "§c您尚未绑定游戏ID！请加入QQ群 {qqGroup} 发送 §b/绑定 {token}",
  "bindSuccessTitle": "§a绑定成功！",
  "bindSuccessSubtitle": "§e祝您游戏愉快",
  "bindSuccessActionBar": "§a已解除限制，您可以正常游戏了",
  "storageMode": "hybrid",          // "local" | "remote" | "hybrid"
  "dbApiUrl": "https://your-worker.workers.dev/api/query",
  "dbApiToken": "your-worker-api-token",
  "cacheTtlSeconds": 60
}
```

### 存储模式说明

| 模式 | 说明 | 适用场景 |
| :--- | :--- | :--- |
| local | 仅使用本地 JSON 文件（bindings.json）存储绑定数据，各服务器独立。 | 传统单服部署，或无需跨服共享。 |
| remote | 仅使用远程数据库（通过 dbApiUrl 指向的 HTTP API，如 Cloudflare D1），不保留本地 JSON 备份。 | 完全依赖云数据库，追求数据强一致性。 |
| hybrid | 推荐模式。优先从远程数据库读写，同时保留本地 JSON 作为缓存和降级备份。查询时先查内存缓存（TTL 可配置），未命中则请求远程数据库并更新缓存。写入时同时更新远程和本地。 | 追求高可用性，容忍短暂网络故障，实现跨服数据共享。 |

### 新增配置项释义

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| storageMode | string | 否 | 存储模式，默认 "hybrid"。 |
| dbApiUrl | string | 仅当模式非 local 时 | 远程数据库 API 的完整 URL（需实现文档中定义的协议）。 |
| dbApiToken | string | 仅当模式非 local 时 | 用于验证远程 API 请求的 Bearer Token。 |
| cacheTtlSeconds | integer | 否 | 内存缓存有效期（秒），默认 60 秒。 |

重要提示：若要启用跨服数据共享，所有需要同步数据的服务器必须：
- 使用相同的 dbApiUrl 和 dbApiToken
- 配置相同的 qqGroup（即相同的 QQ 群号，用于数据隔离）
- 将 storageMode 设为 hybrid 或 remote

---

## API 文档

所有接口需要 Authorization: Bearer <apiToken> 头（如已配置）。

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

---

## 机器人集成

模组提供了完整的 HTTP API，推荐使用 AstrBot 的插件 astrbot_plugin_mcsight 实现 QQ 绑定功能。

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

## 发布的 Artifacts

GitHub Release 会自动附上以下 JAR 文件：

- qqbind-<version>-forge-1.20.1.jar
- qqbind-<version>-neoforge-1.21.1.jar
- qqbind-<version>-fabric-1.20.1.jar
- qqbind-<version>-fabric-1.21.1.jar

## 🛠️ 开发指南

### 代码结构

- common-src/：所有平台无关代码，修改时注意不要引入平台特定 API
- `forge`/ / `neoforge`/ / `fabric-*`：平台特定入口、事件、命令注册
- 新增平台时参考现有模块结构

### 调试

- 在 IDEA 中分别以 :forge:runServer、:neoforge:runServer 或 :fabric-1.20.1:runServer 启动
- 端口 25566 默认绑定所有接口，可通过本地浏览器测试 API
- 测试 API 示例：
  ```bash
  curl -H "Authorization: Bearer your-token" http://127.0.0.1:25566/api/check?gameId=test
  ```

### 提交 PR

欢迎提交 Issue 和 Pull Request。请确保：

- 代码编译通过（两个平台）
- 添加必要的注释
- 更新 README（如果功能有变化）

## 📄 许可证

本项目采用 **MIT License**，完全开源，允许任何人自由使用、修改、分发，包括商业用途，只需保留原始版权声明。详见 [LICENSE](LICENSE) 文件。

## 🙏 致谢

- Minecraft Forge & NeoForge & Fabric 社区
- [AstrBot](https://github.com/Soulter/AstrBot) 提供机器人框架支持
- 所有使用和反馈的玩家