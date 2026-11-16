import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api";
import type { Finding, ReviewDetail, Severity, Verdict } from "../types";
import { SeverityBadge, SourceBadge, VerdictBadge } from "../components/Badges";
import { ArrowUpRightIcon, ChevronLeftIcon } from "../components/Icons";
import { formatMs, formatNumber, formatTime, shortSha } from "../format";

const VERDICT_COLORS: Record<Verdict, string> = {
  SHIP: "var(--green)",
  FIX: "var(--amber)",
  BLOCK: "var(--red)"
};

const SEVERITIES: Array<"ALL" | Severity> = ["ALL", "BLOCKER", "MAJOR", "MINOR", "INFO"];

export function ReviewDetailPage() {
  const { id } = useParams();
  const [review, setReview] = useState<ReviewDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<"ALL" | Severity>("ALL");

  useEffect(() => {
    if (!id) return;
    setReview(null);
    setError(null);
    api.review(Number(id))
      .then(setReview)
      .catch((e: Error) => setError(e.message));
  }, [id]);

  if (error) {
    return (
      <>
        <div className="topbar"><div className="crumb">Console / Reviews / Detail</div></div>
        <div className="alert error">加载失败：{error}</div>
        <Link className="btn ghost" to="/reviews"><ChevronLeftIcon />返回审查记录</Link>
      </>
    );
  }

  if (!review) {
    return (
      <>
        <div className="topbar"><div className="crumb">Console / Reviews / Detail</div></div>
        <div className="loading">正在加载审查结果…</div>
      </>
    );
  }

  const findings = filter === "ALL" ? review.findings : review.findings.filter((f) => f.severity === filter);
  const groups = new Map<string, Finding[]>();
  findings.forEach((finding) => {
    const list = groups.get(finding.file) ?? [];
    list.push(finding);
    groups.set(finding.file, list);
  });
  const ruleCount = review.findings.filter((f) => f.source === "RULE").length;
  const llmCount = review.findings.filter((f) => f.source === "LLM").length;

  return (
    <>
      <div className="topbar">
        <div className="crumb">Console / Reviews / #{review.prNumber}</div>
        <div className="top-actions">
          <Link className="btn ghost" to="/reviews"><ChevronLeftIcon />返回</Link>
          {review.prUrl && (
            <a className="btn" href={review.prUrl} target="_blank" rel="noreferrer">
              打开 PR<ArrowUpRightIcon />
            </a>
          )}
        </div>
      </div>

      <div className="panel">
        <div className="detail-head">
          <div
            className={`ring ${review.verdict.toLowerCase()}`}
            style={{ "--pct": review.score, "--ring-color": VERDICT_COLORS[review.verdict] } as unknown as React.CSSProperties}
          >
            <div className="ring-inner">
              <span className="ring-score">{review.score}</span>
              <span className="ring-label">score</span>
            </div>
          </div>
          <div style={{ minWidth: 0 }}>
            <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
              <VerdictBadge verdict={review.verdict} />
              <span className="repo-name">{review.repo}#{review.prNumber}</span>
              {review.published ? <span className="badge ship">已回写 GitHub</span> : <span className="badge info">仅本地</span>}
            </div>
            <h1 className="detail-title">{review.title}</h1>
            <div className="detail-meta">
              <span>作者 {review.author ?? "—"}</span>
              <span>目标 {review.baseRef ?? "—"}</span>
              <span className="mono">head {shortSha(review.headSha)}</span>
              <span>{formatTime(review.createdAt)}</span>
              {review.llmModel && <span className="mono">{review.llmModel}</span>}
            </div>
          </div>
        </div>
        {review.summary && <div className="detail-summary">{review.summary}</div>}
        {review.publishError && <div className="alert error" style={{ margin: "0 20px 16px" }}>GitHub 回写失败：{review.publishError}</div>}
        <div className="metrics-strip">
          <div className="metric-cell"><span>发现项</span><b className="num">{review.findings.length}</b></div>
          <div className="metric-cell"><span>规则 / 模型</span><b className="num">{ruleCount} / {llmCount}</b></div>
          <div className="metric-cell"><span>模型 tokens</span><b className="num">{formatNumber(review.inputTokens + review.outputTokens)}</b></div>
          <div className="metric-cell"><span>端到端耗时</span><b className="num">{formatMs(review.durationMs)}</b></div>
        </div>
      </div>

      <div className="panel" style={{ marginTop: 14 }}>
        <div className="panel-head">
          <div>
            <div className="panel-kicker">Findings</div>
            <div className="panel-title">风险明细</div>
          </div>
          <div className="filters">
            {SEVERITIES.map((severity) => (
              <button key={severity} className={`chip ${filter === severity ? "on" : ""}`} onClick={() => setFilter(severity)}>
                {severity}
              </button>
            ))}
          </div>
        </div>
        <div className="panel-body">
          {findings.length === 0 && (
            <div className="empty">
              <strong>没有这一类发现</strong>
              换一个级别，或该 PR 在该级别下没有风险。
            </div>
          )}
          {Array.from(groups.entries()).map(([file, items]) => (
            <div className="file-group" key={file}>
              <div className="file-head">
                <span className="file-path">{file}</span>
                <span className="file-count">{items.length} 项</span>
              </div>
              {items.map((finding) => (
                <div className={`finding ${finding.severity.toLowerCase()}`} key={finding.id}>
                  <div className="finding-top">
                    <SeverityBadge severity={finding.severity} />
                    <span className="badge src">{finding.category}</span>
                    <SourceBadge source={finding.source} />
                    {finding.line !== null && <span className="finding-meta mono">L{finding.line}</span>}
                    {finding.ruleId && <span className="finding-meta mono">{finding.ruleId}</span>}
                  </div>
                  <div className="finding-title">{finding.title}</div>
                  {finding.evidence && <pre className="code">{finding.evidence}</pre>}
                  {finding.suggestion && <div className="suggestion">建议：{finding.suggestion}</div>}
                </div>
              ))}
            </div>
          ))}
        </div>
      </div>

      <div className="alert info" style={{ marginTop: 14 }}>
        这一页是审查时的冻结快照：head {shortSha(review.headSha)}。PR 推送新提交后会生成新的审查记录，不会覆盖旧结论。
      </div>
    </>
  );
}
