# 协议协商演练器（Protocol Negotiation Lab）

本地模拟客户端/服务端交换能力、选择版本、确认参数与失败降级的 Java Web 应用。
数据保存在 SQLite，提供 JSON API 与单页 Web UI（标题“协议协商演练器”）。

## 构建与运行

```bash
./gradlew classes                                              # 安装/编译检查
./gradlew test                                                 # 21 个测试
./gradlew run --args='--port 5246'                             # 演示（默认 ./negotiator.db）
./gradlew run --args='--port 5246 --db /path/to/demo.db'       # 指定数据库
```

打开 http://localhost:5246 即可看到并排的双方能力、逐轮消息、候选淘汰与最终参数。

## 协商模型

- 场景包含若干**协议版本**：必选能力 `requiredCaps`、扩展 `extensions`
  （必选/可选、可忽略标记、互斥组 `group`、优先级 `priority`、依赖 `requires`）、
  参数范围 `params`（int 区间 / enum 取值）、显式降级边 `downgrade`。
- 候选顺序：客户端版本偏好序为起点，只沿候选版本声明的**显式降级边**继续；
  每个候选记录接受或拒绝原因（`REQUIRED_CAP_MISSING`、`SERVER_VERSION_UNSUPPORTED`、
  `UNKNOWN_REQUIRED_EXTENSION`、`REQUIRED_EXT_*`、`PARAM_NO_OVERLAP` 等）。
- 选择固定规则：能力/扩展取双方交集且依赖必须满足；互斥组取 `priority` 最小者，
  并列按扩展名字典序升序；int 参数取双方区间交集与协议范围的交集中最靠近协议中点的值；
  enum 取协议声明顺序中第一个双方都支持的取值。
- 未知扩展：任一方声明为**可忽略**（`ignorable:true`）时不参与选择但保留在 transcript；
  未声明可忽略的未知扩展按必选未知处理，候选明确失败 `UNKNOWN_REQUIRED_EXTENSION`。
- 降级边循环/自环/悬空在**发布前**校验拒绝（`DRAFT_INVALID`）。

## 会话四阶段与故障语义

状态机：`offer → SELECTED → CONFIRMED → ACTIVE`，失败为 `FAILED`。

- 每条消息记录 `phase/direction/seq/byteCount/rawText/parsed`，即双方消息字节与解析结果。
- select 在服务端**计算一次后冻结**（`selection_hash` + 当时的场景快照 JSON 存入 SQLite）。
- “保存 select 后、发 confirm 前崩溃”：offer 中带 `injections.crashAfterSelect:true`，
  默认策略会让进程以退出码 137 退出；重启后对同一会话用相同 `selectionHash` 调 confirm
  即可恢复，hash 不一致返回 `409 SELECTION_MISMATCH`，**不会因配置变化重新计算**。
- 幂等：相同 `idemKey` + 相同 offer 返回原会话；相同 key + 不同 offer 返回
  `409 IDEMPOTENCY_CONFLICT`。
- 注入：`loseConfirm` → `503 CONFIRM_LOST`（状态不前进）；重复 confirm/active →
  返回原结果并追加一条重复消息。
- 陈旧 token：ACTIVE 会话 `/renegotiate` 产生新一代次与新 token，旧 token 激活返回
  `409 STALE_TOKEN`。
- 草稿用 `revision` 乐观锁：两个编辑窗口，后保存的旧 revision 得到
  `409 REVISION_CONFLICT`。
- 已发布版本声明不可修改（`409 VERSION_IMMUTABLE`），只能以新版本号发布。
- transcript 可导出（含原始报文字节、双方能力、场景快照）；重新导入会用快照重跑引擎，
  返回 `sameSelection` 校验“得到同样选择”。

## 预置数据

首次启动（空库）预置场景 `tls-layered`（v1/v2/v3）与一条 ACTIVE 会话：
客户端首选 v3，服务端声明支持 v3 但缺少必选能力 `quic-transport`，v3 被明确拒绝
（`REQUIRED_CAP_MISSING`），沿显式边 `v3 → v2` 降级并完成协商。
选择冻结在 SQLite，重启后保持相同 `selectionHash`。

## JSON API 摘要

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/scenarios` | 场景列表（draft + published + revision） |
| POST | `/api/scenarios` | 创建场景（草稿立即校验，循环边在此即拒） |
| PUT | `/api/scenarios/{id}/draft` | 乐观锁保存草稿（`expectedRevision`） |
| POST | `/api/scenarios/{id}/publish` | 发布；旧版本改声明 → `VERSION_IMMUTABLE` |
| POST | `/api/sessions` | offer + select（幂等键、`crashAfterSelect`） |
| GET | `/api/sessions` / `/api/sessions/{id}` | 会话列表 / 并排详情视图 |
| POST | `/api/sessions/{id}/confirm` | 恢复/推进 confirm（`loseConfirm`/`duplicateConfirm`） |
| POST | `/api/sessions/{id}/active` | 激活（陈旧 token → `STALE_TOKEN`） |
| POST | `/api/sessions/{id}/renegotiate` | ACTIVE 上发起新一代次 |
| GET | `/api/sessions/{id}/export` | 导出 transcript |
| POST | `/api/transcripts/import` | 导入 transcript 并重算比对选择 |

## 技术栈

Java 17+（在 JDK 25 上验证，`--release 17`）、Gradle 9.8 wrapper、
JDK 内置 `com.sun.net.httpserver`、sqlite-jdbc、Jackson、JUnit 5。
