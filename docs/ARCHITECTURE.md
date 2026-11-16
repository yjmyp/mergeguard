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
| 接入层 | Webhook 验签、幂等；轮询 open PR；统一投递任务 | `WebhookController`、`PollingService` |
| 任务层 | MySQL 租约队列、过期恢复、指数退避、幂等键 | `JobService`、`JobWorker`、`ReviewJob` |
| 编排层 | 拉 diff、加锁、规则 + 模型、聚合、落库、回写 | `ReviewService` |
| Diff 解析 | 把 unified diff 映射成新增行号，保存快照 | `DiffParser` / `FileDiff` / `ReviewFile` |
| 分块 | 按字符上限切块，带 `+L行号|` 标记，统计覆盖率 | `DiffChunker` |
| 注入防御 | 中和越权指令与标签逃逸，计数 | `PromptGuard` |
| 规则引擎 | 21 条确定性规则，输出可解释发现 | `RuleEngine` |
| 模型评审 | 多块调用 DeepSeek，单块重试，JSON 输出 | `LlmReviewer` |
| 风险聚合 | 去重、同规则同文件合并、置信度过滤、打分、判定 | `RiskAggregator` |
| 质量闭环 | 发现状态与反馈、按规则统计采纳率 | `FindingStatus`、`QualityController` |
| 控制台 | 总览、记录、详情、质量、任务队列、仓库接入 | `frontend/` |
| 存储 | 审查、发现、diff 快照、仓库策略、任务、Webhook 投递 | MySQL 8 |
| 协调 | 审查锁、轮询锁 | Redis 7 |
| 集成 | PR Review / Issue Comment / Check Run / Commit Status / SARIF | `GitHubClient`、`SarifExporter` |

## 3. 一次审查的数据流

```text
GitHub PR (head sha)
  ├─ Webhook pull_request.opened/synchronize/reopened
  │    └─ HMAC 校验 → delivery 幂等 → JobService.enqueue
  └─ 轮询：GET /repos/{o}/{r}/pulls?state=open
       └─ 未审过的 head sha → JobService.enqueue
                 │
                 ▼
        mg_job (dedupe_key 唯一，status=QUEUED)
                 │
                 ▼
        JobWorker claim：status=RUNNING + lease_until
                 │  （崩溃后租约过期会被重新 claim）
                 ▼
        Redis SET NX review:{repo}#{pr}@{sha}
                 │
                 ▼
        GET /pulls/{n}/files（最多 300 个文件）
                 │
                 ├─ mg_review_file 保存 diff 快照
                 ▼
        FileDiff.parse → 新增行号集合
                 │
        ┌────────┴────────┐
        ▼                 ▼
   RuleEngine       DiffChunker → PromptGuard → LlmReviewer
   21 条规则          按块 JSON，单块失败重试
        └────────┬────────┘
                 ▼
          RiskAggregator
   （同规则+同文件合并 / 模型与规则邻近去重 / 置信度校准 / 分级扣分）
                 │
                 ▼
   mg_review + mg_finding（按 head sha 唯一）
                 │
                 ├─ 控制台：/api/reviews/{id}、/diff、/sarif
                 ├─ GitHub：汇总评论 upsert → 行内评论 → Check Run
                 └─ Check Run 403：自动降级 Commit Status
```

## 4. 任务状态机

```text
QUEUED ──claim──▶ RUNNING ──success──▶ DONE
   ▲                  │
   │                  ├─ exception ─▶ QUEUED（next_attempt_at 指数退避）
   │                  │                  attempts < max_attempts
   │                  └─ attempts 用尽 ─▶ FAILED
   └──── 手工 retry（仅 FAILED）───────┘
```

- `dedupe_key = repo#pr@sha`，唯一约束保证 Webhook 与轮询不会重复入队。
- `lease_until` 默认 120 秒；worker 崩溃后，其他实例可重新 claim。
- 所有状态变更写库，`/api/jobs` 与 `/api/jobs/stats` 可观测。

## 5. 判定与去重

**扣分**：BLOCKER -40，MAJOR -12，MINOR -4，INFO -1，最低 0。

**判定**：

- 任一 BLOCKER → `BLOCK`
- 无 BLOCKER，但有 MAJOR 或分数 < 75 → `FIX`
- 其余 → `SHIP`

**去重顺序**：

1. 同一 `ruleId + file` 的多条规则发现合并为一条，证据用换行拼接。
2. 同一 `file + line + category` 只保留一条，严重级更高者优先，规则优先于模型。
3. 模型发现若与某条规则发现处于同一文件、行号相差 ≤ 2，则丢弃。
4. 模型置信度低于 `minConfidence=0.85` 直接丢弃。
5. 新提交发布时，同一文件同类别 ±3 行内已评论过的问题不再重复发 inline。

## 6. AI 评审流水线

1. `DiffChunker` 按 `chunk-chars`（默认 6000）切分，最多 `max-chunks`（默认 8）。
2. 每个新增行显式写成 `+L{行号}|{代码}`，降低模型编造行号的概率。
3. `PromptGuard` 中和三类内容：英文/中文越权指令、伪系统提示、闭合标签逃逸；命中计数。
4. 每块独立调用模型，要求 JSON；单块失败按 `retry-attempts` 重试，仍失败则跳过该块。
5. 合并所有块结果，记录 `llm_chunks`、`llm_coverage`；部分失败时保留成功块与规则结果。
6. 类别白名单：模型不负责 TESTING / MAINTAINABILITY / REVIEW_COST，这些只由确定性逻辑产生。

**离线评测**：`eval/cases.json` 有 32 个带标注片段（26 风险 + 6 清洁）。`EvaluationTest` 默认跳过，使用 `-Dmg.eval=true` 才真实调用模型。规则 only F1 0.818，混合 F1 0.962，行号命中率 1.000。产出 `docs/evaluation.json` 与 `docs/EVALUATION.md`。

## 7. 发布策略

优先级从高到低：

1. 汇总评论：用 `<!-- mergeguard:summary -->` 标记查找旧评论并更新，不重复刷屏。
2. 行内评论：只发 diff 新增行、达到 `minPublishSeverity`、不超过 `maxInlineComments`。
3. Check Run：带 annotations；需要 GitHub App，PAT 调用返回 403。
4. Commit Status：Check Run 不可用时自动降级，`context = MergeGuard / risk review`。

汇总评论、行内评论、Check Run/Commit Status 任一成功即 `published=true`；仍失败记录 `publishError`，不影响本地审查结果。系统只发 `COMMENT` 事件，不 `APPROVE` / `REQUEST_CHANGES`，不自动合并。

## 8. 安全设计

| 风险 | 处理 |
|---|---|
| 伪造 Webhook | `X-Hub-Signature-256` HMAC-SHA256，常量时间比较；未配置 secret 直接 503 |
| 重放/重复投递 | `mg_webhook_delivery` 以 `X-GitHub-Delivery` 为主键；`mg_job.dedupe_key` 二次幂等 |
| 控制台未授权访问 | `X-API-Key`，常量时间比较；Key 未配置 fail-closed 503 |
| 并发重复审查 | Redis `SET NX` + MySQL `(repo, pr, head_sha)` 唯一约束 + 任务幂等 |
| 模型输出不可信 | 只接受 JSON；文件必须在 diff 中；行号必须在新增行中；severity/category 白名单归一化；不执行模型命令 |
| Prompt injection | `PromptGuard` 中和指令；diff 只作为数据；模型无工具权限；输出仍经过结构校验 |
| 密钥泄露 | token 只从环境变量读取；日志不打印 token；前端不打包 API Key |
| 误伤合并 | 只发评论和状态，不自动合并 |

## 9. 数据模型

| 表 | 关键字段 | 说明 |
|---|---|---|
| `mg_repository` | id（GitHub repo id）、owner、name、enabled、auto_review、策略字段 | 接入仓库与策略 |
| `mg_review` | repo、pr_number、head_sha、verdict、score、summary、tokens、分阶段耗时、llm_chunks/coverage、published、publish_error | 唯一键 `(repo_owner, repo_name, pr_number, head_sha)` |
| `mg_review_file` | review_id、filename、patch | diff 快照，支持详情页与离线回看 |
| `mg_finding` | review_id、file、line、severity、category、evidence、suggestion、source、confidence、rule_id、status、feedback_note、feedback_at | 规则/模型发现与反馈 |
| `mg_job` | dedupe_key、status、attempts、max_attempts、lease_until、next_attempt_at、last_error、review_id | 持久化任务队列 |
| `mg_webhook_delivery` | delivery_id、event、received_at | Webhook 幂等 |

演示环境复用本机 `offeragent` schema，通过 `DB_SCHEMA` 切换；生产应使用独立库 + Flyway。演示库做过一次手工归一化：旧 `mg_finding.status` 补 `OPEN`、旧 `mg_review` 的可空列与默认值、`mg_repository` 的策略默认值；新库由 `ddl-auto=update` 直接建对。

## 10. 失败模式

| 失败 | 行为 |
|---|---|
| GitHub API 403/429 | 任务失败并按退避重试，最多 `max_attempts`；不会写入半成品审查 |
| LLM 超时/400/JSON 解析失败 | 单块重试，仍失败则保留其余块与规则结果，审查继续 |
| 行内评论 422 | 汇总评论仍保留，行内失败只记日志 |
| Check Run 403（PAT） | 自动降级 Commit Status；两者都失败才记录 `publishError` |
| Redis 不可用 | 跳过锁继续审查（可用性优先），数据库唯一约束兜底 |
| Worker 崩溃 | 租约过期后任务被重新 claim；`attempts` 递增 |
| 同一 PR 新提交 | 新记录，旧记录保留为快照 |

## 11. 代码地图

```text
src/main/java/dev/yujian/mergeguard/
├── github/        GitHub REST 客户端与 DTO
├── diff/          unified diff → 新增行号
├── analysis/      RuleEngine / DiffChunker / PromptGuard / LlmReviewer / RiskAggregator
├── service/       ReviewService / JobService / JobWorker / PollingService / Hmac / SarifExporter
├── web/           Webhook / Review / Repo / Job / Quality / Overview / 异常处理
├── domain/        JPA 实体与枚举
├── repo/          Spring Data Repository
└── config/        属性、RestClient、异步线程池、鉴权过滤器、CORS、OpenAPI

frontend/src/
├── pages/         Overview / Reviews / ReviewDetail / Quality / Jobs / Repos
├── components/    Layout / Badges / Icons
├── api.ts         控制台 API 客户端
└── styles.css     自建设计系统

eval/cases.json    32 个离线评测用例
docs/               架构、评测报告、端到端证据、截图
```

## 12. 取舍与限制

- **为什么规则 + 模型**：阻断项需要稳定、可解释、可回归；模型补语义但可能漂移，因此要置信度过滤和类别约束。
- **为什么用 MySQL 队列而不是内存线程池**：审查耗时长、会调用外部 API，进程重启时内存任务会丢；租约队列实现成本低且可观测。
- **为什么按块而不是整段 diff**：长 diff 会超上下文，块级重试还能避免一个坏块拖垮整次审查。
- **为什么保留 diff 快照**：详情页要能回看行级证据，GitHub 上的旧提交也可能被 force-push。
- **为什么先不做 GitHub App**：PAT 能最快验证端到端价值；Check Run 限制已经通过 Commit Status 降级覆盖。
- **为什么不用 Streamlit**：需要自定义信息密度、状态与交互；React + 自建 CSS 可控且可部署为单 JAR。
- **本机没有 Docker**：`docker-compose.yml` 未做本地实跑验证，README 已明确标注。
