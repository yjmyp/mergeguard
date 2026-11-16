import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";
import type { Overview, Verdict } from "../types";
import { VerdictBadge } from "../components/Badges";
import { ArrowUpRightIcon, CoinIcon, PulseIcon, RefreshIcon, ShieldIcon } from "../components/Icons";
import { formatMs, formatNumber, formatTime } from "../format";

const VERDICT_COLORS: Record<Verdict, string> = {
  SHIP: "var(--green)",
  FIX: "var(--amber)",
  BLOCK: "var(--red)"
};

export function OverviewPage() {
  const [data, setData] = useState<Overview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = () => {
    setLoading(true);
    api.overview()
      .then((value) => { setData(value); setError(null); })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const maxTotal = Math.max(1, ...(data?.daily.map((d) => d.total) ?? [1]));
  const counts = (verdict: Verdict) => data?.verdicts.find((v) => v.verdict === verdict)?.count ?? 0;
  const verdictTotal = Math.max(1, (data?.verdicts ?? []).reduce((sum, v) => sum + v.count, 0));

  return (
    <>
      <div className="topbar">
        <div className="crumb">Console / Overview</div>
        <div className="top-actions">
          <button className="btn ghost" onClick={load}><RefreshIcon />刷新</button>
          <Link className="btn" to="/reviews">审查记录<ArrowUpRightIcon /></Link>
        </div>
      </div>

      <div className="page-head">
        <h1 className="page-title">合并前风险，一次看清</h1>
        <p className="page-sub">
          每个 PR 先过 20 条确定性规则，再由模型做语义复核；结论落在 GitHub 和这里，拦截理由可逐条回溯。
        </p>
      </div>

      {error && <div className="alert error">加载失败：{error}</div>}

      <div className="kpis">
        <div className="kpi">
          <div className="kpi-label">审查总数<span><ShieldIcon /></span></div>
          <div className="kpi-value">{loading ? "—" : formatNumber(data?.reviews ?? 0)}<small>PR</small></div>
          <div className="kpi-foot">其中 {data?.llmReviews ?? 0} 次有模型参与</div>
          <span className="kpi-accent" />
        </div>
        <div className="kpi">
          <div className="kpi-label">阻断率<span><PulseIcon /></span></div>
          <div className="kpi-value">{loading ? "—" : `${Math.round((data?.blockRate ?? 0) * 100)}`}<small>%</small></div>
          <div className="kpi-foot">{data?.blocked ?? 0} 个 PR 被判 BLOCK</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">平均耗时<span><RefreshIcon /></span></div>
          <div className="kpi-value">{loading ? "—" : formatMs(data?.avgDurationMs ?? 0)}</div>
          <div className="kpi-foot">拉取 diff + 规则 + 模型端到端</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">模型用量<span><CoinIcon /></span></div>
          <div className="kpi-value">{loading ? "—" : formatNumber(data?.totalTokens ?? 0)}<small>tokens</small></div>
          <div className="kpi-foot">按每次审查真实计量</div>
        </div>
      </div>

      <div className="grid-2">
        <div className="panel">
          <div className="panel-head">
            <div>
              <div className="panel-kicker">Last 7 days</div>
              <div className="panel-title">每日审查量</div>
            </div>
            <div className="legend">
              <span><i className="swatch" style={{ background: "#20232a" }} />全部</span>
              <span><i className="swatch" style={{ background: "var(--red)" }} />阻断</span>
            </div>
          </div>
          <div className="panel-body">
            <div className="chart">
              {(data?.daily ?? []).map((day) => (
                <div className="chart-col" key={day.date}>
                  <div className="chart-bars">
                    <div className="chart-total" style={{ height: `${Math.max(3, (day.total / maxTotal) * 100)}%` }} />
                    <div className="chart-block" style={{ height: `${Math.max(0, (day.blocked / maxTotal) * 100)}%` }} />
                  </div>
                  <div className="chart-label">{day.date}</div>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div className="panel">
          <div className="panel-head">
            <div>
              <div className="panel-kicker">Verdict mix</div>
              <div className="panel-title">判定分布</div>
            </div>
          </div>
          <div className="panel-body">
            <div className="verdict-list">
              {(["SHIP", "FIX", "BLOCK"] as Verdict[]).map((verdict) => (
                <div className="verdict-row" key={verdict}>
                  <VerdictBadge verdict={verdict} />
                  <div className="verdict-track">
                    <div className="verdict-fill" style={{ width: `${(counts(verdict) / verdictTotal) * 100}%`, background: VERDICT_COLORS[verdict] }} />
                  </div>
                  <span className="verdict-num num">{counts(verdict)}</span>
                </div>
              ))}
            </div>
            <hr className="sep" />
            <div className="hint">
              SHIP 表示未发现阻断/高优问题；FIX 表示存在需修复项；BLOCK 表示存在数据丢失、凭证泄露等合并前必须处理的风险。
            </div>
          </div>
        </div>
      </div>

      <div className="panel">
        <div className="panel-head">
          <div>
            <div className="panel-kicker">Recent reviews</div>
            <div className="panel-title">最近审查</div>
          </div>
          <Link className="link" to="/reviews">全部记录<ArrowUpRightIcon /></Link>
        </div>
        {(data?.recent?.length ?? 0) === 0 ? (
          <div className="empty">
            <strong>还没有审查记录</strong>
            去「接入仓库」添加一个仓库并扫描，或手动触发一次 PR 审查。
          </div>
        ) : (
          <table>
            <thead>
              <tr><th>判定</th><th>PR</th><th>标题</th><th>发现</th><th>耗时</th><th>时间</th></tr>
            </thead>
            <tbody>
              {data?.recent.map((review) => (
                <tr key={review.id}>
                  <td><VerdictBadge verdict={review.verdict} /></td>
                  <td>
                    <Link className="pr-link" to={`/reviews/${review.id}`}>
                      {review.repo.split("/")[1]}#{review.prNumber}
                    </Link>
                  </td>
                  <td>{review.title}</td>
                  <td className="num">{review.findings}</td>
                  <td className="num">{formatMs(review.durationMs)}</td>
                  <td className="num">{formatTime(review.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
