# 协议协商演练器 (Protocol Negotiation Trainer)

本地模拟连接层客户端/服务端的能力交换与协议协商：版本选择、能力交集、
依赖与互斥校验、参数范围确认、沿显式边降级，以及失败/崩溃恢复。

- Java（JDK `HttpServer`，无 Web 框架）+ 原生 JSON 实现
- SQLite（`sqlite-jdbc`）保存场景、不可变发布版本、draft 与会话/消息
- 两个页面：演练主页 `index.html` 与双栏 draft 编辑页 `draft.html`

## 构建与运行

```bash
./gradlew classes
./gradlew test
./gradlew run --args='--port 5246'
```

然后打开 http://localhost:5246 ，页面标题为「协议协商演练器」。
数据默认存于工作目录 `proto-negotiator.db`（首次启动自动建库并写入预置数据）。
可用 `--db /path/to.db` 指定其它数据库文件。

## 预置演示

预置场景 `demo` 含 `v1 / v2 / v3` 三个版本，其中 `v3` 额外要求能力 `mux`，
并有一条显式降级边 `v3 -> v2`。预置客户端优先 `v3` 但不具备 `mux`，
因此协商会明确记录 v3 被拒原因，再沿显式边降级选定 `v2`。预置会话停在
`select` 已保存、confirm 未发送的状态（模拟进程退出）；重启后对同一会话
再次 select/confirm 会得到并激活完全相同的选择，不会因配置变化重算。

## 协商模型

- 阶段：`offer -> select -> confirm -> active`，每条消息记录方向、阶段、
  代次、字节数与解析后的 JSON。
- 版本必须为双方交集；必选能力需双方都具备。
- 扩展：支持依赖（requires）闭包与互斥组；同优先级按客户端 offer 顺序的
  固定规则裁决（先列者胜）。
- 未知扩展：声明可忽略的可选扩展保留在 transcript 中并标记 `ignored`；
  必选的未知扩展导致明确失败（`MANDATORY_UNKNOWN_EXTENSION`）。
- 参数：整型范围取交集并钳制；枚举取交集，无交集才淘汰候选。
- 降级只沿发布时声明的显式边发生；每个被访问候选都记录拒绝原因。
  降级图中的环（含自环）在发布前被拒绝。
- 幂等：`(场景, 幂等键)` 唯一。相同幂等键且相同 offer 返回原会话；
  相同键不同 offer 返回 409 冲突。
- 恢复：select 落库后进程退出，恢复时必须携带同一选择，否则 409；
  会话绑定发布时的 revision，之后发布新 revision 不影响已存选择。
- 重复 confirm 返回原结果；每个代次有独立 token，旧 token 不能激活新代次。
- draft 采用乐观锁（baseVersion），两个页面同时保存时后写者收到 409；
  已发布的能力声明不可修改，发布动作总是创建新的 revision。
- transcript 可导出为 JSON；导入时用不可变的历史 revision 重算并断言
  得到相同选择，否则报错。

## 故障注入

- select 请求带 `crashAfterSelect:true`：select 已持久化但不返回（HTTP 599）。
- confirm 请求带 `simulateLoss:true`：服务端已 ACTIVE 但回复丢失。
- confirm 可重复发送验证幂等；可改用错误/旧 token 验证 409。
- `reoffer` 开新代次并签发新 token。

## JSON API 摘要

- `GET  /api/scenarios`
- `POST /api/scenarios`
- `GET  /api/scenarios/{id}/draft` / `PUT .../draft`（体含 `baseVersion`）
- `POST /api/scenarios/{id}/publish`
- `GET  /api/scenarios/{id}/published/{revision?}`
- `POST /api/scenarios/{id}/offer`
- `GET  /api/sessions/{id}`
- `POST /api/sessions/{id}/select`
- `POST /api/sessions/{id}/confirm`
- `POST /api/sessions/{id}/reoffer`
- `GET  /api/sessions/{id}/transcript`
- `POST /api/scenarios/{id}/transcript`（导入并校验）

## 测试

`./gradlew test` 覆盖：能力交集、互斥组、未知扩展（可忽略/必选失败）、
优先级并列、显式降级与无边不降级、降级环发布拒绝、select 后崩溃恢复、
配置变更不重算、重复 confirm、陈旧 token、幂等冲突、draft 乐观锁、
发布不可变、transcript 导入一致性与重启持久化。
