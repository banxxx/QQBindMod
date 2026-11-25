# HTTP API

模组在服务端内置 HTTP API，供 QQ 机器人插件调用。默认监听 `0.0.0.0:25566`，可以通过 `httpPort` 和 `httpBindHost` 修改。

除特别说明外，所有接口都需要请求头：

```http
Authorization: Bearer <apiToken>
```

`apiToken` 必须与机器人插件配置一致。默认占位值、空值或不安全的值会导致 HTTP API 拒绝启动。

## 接口

| 端点 | 方法 | 说明 |
| --- | --- | --- |
| `/api/bind` | POST | 绑定 QQ 和游戏 ID。请求体：`{"qq":"123","gameId":"player"}` |
| `/api/unbind` | POST | 解绑。请求体：`{"gameId":"player"}` 或 `{"qq":"123"}` |
| `/api/check` | GET | 查询绑定状态。参数：`?gameId=player` 或 `?qq=123` |
| `/api/status` | GET | 在线玩家列表、TPS、延迟等状态 |
| `/api/stats/{player}` | GET | 指定玩家的详细统计 |
| `/api/tps` | GET | 当前 TPS |
| `/api/broadcast` | POST | 全服广播。请求体：`{"message":"Hello"}` |
| `/api/validate_token` | GET | 验证绑定令牌（机器人端使用） |
| `/api/cache/invalidate` | POST | 清除指定玩家的缓存 |
| `/api/console` | POST | 以控制台身份执行命令并回显输出，需要独立的 `consoleToken` |

示例：

```bash
curl -H "Authorization: Bearer 你的apiToken" \
  "http://127.0.0.1:25566/api/check?gameId=Steve"
```

## /api/console

该接口等同于服务器控制台，默认关闭。只有配置了 `consoleToken` 才会注册，并且它不与 `apiToken` 共用密钥。

::: danger 只在可信网络使用
不要把 `/api/console` 暴露到公网。推荐：

- `httpBindHost` 使用 `127.0.0.1` 或内网网卡地址；
- 用防火墙限制来源 IP；
- 每台服务器使用独立的 `consoleToken`，不要多服共用；
- 机器人端按服务器单独保存密钥。
:::

永久硬禁命令（配置无法解除）：

```text
stop, restart, reload, deploy, ban, ban-ip, pardon, pardon-ip, schedule
```

其余命令默认可执行，包括 `op`、`execute`、`function` 等。需要限制时使用 `consoleBannedCommands` 追加命令名。

限制：

- 单次调用按来源 IP 限流 20 次/分钟；
- 命令长度上限 200 字符；
- 不支持一次提交多条命令；
- 返回 `output` 最多 30 行，单行最多 500 字符；
- 疑似包含口令/种子的行会被替换为提示；
- `status=timeout` 表示模组没有在限定时间内取回结果，命令可能已经执行，不要当作“已取消”。

请求示例：

```bash
curl -X POST \
  -H "Authorization: Bearer 你的consoleToken" \
  -H "Content-Type: application/json" \
  -d '{"command":"whitelist list","actor":"qq:123"}' \
  "http://127.0.0.1:25566/api/console"
```

## 官方模式下的旧 API

QQ 官方模式下，旧版 OneBot 写接口不会回退到旧存储。部分只读状态接口（如 `/api/status`、`/api/tps`、`/api/stats`）仍可用于监控；绑定本身由官方 V2 流程完成。不要在官方模式下依赖 OneBot 的绑定/解绑 API。

## 安全建议

- 不要把 `apiToken`、`consoleToken` 或数据库密码提交到 Git 或发到群里。
- `apiToken` 使用随机长字符串；泄露后立即更换并重启。
- 不需要控制台接口时保持 `consoleToken` 为空。
- 生产环境优先绑定内网地址，并通过反向代理/防火墙限制来源。
