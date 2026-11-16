# MergeGuard 架构说明

## 1. 目标与边界

**目标**：在一个 PR 合并前，用尽量低的人工成本回答三个问题：

1. 这次改动有没有会直接造成事故的问题（数据丢失、凭证泄露、注入、CI 供应链）？
2. 有没有需要修复后才能上线的可靠性/测试缺口？
3. 结论和证据在哪里，能不能回看、能不能回写 GitHub？

**不做**：

- 不自动合并、不自动修改代码；
- 不做完整 SAST/DAST 替代品；
- 不做多租户 SaaS 计费；
- 不试图理解整个仓库的运行时语义。

## 2. 组件

| 组件 | 职责 | 关键类 |
|---|---|---|
| 接入层 | Webhook 验签、幂等；轮询 open PR | `WebhookController`、`PollingService` |
| 编排层 | 拉 diff、加锁、规则 + 模型、聚合、落库、回写 | `ReviewService` |
| Diff 解析 | 把 unified diff 映射成新增行号 | `DiffParser` / `FileDiff` |
| 规则引擎 | 20 条确定性规则，输出可解释发现 | `RuleEngine` |
| 模型评审 | 只针对新增代码做语义复核，JSON 输出 | `LlmReviewer` |
| 风险聚合 | 去重、同规则同文件合并、打分、判定 | `RiskAggregator` |
| 控制台 | 总览、记录、详情、仓库接入 | `frontend/` |
| 存储 | 审查、发现、仓库、Webhook 投递 | MySQL 8 |
| 协调 | 审查锁、轮询锁 | Redis 7 |

## 3. 一次审查的数据流

```text
GitHub PR (head sha)
  ├─ Webhook pull_request.opened/synchronize/reopened
  │    └─ HMAC 校验 → delivery 幂等 → 入队
  └─ 轮询：GET /repos/{o}/{r}/pulls?state=open
       └─ 未审过的 head sha → 入队
                 │
                 ▼
        Redis SET NX review:{repo}#{pr}@{sha}
                 │
                 ▼
        GET /pulls/{n}/files（最多 300 个文件）
                 │
                 ▼
        FileDiff.parse → 新增行号集合
                 │
        ┌────────┴────────┐
        ▼                 ▼
   RuleEngine        LlmReviewer
   20 条规则          DeepSeek JSON
        └────────┬────────┘
                 ▼
          RiskAggregator
   （同规则+同文件合并 / 模型与规则邻近去重 / 分级扣分）
                 │
                 ▼
   mg_review + mg_finding（按 head sha 唯一）
                 │
                 ├─ 控制台：/api/reviews/{id}
                 └─ GitHub：POST /pulls/{n}/reviews
                      ├─ inline：仅 diff 新增行，最多 10 条
                      └─ 失败 → Issue Comment → 记录 publishError
```

## 4. 判定与去重

**扣分**：BLOCKER -40，MAJOR -12，MINOR -4，INFO -1，最低 0。

**判定**：

- 任一 BLOCKER → `BLOCK`
- 无 BLOCKER，但有 MAJOR 或分数 < 75 → `FIX`
- 其余 → `SHIP`

**去重顺序**：

1. 同一 `ruleId + file` 的多条规则发现合并为一条，证据用换行拼接（例如同一迁移脚本里的 `DROP COLUMN` 与 `DROP TABLE`）。
2. 同一 `file + line + category` 只保留一条，严重级更高者优先，规则优先于模型。
3. 模型发现若与某条规则发现处于同一文件、行号相差 ≤ 2，则丢弃，避免重复报警。

## 5. 安全设计

| 风险 | 处理 |
|---|---|
| 伪造 Webhook | `X-Hub-Signature-256` HMAC-SHA256，常量时间比较；未配置 secret 直接 503 |
| 重放/重复投递 | `mg_webhook_delivery` 以 `X-GitHub-Delivery` 为主键，唯一冲突返回 200 |
| 控制台未授权访问 | `X-API-Key`，常量时间比较；Key 未配置 fail-closed 503 |
| 并发重复审查 | Redis `SET NX` + MySQL `(repo, pr, head_sha)` 唯一约束 |
| 模型输出不可信 | 只接受 JSON；文件必须在 diff 中；行号必须在新增行中，否则降为文件级；severity/category 白名单归一化；不执行模型给出的命令 |
| Prompt injection | diff 只作为数据传入；模型无工具权限；输出仍经过结构校验 |
| 密钥泄露 | token 只从环境变量读取；日志不打印 token；前端不打包 API Key |
| 误伤合并 | 只发 `COMMENT` 事件，不 `APPROVE` / `REQUEST_CHANGES`，不自动合并 |

## 6. 数据模型

| 表 | 关键字段 | 说明 |
|---|---|---|
| `mg_repository` | id（GitHub repo id）、owner、name、enabled、auto_review | 接入仓库 |
| `mg_review` | repo、pr_number、head_sha、verdict、score、summary、tokens、published | 唯一键 `(repo_owner, repo_name, pr_number, head_sha)` |
| `mg_finding` | review_id、file、line、severity、category、evidence、suggestion、source | 规则/模型发现 |
| `mg_webhook_delivery` | delivery_id、event、received_at | Webhook 幂等 |

演示环境复用本机 `offeragent` schema，通过 `DB_SCHEMA` 切换；生产应使用独立库 + Flyway。

## 7. 失败模式

| 失败 | 行为 |
|---|---|
| GitHub API 403/429 | 记录 `GitHubException`，审查任务失败并计数；不会写入半成品 |
| LLM 超时/400/JSON 解析失败 | 降级为规则结果，`llm_model` 仍记录，tokens 为 0 |
| 行内评论 422 | 自动退回 Issue Comment；再失败记录 `publishError` |
| Redis 不可用 | 跳过锁继续审查（可用性优先），日志告警 |
| 审查队列满 | 手动接口返回 503，异步任务丢弃并计数 |
| 同一 PR 新提交 | 新记录，旧记录保留为快照 |

## 8. 代码地图

```text
src/main/java/dev/yujian/mergeguard/
├── github/        GitHub REST 客户端与 DTO
├── diff/          unified diff → 新增行号
├── analysis/      RuleEngine / LlmReviewer / RiskAggregator
├── service/       ReviewService / PollingService / Hmac
├── web/           Webhook / Review / Repo / Overview / 异常处理
├── domain/        JPA 实体与枚举
├── repo/          Spring Data Repository
└── config/        属性、RestClient、异步线程池、鉴权过滤器、CORS、OpenAPI

frontend/src/
├── pages/         Overview / Reviews / ReviewDetail / Repos
├── components/    Layout / Badges / Icons
├── api.ts         控制台 API 客户端
└── styles.css     自建设计系统
```

## 9. 取舍

- **为什么规则 + 模型，而不是纯模型**：阻断项需要稳定、可解释、可回归；模型补语义但可能漂移。
- **为什么轮询 + Webhook 都做**：本地/无公网环境可直接演示；生产用 Webhook 降延迟。
- **为什么按 head sha 幂等**：Review 的价值在于“这个提交能不能合”，提交变了就必须重新判断。
- **为什么不用 Streamlit**：需要自定义信息密度、状态与交互；React + 自建 CSS 可控且可部署为单 JAR。
- **为什么先不做 GitHub App**：PAT 能最快验证端到端价值；App 安装态、权限细分、多租户是下一阶段。
