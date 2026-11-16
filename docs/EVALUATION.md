# MergeGuard 离线评测

数据集：`eval/cases.json`，32 个带标注的 PR 片段（26 个风险用例、6 个清洁用例）。

模型：`deepseek-chat`。生成时间：2026-11-16T15:24:59.176134900Z。所有数字由 `EvaluationTest` 实际调用模型后写入，不含手工估值。

## 总体结果

| 方案 | TP | FP | FN | Precision | Recall | F1 | 行号命中率 |
|---|---|---|---|---|---|---|---|
| 规则 only | 18 | 0 | 8 | 1.000 | 0.692 | 0.818 | 1.000 |
| 规则 + 模型 | 25 | 1 | 1 | 0.962 | 0.962 | 0.962 | 1.000 |

- 混合方案比纯规则多召回 **7** 个真实问题。
- 混合方案 F1 = **0.962**，规则 only F1 = **0.818**。
- 本次调用消耗 **20276** tokens，模型总耗时 **53529 ms**，整个评测端到端 **53601 ms**。

## 逐用例

| 用例 | 类型 | 规则 TP/FP/FN | 混合 TP/FP/FN | tokens | 模型块 | 覆盖 |
|---|---|---|---|---|---|---|
| `sql-drop` | risk | 1/0/0 | 1/0/0 | 754 | 1 | 100% |
| `sql-truncate` | risk | 1/0/0 | 1/0/0 | 609 | 1 | 100% |
| `sql-delete-all` | risk | 1/0/0 | 1/0/0 | 599 | 1 | 100% |
| `aws-key` | risk | 1/0/0 | 1/0/0 | 636 | 1 | 100% |
| `private-key` | risk | 1/0/0 | 1/0/0 | 620 | 1 | 100% |
| `sql-injection` | risk | 1/0/0 | 1/0/0 | 677 | 1 | 100% |
| `shell-injection` | risk | 1/0/0 | 1/0/0 | 643 | 1 | 100% |
| `tls-disabled` | risk | 1/0/0 | 1/0/0 | 625 | 1 | 100% |
| `eval-input` | risk | 1/0/0 | 1/0/0 | 616 | 1 | 100% |
| `path-traversal` | risk | 0/0/1 | 1/0/0 | 637 | 1 | 100% |
| `unsafe-deserialize` | risk | 0/0/1 | 1/0/0 | 832 | 1 | 100% |
| `weak-hash` | risk | 0/0/1 | 1/0/0 | 668 | 1 | 100% |
| `empty-catch` | risk | 1/0/0 | 1/0/0 | 630 | 1 | 100% |
| `broad-catch` | risk | 1/0/0 | 1/0/0 | 651 | 1 | 100% |
| `resource-leak` | risk | 0/0/1 | 1/0/0 | 626 | 1 | 100% |
| `disabled-test` | risk | 1/0/0 | 1/0/0 | 746 | 1 | 100% |
| `pr-target` | risk | 1/0/0 | 1/0/0 | 659 | 1 | 100% |
| `write-all` | risk | 1/0/0 | 1/0/0 | 655 | 1 | 100% |
| `internal-url` | risk | 1/0/0 | 1/0/0 | 609 | 1 | 100% |
| `debug-print` | risk | 1/0/0 | 1/0/0 | 655 | 1 | 100% |
| `prompt-injection` | risk | 1/0/0 | 1/0/0 | 500 | 1 | 100% |
| `env-commit` | risk | 1/0/0 | 1/0/0 | 0 | 0 | 100% |
| `admin-authz` | risk | 0/0/1 | 1/0/0 | 754 | 1 | 100% |
| `race-create` | risk | 0/0/1 | 0/1/1 | 879 | 1 | 100% |
| `unbounded-query` | risk | 0/0/1 | 1/0/0 | 645 | 1 | 100% |
| `secret-in-log` | risk | 0/0/1 | 1/0/0 | 624 | 1 | 100% |
| `clean-null-check` | clean | 0/0/0 | 0/0/0 | 516 | 1 | 100% |
| `clean-test-add` | clean | 0/0/0 | 0/0/0 | 610 | 1 | 100% |
| `clean-refactor` | clean | 0/0/0 | 0/0/0 | 759 | 1 | 100% |
| `clean-config` | clean | 0/0/0 | 0/0/0 | 643 | 1 | 100% |
| `clean-docs` | clean | 0/0/0 | 0/0/0 | 478 | 1 | 100% |
| `clean-small-fix` | clean | 0/0/0 | 0/0/0 | 721 | 1 | 100% |

## 失败与误报

  - DATA_LOSS|1|1.00|破坏性数据库变更
  - DATA_LOSS|1|1.00|破坏性数据库变更
  - DATA_LOSS|1|1.00|DELETE 语句缺少 WHERE
  - SECURITY|1|1.00|疑似硬编码凭证
  - SECURITY|1|1.00|疑似硬编码凭证
  - SECURITY|2|1.00|SQL 字符串拼接可能注入
  - SECURITY|2|1.00|动态执行代码
  - SECURITY|3|1.00|TLS 校验被关闭
  - SECURITY|1|0.98|直接 eval 用户输入导致任意代码执行
  - SECURITY|2|0.95|未校验文件名导致路径穿越
  - SECURITY|2|0.95|不可信数据直接反序列化导致 RCE
  - SECURITY|2|0.98|口令使用无盐 MD5 哈希，存在严重安全风险
  - RELIABILITY|3|1.00|异常被静默吞掉
  - RELIABILITY|1|1.00|捕获范围过宽
  - RELIABILITY|2|0.95|InputStream 未关闭导致文件描述符泄漏
  - TESTING|1|1.00|测试被禁用或跳过
  - CI_SECURITY|1|1.00|使用 pull_request_target 且未隔离权限
  - CI_SECURITY|1|1.00|工作流权限过宽
  - CONFIG|1|0.90|硬编码内网地址导致环境耦合
  - MAINTAINABILITY|2|1.00|调试输出残留
  - SECURITY|1|1.00|疑似针对 AI 审查的提示注入
  - SECURITY|null|1.00|提交了环境变量文件
  - SECURITY|1|0.95|管理接口缺少授权校验
- `race-create` 漏报 1 项。
- `race-create` 误报 1 项。
  - CORRECTNESS|2|0.90|先查后插存在并发竞态
  - PERFORMANCE|1|0.85|无上限全表查询存在内存与性能风险
  - SECURITY|2|0.98|日志打印明文口令导致凭据泄露

## 复现

```bash
mvn -q -Dmg.eval=true -Dtest=EvaluationTest test
```
