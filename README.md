# QQBindMod

[![Forge 1.20.1](https://img.shields.io/badge/Forge-1.20.1-orange?style=flat-square)](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html)
[![NeoForge 1.21.1](https://img.shields.io/badge/NeoForge-1.21.1-yellow?style=flat-square)](https://projects.neoforged.net/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)

一个通过 HTTP API 与 QQ 机器人联动，实现玩家 QQ 号与游戏 ID 绑定的 Minecraft 模组。支持 Forge 1.20.1 和 NeoForge 1.21.1。

## 📖 简介

**QQBindMod** 是一个服务端模组，它内置了一个轻量级 HTTP 服务器，为机器人程序（如基于 AstrBot 的 QQ 机器人）提供 RESTful API，用于绑定/解绑 QQ 号与游戏内玩家 ID，并自动同步原版白名单。

同时，模组还提供了在线玩家列表、玩家统计、TPS、广播等辅助功能，方便机器人实现更多管理功能。

## ✨ 功能特性

- 🔐 **QQ 绑定**：通过 `/api/bind` 绑定 QQ 号和游戏 ID，支持反向查询
- 🚫 **未绑定拦截**：未绑定的玩家无法进入服务器（可配置）
- ⚙️ **白名单同步**：绑定成功自动添加原版白名单，解绑自动移除
- 📊 **玩家统计**：提供 `/api/stats` 获取玩家详细数据（移动距离、击杀、死亡等）
- 🖥️ **在线列表**：`/api/status` 返回当前在线玩家信息（含 UUID）
- 📈 **TPS 查询**：`/api/tps` 返回服务器当前 TPS
- 📢 **广播**：`/api/broadcast` 向全服发送消息
- 🔄 **命令支持**：游戏内 `/qqbind` 命令（重载配置、列表、解绑）
- 🧩 **多平台**：同时支持 Forge 1.20.1 和 NeoForge 1.21.1

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
```

## 🔧 快速开始

### 环境要求

- Java 17（Forge）/ Java 21（NeoForge）
- Gradle（使用项目 wrapper）
- Minecraft 服务端（Forge / NeoForge）

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

### 安装

1. 将对应平台的 JAR 文件放入服务端的 `mods` 文件夹
2. 启动服务器，模组会在 `config/qqbind/` 下生成默认配置文件 `qqbind-config.json`
3. 修改配置文件中的 `apiToken`（**必须修改！**）和 `httpPort`（默认 25566）
4. 重启服务器生效

### 配置示例

```json
{
  "httpPort": 25566,
  "apiToken": "your-strong-random-token",
  "enableWhitelistCheck": true,
  "dataFilePath": "qqbind/bindings.json",
  "kickMessage": "§c您尚未绑定游戏ID！\n§e请加入QQ群发送 /绑定 指令完成绑定。"
}
```

## 📡 API 文档

所有接口需要 `Authorization: Bearer <apiToken>` 头（如已配置）。

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/bind` | POST | 绑定 QQ 和游戏 ID（请求体：`{"qq":"123","gameId":"player"}`） |
| `/api/unbind` | POST | 解绑（请求体：`{"gameId":"player"}` 或 `{"qq":"123"}`） |
| `/api/check` | GET | 查询绑定状态（参数：`?gameId=player` 或 `?qq=123`） |
| `/api/status` | GET | 获取在线玩家列表、TPS、延迟等 |
| `/api/stats/{player}` | GET | 获取指定玩家的详细统计 |
| `/api/tps` | GET | 获取当前 TPS |
| `/api/broadcast` | POST | 广播消息（请求体：`{"message":"Hello"}`） |

## 🤖 机器人集成

### AstrBot 插件配置

模组提供了完整的 HTTP API，推荐使用 [AstrBot](https://github.com/banxxx/astrbot_plugin_mcsight) 的插件 `astrbot_plugin_mcsight` 实现 QQ 绑定功能。

在 AstrBot 插件配置中设置：

```json
{
  "enable_mod_api": true,
  "mod_api_port": 25566,
  "mod_api_token": "your-strong-random-token"
}
```

### 支持的机器人命令（需配合插件）

- `/绑定` 或 `/bind <游戏ID>`：绑定当前 QQ 号与游戏 ID
- `/解绑` 或 `/unbind <游戏ID>`：解绑（管理员可用）
- `/查绑定` 或 `/check <游戏ID>`：查询绑定状态
- `/查绑定 QQ <QQ号>`：通过 QQ 号查询绑定的游戏 ID
- `/在线` 或 `/status`：查看在线玩家
- `/广播 <消息>`：向全服广播消息
- `/tps`：查看服务器 TPS

## 📦 发布的 Artifacts

GitHub Release 会自动附上两个 JAR 文件：

- `qqbind-<version>-forge-1.20.1.jar`
- `qqbind-<version>-neoforge-1.21.1.jar`

## 🛠️ 开发指南

### 代码结构

- `common-src/`：所有平台无关代码，修改时注意不要引入平台特定 API
- `forge/` / `neoforge/`：平台特定入口、事件、命令注册
- 新增平台时参考现有模块结构

### 调试

- 在 IDEA 中分别以 `:forge:runServer` 和 `:neoforge:runServer` 启动
- 端口 `25566` 默认绑定所有接口，可通过本地浏览器测试 API
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

- Minecraft Forge & NeoForge 社区
- [AstrBot](https://github.com/Soulter/AstrBot) 提供机器人框架支持
- 所有使用和反馈的玩家