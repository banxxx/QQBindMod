# 快速开始

QQBindMod 是**服务端模组**。它本身不直接登录 QQ，而是在 Minecraft 服务端内置 HTTP API，由 QQ 机器人插件调用，完成绑定、解绑、查询和白名单同步。

## 支持版本

| 加载器 | Minecraft | Java |
| --- | --- | --- |
| Forge | 1.20.1 | 17 |
| NeoForge | 1.21.1 | 21 |
| Fabric | 1.20.1 | 17 |
| Fabric | 1.21.1 | 21 |

请使用与服务器加载器、Minecraft 版本和 Java 版本匹配的 JAR。不要把 Forge 的 JAR 放进 Fabric 服务端。

## 安装

1. 从 [GitHub Releases](https://github.com/banxxx/QQBindMod/releases) 下载对应平台的 JAR，或按 [开发指南](/guide/development) 自行构建。
2. 把 JAR 放进服务端的 `mods` 目录。
3. 启动一次服务器。模组会生成 `config/qqbind/qqbind-config.jsonc`；旧版 `config/qqbind/qqbind-config.json` 仍然可以读取。
4. 修改下面这些**必须处理**的配置，然后完整停止并启动服务器。

### 必须修改的字段

| 字段 | 说明 |
| --- | --- |
| `apiToken` | 机器人插件访问 HTTP API 的 Bearer Token。必须是随机长字符串，不能保留默认占位值，否则 HTTP API 会拒绝启动。 |
| `onebot.qqGroup` | 使用 OneBot 模式时，填真实 QQ 群号。它既用于展示，也用于 OneBot 数据隔离。 |
| `httpPort` | HTTP API 端口，默认 `25566`。修改后需要重启。 |
| `httpBindHost` | 监听地址。默认 `0.0.0.0`；如果机器人插件和服务端在同一台机器或同一内网，建议改成 `127.0.0.1` 或内网网卡地址。 |

最小可用的 OneBot 配置示例：

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

`serverId` 在 OneBot 模式下可以保留占位符或留空，模组会自动生成一个进程内短标识。

### 使用 QQ 官方模式

把 `bindingPlatform` 改成 `qq_official`，并填写 `qq_official` 段。下面这些字段必须替换成真实值：

- `environmentId`：官方环境 UUID
- `bindingGroupId`：绑定群的 `group_openid`，不是群号
- `serverId`：本服 UUID
- `apiToken`：随机长字符串
- `configurationRevision`：通常保持 `"1"`

完整步骤见 [QQ 官方模式](/guide/qq-official)。

## 验证是否启动成功

配置好 `apiToken` 后，用机器人插件或 curl 请求状态接口：

```bash
curl -H "Authorization: Bearer 你的apiToken" \
  "http://127.0.0.1:25566/api/status"
```

如果连接被拒绝，先检查：

1. 服务端日志里 HTTP 是否启动、端口是否被占用。
2. `apiToken` 是否仍是默认占位值或为空。
3. `httpBindHost` 是否只监听了本机，导致远程请求不到。
4. 防火墙/安全组是否放行端口。

更完整的排查见 [常见问题](/guide/faq)。

## 下一步

- [配置文件](/guide/configuration)：字段含义、严格校验、模板占位符、配置丢失恢复。
- [OneBot 模式](/guide/onebot)：群机器人接入和跨服。
- [QQ 官方模式](/guide/qq-official)：官方环境、群 openid 和运行期限制。
- [HTTP API](/guide/api)：接口列表和 `consoleToken` 安全说明。
