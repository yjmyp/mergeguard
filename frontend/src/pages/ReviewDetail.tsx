import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api";
import type { DiffFile, Finding, ReviewDetail, ReviewDiff, Severity, Verdict } from "../types";
import { SeverityBadge, SourceBadge, VerdictBadge } from "../components/Badges";
import { ArrowUpRightIcon, ChevronLeftIcon } from "../components/Icons";
import { formatMs, formatNumber, formatTime, shortSha } from "../format";

const VERDICT_COLORS: Record<Verdict, string> = { SHIP: "var(--green)", FIX: "var(--amber)", BLOCK: "var(--red)" };
const SEVERITIES: Array<"ALL" | Severity> = ["ALL", "BLOCKER", "MAJOR", "MINOR", "INFO"];
const STATUS_LABELS: Record<Finding["status"], string> = { OPEN: "待判定", ACCEPTED: "已确认", DISMISSED: "误报", FIXED: "已修复" };
const STATUS_TONES: Record<Finding["status"], string> = { OPEN: "info", ACCEPTED: "ship", DISMISSED: "block", FIXED: "fix" };

export function ReviewDetailPage() {
  const { id } = useParams();
  const [review, setReview] = useState<ReviewDetail | null>(null);
  const [diff, setDiff] = useState<ReviewDiff | null>(null);
  const [view, setView] = useState<"findings" | "diff">("findings");
  const [filter, setFilter] = useState<"ALL" | Severity>("ALL");
  const [error, setError] = useState<string | null>(null);
  const [busyFinding, setBusyFinding] = useState<number | null>(null);

  useEffect(() => {
    if (!id) return;
    setReview(null); setDiff(null); setError(null);
    api.review(Number(id)).then(setReview).catch((e: Error) => setError(e.message));
  }, [id]);

  const openDiff = () => {
    setView("diff");
    if (!diff && id) {
      api.reviewDiff(Number(id)).then(setDiff).catch((e: Error) => setError(e.message));
    }
  };

  const feedback = (finding: Finding, status: Finding["status"]) => {
    setBusyFinding(finding.id);
    api.feedback(finding.id, status)
      .then(() => setReview((current) => current ? {
        ...current,
        findings: current.findings.map((item) => item.id === finding.id
          ? { ...item, status, feedbackAt: new Date().toISOString() }
          : item)
      } : current))
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusyFinding(null));
  };

  if (error) {
    return (<><div className="topbar"><div className="crumb">Console / Reviews / Detail</div></div><div className="alert error">加载失败：{error}</div><Link className="btn ghost" to="/reviews"><ChevronLeftIcon />返回审查记录</Link></>);
  }
  if (!review) {
    return (<><div className="topbar"><div className="crumb">Console / Reviews / Detail</div></div><div className="loading">正在加载审查结果…</div></>);
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
  const judged = review.findings.filter((f) => f.status !== "OPEN").length;

  return (
    <>
      <div className="topbar">
        <div className="crumb">Console / Reviews / #{review.prNumber}</div>
        <div className="top-actions">
          <Link className="btn ghost" to="/reviews"><ChevronLeftIcon />返回</Link>
          {review.prUrl && <a className="btn" href={review.prUrl} target="_blank" rel="noreferrer">打开 PR<ArrowUpRightIcon /></a>}
        </div>
      </div>

      <div className="panel">
        <div className="detail-head">
          <div className={`ring ${review.verdict.toLowerCase()}`} style={{ "--pct": review.score, "--ring-color": VERDICT_COLORS[review.verdict] } as unknown as React.CSSProperties}>
            <div className="ring-inner"><span className="ring-score">{review.score}</span><span className="ring-label">score</span></div>
          </div>
          <div style={{ minWidth: 0 }}>
            <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
              <VerdictBadge verdict={review.verdict} />
              <span className="repo-name">{review.repo}#{review.prNumber}</span>
              {review.published ? <span className="badge ship">已回写 GitHub</span> : <span className="badge info">仅本地</span>}
              {review.injectionNeutralized > 0 && <span className="badge block">中和注入 {review.injectionNeutralized}</span>}
            </div>
            <h1 className="detail-title">{review.title}</h1>
            <div className="detail-meta">
              <span>作者 {review.author ?? "—"}</span><span>目标 {review.baseRef ?? "—"}</span>
              <span className="mono">head {shortSha(review.headSha)}</span><span>{formatTime(review.createdAt)}</span>
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
          <div className="metric-cell"><span>拉取 / 规则 / 模型</span><b className="num">{review.diffMs} / {review.ruleMs} / {review.llmMs} ms</b></div>
          <div className="metric-cell"><span>模型覆盖 / 分块</span><b className="num">{review.llmCoverage}% · {review.llmChunks}</b></div>
        </div>
      </div>

      <div className="panel" style={{ marginTop: 14 }}>
        <div className="panel-head">
          <div>
            <div className="panel-kicker">Evidence</div>
            <div className="panel-title">{view === "findings" ? "风险明细" : "Diff 快照"}</div>
          </div>
          <div className="filters">
            <button className={`chip ${view === "findings" ? "on" : ""}`} onClick={() => setView("findings")}>发现项</button>
            <button className={`chip ${view === "diff" ? "on" : ""}`} onClick={openDiff}>Diff</button>
            {view === "findings" && SEVERITIES.map((severity) => (
              <button key={severity} className={`chip ${filter === severity ? "on" : ""}`} onClick={() => setFilter(severity)}>{severity}</button>
            ))}
          </div>
        </div>

        {view === "findings" ? (
          <div className="panel-body">
            {judged > 0 && <div className="hint" style={{ marginBottom: 10 }}>已判定 {judged} 条，可在每条下方标注确认 / 误报 / 已修复。</div>}
            {findings.length === 0 && <div className="empty"><strong>没有这一类发现</strong>换一个级别，或该 PR 在该级别下没有风险。</div>}
            {Array.from(groups.entries()).map(([file, items]) => (
              <div className="file-group" key={file}>
                <div className="file-head"><span className="file-path">{file}</span><span className="file-count">{items.length} 项</span></div>
                {items.map((finding) => (
                  <div className={`finding ${finding.severity.toLowerCase()}`} key={finding.id}>
                    <div className="finding-top">
                      <SeverityBadge severity={finding.severity} />
                      <span className="badge src">{finding.category}</span>
                      <SourceBadge source={finding.source} />
                      {finding.line !== null && <span className="finding-meta mono">L{finding.line}</span>}
                      {finding.ruleId && <span className="finding-meta mono">{finding.ruleId}</span>}
                      <span className={`badge ${STATUS_TONES[finding.status]}`}>{STATUS_LABELS[finding.status]}</span>
                    </div>
                    <div className="finding-title">{finding.title}</div>
                    {finding.evidence && <pre className="code">{finding.evidence}</pre>}
                    {finding.suggestion && <div className="suggestion">建议：{finding.suggestion}</div>}
                    <div className="feedback-row">
                      <span className="hint">这条判断是否成立？</span>
                      <button className={`mini-btn ${finding.status === "ACCEPTED" ? "on" : ""}`} disabled={busyFinding === finding.id} onClick={() => feedback(finding, "ACCEPTED")}>确认有效</button>
                      <button className={`mini-btn ${finding.status === "DISMISSED" ? "on" : ""}`} disabled={busyFinding === finding.id} onClick={() => feedback(finding, "DISMISSED")}>标记误报</button>
                      <button className={`mini-btn ${finding.status === "FIXED" ? "on" : ""}`} disabled={busyFinding === finding.id} onClick={() => feedback(finding, "FIXED")}>已修复</button>
                    </div>
                  </div>
                ))}
              </div>
            ))}
          </div>
        ) : (
          <div className="diff-wrap">
            {!diff ? <div className="loading">正在加载 diff…</div> : diff.files.length === 0 ? <div className="empty"><strong>没有可展示的 diff</strong>该审查没有保存文件快照。</div> :
              diff.files.map((file) => <DiffView key={file.file} file={file} findings={review.findings.filter((f) => f.file === file.file)} />)}
          </div>
        )}
      </div>

      <div className="alert info" style={{ marginTop: 14 }}>这一页是审查时的冻结快照：head {shortSha(review.headSha)}。PR 推送新提交后会生成新的审查记录，不会覆盖旧结论。</div>
    </>
  );
}

function DiffView({ file, findings }: { file: DiffFile; findings: Finding[] }) {
  const lines = (file.patch ?? "").split("\n");
  let newLine = 0;
  let rendered = 0;
  return (
    <div className="diff-file">
      <div className="diff-head"><span className="file-path">{file.file}</span><span className="file-count">+{file.additions} / -{file.deletions}</span></div>
      <pre className="diff-body">
        {lines.map((line, index) => {
          if (rendered++ > 260) return null;
          const hunk = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
          if (hunk) { newLine = Number(hunk[2]); return <div className="diff-hunk" key={index}>{line}</div>; }
          const kind = line.startsWith("+") ? "add" : line.startsWith("-") ? "del" : "ctx";
          const lineNo = kind === "add" ? newLine++ : kind === "ctx" ? newLine++ : null;
          const markers = lineNo === null ? [] : findings.filter((f) => f.line === lineNo);
          if (markers.length === 0) {
            return <div className={`diff-line ${kind}`} key={index}><span className="diff-no">{lineNo ?? ""}</span><span className="diff-text">{line || " "}</span></div>;
          }
          return (
            <div key={index}>
              {markers.map((marker) => (
                <div className={`diff-marker ${marker.severity.toLowerCase()}`} key={marker.id}>
                  <SeverityBadge severity={marker.severity} /> {marker.title} {marker.ruleId && <span className="mono">{marker.ruleId}</span>}
                </div>
              ))}
              <div className={`diff-line ${kind}`}><span className="diff-no">{lineNo ?? ""}</span><span className="diff-text">{line || " "}</span></div>
            </div>
          );
        })}
      </pre>
    </div>
  );
}
