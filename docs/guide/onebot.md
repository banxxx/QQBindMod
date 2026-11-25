# OneBot 模式

OneBot 模式适合已经有一个 QQ 群机器人（例如基于 AstrBot + OneBot 适配器）的服务器。模组只负责 Minecraft 侧的绑定、白名单和 HTTP API，QQ 消息由机器人插件处理。

## 配置步骤

1. 确认机器人插件能访问 Minecraft 服务端的 HTTP API。
2. 在配置文件中设置：

```jsonc
{
  "configVersion": 2,
  "bindingPlatform": "onebot",
  "httpPort": 25566,
  "httpBindHost": "127.0.0.1",
  "apiToken": "replace-with-a-long-random-token",
  "onebot": {
    "qqGroup": "123456789",
    "groupHint": "",
    "serverId": "<SERVER_UUID>",
    "storageMode": "local"
  }
}
```

3. `apiToken` 必须改成随机长字符串，并填入机器人插件配置；两边不一致会返回 401。
4. `qqGroup` 必须是真实群号。它既用于展示，也用于 OneBot 数据隔离。
5. 修改端口、存储模式或平台后完整停启服务器。

## 字段说明

| 字段 | 说明 |
| --- | --- |
| `qqGroup` | 真实 QQ 群号，不能为空。 |
| `groupHint` | 消息里显示的群号。留空自动使用 `qqGroup`。只影响展示。 |
| `serverId` | 可省略、留空或保留 `<SERVER_UUID>`，模组会在内存里生成短标识，但不会写回配置文件。它只用于本进程令牌命名空间，不是跨服身份。 |
| `storageMode` | `local` 单服；`hybrid` / `remote` 需要中心数据库。 |
| `dataFilePath` | 高级项，默认 `qqbind/bindings.json`，一般不需要改。 |

## 玩家绑定流程

未绑定玩家进入服务器时，模组会显示标题、副标题和动作栏提示，其中包含：

- `{groupHint}`：去哪个 QQ 群
- `{bindCommand}`：完整的绑定指令（OneBot 为 `/绑定 <6位令牌>`）
- `{token}`：6 位绑定令牌

玩家在 QQ 群发送绑定指令后，机器人插件调用模组 HTTP API 完成绑定。绑定成功后：

- 玩家 QQ 与游戏 ID 写入存储；
- 自动加入原版白名单；
- 玩家再次进入时不再被拦截。

解绑后会自动从白名单移除。

## 消息模板

两个平台共用模板字段。OneBot 常用写法：

```jsonc
"subtitleTemplate": "§e请在{groupHint}发送 §b{bindCommand} §e完成绑定。",
"actionBarTemplate": "§c您尚未绑定游戏ID！请在{groupHint}发送 §b{bindCommand}"
```

只要保留 `{groupHint}` 和 `{bindCommand}`，修改其它文字不会破坏替换。`{qqGroup}` 仅为旧模板兼容，新配置不建议使用。

## 本地数据与跨服

- 本地模式：绑定数据文件由 `onebot.dataFilePath` 决定，默认 `qqbind/bindings.json`，相对 `config/` 解析，即 `config/qqbind/bindings.json`。
- 本地文件带有群号标记；如果文件里的群号和当前 `qqGroup` 不一致，模组会按空表起步并打印警告，避免把别的群的数据当成当前群。
- 跨服共享时，所有服务器必须使用相同的 `jdbcUrl`、`dbUser`、`dbPassword` 和**相同的 `qqGroup`**，并把 `storageMode` 设为 `hybrid` 或 `remote`。详细说明见 [存储与跨服](/guide/storage)。

## 机器人插件

模组提供 HTTP API，推荐配合 AstrBot 插件使用（参见 [astrbot_plugin_mcsight](https://github.com/banxxx/astrbot_plugin_mcsight)）。插件至少需要：

- 模组地址：`http://<服务端地址>:25566`
- `apiToken`：与模组配置一致
- QQ 群号：与 `onebot.qqGroup` 一致

如果使用中心数据库，插件侧还需要配置 Worker API 或中心库连接；以插件自身文档为准。

## 排查

| 现象 | 检查 |
| --- | --- |
| 玩家看不到绑定提示 | `enableWhitelistCheck` 是否为 `true`；玩家是否已绑定；消息模板是否删掉了 `{groupHint}` / `{bindCommand}`。 |
| 提示里的群号不对 | `onebot.groupHint` 是否填了旧值；留空时会使用 `qqGroup`。 |
| 机器人返回 401 | `apiToken` 与插件配置不一致，或仍是默认占位值。 |
| 绑定后没有白名单 | 检查 `storageMode` 和数据库连接；查看服务端日志。 |
| 多服数据不共享 | 各服的 `qqGroup`、数据库地址、账号和 `storageMode` 是否一致。 |
| reload 后端口/存储模式没变 | 端口和存储模式需要完整停启。 |
