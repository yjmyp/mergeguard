import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";
import type { JobDto, JobStats } from "../types";
import { RefreshIcon } from "../components/Icons";
import { formatTime, shortSha } from "../format";

const TONES: Record<JobDto["status"], string> = { QUEUED: "info", RUNNING: "fix", DONE: "ship", FAILED: "block" };

export function JobsPage() {
  const [jobs, setJobs] = useState<JobDto[]>([]);
  const [stats, setStats] = useState<JobStats | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<number | null>(null);

  const load = () => {
    Promise.all([api.jobs(40), api.jobStats()])
      .then(([list, counters]) => { setJobs(list); setStats(counters); setError(null); })
      .catch((e: Error) => setError(e.message));
  };
  useEffect(() => { load(); const timer = setInterval(load, 6000); return () => clearInterval(timer); }, []);

  const retry = (job: JobDto) => {
    setBusy(job.id);
    api.retryJob(job.id).then(load).catch((e: Error) => setError(e.message)).finally(() => setBusy(null));
  };

  return (
    <>
      <div className="topbar">
        <div className="crumb">Console / Durable jobs</div>
        <div className="top-actions"><button className="btn ghost" onClick={load}><RefreshIcon />刷新</button></div>
      </div>
      <div className="page-head">
        <h1 className="page-title">持久化审查队列</h1>
        <p className="page-sub">
          Webhook 与轮询产生的审查先落 MySQL，再由 worker 以租约领取。进程重启不会丢任务，失败按指数退避重试。
        </p>
      </div>
      {error && <div className="alert error">{error}</div>}

      <div className="kpis">
        {(["QUEUED", "RUNNING", "DONE", "FAILED"] as const).map((key) => (
          <div className="kpi" key={key}>
            <div className="kpi-label">{key}</div>
            <div className="kpi-value">{stats ? stats[key.toLowerCase() as keyof JobStats] : 0}</div>
            <div className="kpi-foot">{key === "FAILED" ? "可在右侧手动重试" : "实时统计"}</div>
          </div>
        ))}
      </div>

      <div className="panel">
        <div className="panel-head">
          <div><div className="panel-kicker">Queue</div><div className="panel-title">最近任务</div></div>
          <span className="hint">每 6 秒自动刷新</span>
        </div>
        {jobs.length === 0 ? (
          <div className="empty"><strong>队列为空</strong>Webhook 或轮询入队后会显示在这里。</div>
        ) : (
          <table>
            <thead><tr><th>状态</th><th>PR</th><th>head</th><th>尝试</th><th>下次尝试</th><th>结果 / 错误</th><th></th></tr></thead>
            <tbody>
              {jobs.map((job) => (
                <tr key={job.id}>
                  <td><span className={`badge ${TONES[job.status]}`}>{job.status}</span></td>
                  <td>{job.status === "DONE" && job.reviewId ? <Link className="pr-link" to={`/reviews/${job.reviewId}`}>{job.name}#{job.prNumber}</Link> : `${job.name}#${job.prNumber}`}</td>
                  <td className="mono">{shortSha(job.headSha)}</td>
                  <td className="num">{job.attempts}/{job.maxAttempts}</td>
                  <td className="num">{formatTime(job.nextAttemptAt)}</td>
                  <td className="job-error">{job.lastError ?? (job.reviewId ? `review #${job.reviewId}` : "—")}</td>
                  <td>{job.status === "FAILED" && <button className="mini-btn" disabled={busy === job.id} onClick={() => retry(job)}>{busy === job.id ? "重试中…" : "重试"}</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
