import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api } from "../api";
import type { Page, RepoDto, ReviewSummary, Verdict } from "../types";
import { VerdictBadge } from "../components/Badges";
import { PlusIcon, RefreshIcon } from "../components/Icons";
import { formatMs, formatTime } from "../format";

const FILTERS: Array<{ label: string; value: "ALL" | Verdict }> = [
  { label: "全部", value: "ALL" },
  { label: "SHIP", value: "SHIP" },
  { label: "FIX", value: "FIX" },
  { label: "BLOCK", value: "BLOCK" }
];

export function ReviewsPage() {
  const navigate = useNavigate();
  const [verdict, setVerdict] = useState<"ALL" | Verdict>("ALL");
  const [repo, setRepo] = useState("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<ReviewSummary> | null>(null);
  const [repos, setRepos] = useState<RepoDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState({ owner: "yjmyp", name: "mergeguard-demo", prNumber: "", publish: true });
  const [submitting, setSubmitting] = useState(false);

  const load = () => {
    setLoading(true);
    api.reviews({ page, size: 15, verdict, repo })
      .then((value) => { setData(value); setError(null); })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, [page, verdict, repo]);
  useEffect(() => {
    api.repos().then(setRepos).catch(() => undefined);
  }, []);

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    const prNumber = Number(form.prNumber);
    if (!form.owner || !form.name || !prNumber) {
      setError("请填写仓库 owner/name 和 PR 编号");
      return;
    }
    setSubmitting(true);
    setError(null);
    api.trigger({ owner: form.owner, name: form.name, prNumber, publish: form.publish })
      .then((review) => navigate(`/reviews/${review.id}`))
      .catch((e: Error) => setError(e.message))
      .finally(() => setSubmitting(false));
  };

  return (
    <>
      <div className="topbar">
        <div className="crumb">Console / Reviews</div>
        <div className="top-actions">
          <button className="btn ghost" onClick={load}><RefreshIcon />刷新</button>
          <button className="btn primary" onClick={() => setShowForm((value) => !value)}>
            <PlusIcon />手动审查
          </button>
        </div>
      </div>

      <div className="page-head">
        <h1 className="page-title">审查记录</h1>
        <p className="page-sub">每次 PR 审查保存完整证据链：判定、分数、发现项、模型用量和是否已回写 GitHub。</p>
      </div>

      {error && <div className="alert error">{error}</div>}

      {showForm && (
        <div className="panel" style={{ marginBottom: 14 }}>
          <div className="panel-head">
            <div>
              <div className="panel-kicker">Manual review</div>
              <div className="panel-title">手动触发一次 PR 审查</div>
            </div>
            <span className="hint">需要仓库已接入且 token 有读权限</span>
          </div>
          <div className="panel-body">
            <form className="form-row" onSubmit={submit}>
              <div className="field">
                <label>Owner</label>
                <input className="input" value={form.owner} onChange={(e) => setForm({ ...form, owner: e.target.value })} />
              </div>
              <div className="field">
                <label>Repo</label>
                <input className="input" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              </div>
              <div className="field">
                <label>PR 编号</label>
                <input className="input" style={{ width: 100 }} inputMode="numeric" value={form.prNumber} onChange={(e) => setForm({ ...form, prNumber: e.target.value })} />
              </div>
              <label className="toggle" style={{ paddingBottom: 8 }}>
                <input type="checkbox" checked={form.publish} onChange={(e) => setForm({ ...form, publish: e.target.checked })} />
                回写 GitHub
              </label>
              <button className="btn primary" type="submit" disabled={submitting}>{submitting ? "审查中…" : "开始审查"}</button>
            </form>
          </div>
        </div>
      )}

      <div className="panel">
        <div className="panel-head">
          <div className="filters">
            {FILTERS.map((filter) => (
              <button
                key={filter.value}
                className={`chip ${verdict === filter.value ? "on" : ""}`}
                onClick={() => { setVerdict(filter.value); setPage(0); }}
              >
                {filter.label}
              </button>
            ))}
            <select className="select" value={repo} onChange={(e) => { setRepo(e.target.value); setPage(0); }}>
              <option value="">全部仓库</option>
              {repos.map((item) => <option key={item.id} value={item.fullName}>{item.fullName}</option>)}
            </select>
          </div>
          <span className="hint">{data ? `共 ${data.total} 条` : ""}</span>
        </div>

        {(data?.items.length ?? 0) === 0 ? (
          <div className="empty">
            <strong>{loading ? "加载中…" : "没有符合条件的记录"}</strong>
            {!loading && "换一个筛选条件，或先触发一次审查。"}
          </div>
        ) : (
          <>
            <table>
              <thead>
                <tr><th>判定</th><th>PR</th><th>标题</th><th>发现</th><th>模型</th><th>耗时</th><th>时间</th></tr>
              </thead>
              <tbody>
                {data?.items.map((review) => (
                  <tr key={review.id} onClick={() => navigate(`/reviews/${review.id}`)} style={{ cursor: "pointer" }}>
                    <td><VerdictBadge verdict={review.verdict} /></td>
                    <td>
                      <div className="repo-cell">
                        <span className="repo-name">{review.repo}</span>
                        <Link className="pr-link" to={`/reviews/${review.id}`}>#{review.prNumber}</Link>
                      </div>
                    </td>
                    <td>{review.title}</td>
                    <td className="num">{review.findings}</td>
                    <td className="num">{review.tokens > 0 ? `${review.tokens} tok` : "—"}</td>
                    <td className="num">{formatMs(review.durationMs)}</td>
                    <td className="num">{formatTime(review.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="pagination">
              <span>第 {(data?.page ?? 0) + 1} / {Math.max(1, data?.pages ?? 1)} 页</span>
              <div className="pagination-actions">
                <button className="mini-btn" disabled={(data?.page ?? 0) === 0} onClick={() => setPage((value) => Math.max(0, value - 1))}>上一页</button>
                <button className="mini-btn" disabled={(data?.page ?? 0) + 1 >= (data?.pages ?? 1)} onClick={() => setPage((value) => value + 1)}>下一页</button>
              </div>
            </div>
          </>
        )}
      </div>

      <p className="hint" style={{ marginTop: 12 }}>
        默认每页 15 条。审查是异步执行的，PR 刚推送时可能需要几秒到几十秒，刷新即可看到结果。
      </p>
      <p className="hint">
        判定由确定性规则与模型发现共同计算，分数越低风险越高。
      </p>
    </>
  );
}
