import { useEffect, useState } from "react";
import { api } from "../api";
import type { Quality } from "../types";
import { PulseIcon, RefreshIcon, ShieldIcon, CoinIcon } from "../components/Icons";

export function QualityPage() {
  const [data, setData] = useState<Quality | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    api.quality().then((value) => { setData(value); setError(null); }).catch((e: Error) => setError(e.message));
  };
  useEffect(load, []);

  const pct = (value: number) => `${Math.round(value * 100)}%`;

  return (
    <>
      <div className="topbar">
        <div className="crumb">Console / Quality loop</div>
        <div className="top-actions"><button className="btn ghost" onClick={load}><RefreshIcon />刷新</button></div>
      </div>
      <div className="page-head">
        <h1 className="page-title">规则质量闭环</h1>
        <p className="page-sub">
          每条发现都可以被确认、判为误报或标记为已修复。这里汇总规则的历史命中与误报，作为调整阈值和下线规则依据。
        </p>
      </div>
      {error && <div className="alert error">{error}</div>}

      <div className="kpis">
        <div className="kpi"><div className="kpi-label">发现项总数<span><ShieldIcon /></span></div><div className="kpi-value">{data?.totalFindings ?? 0}</div><div className="kpi-foot">包含规则与模型发现</div></div>
        <div className="kpi"><div className="kpi-label">已判定<span><PulseIcon /></span></div><div className="kpi-value">{data?.judged ?? 0}</div><div className="kpi-foot">确认 + 误报 + 已修复</div></div>
        <div className="kpi"><div className="kpi-label">有效率<span><PulseIcon /></span></div><div className="kpi-value">{data ? pct(data.acceptanceRate) : "—"}</div><div className="kpi-foot">(确认 + 已修复) / 已判定</div></div>
        <div className="kpi"><div className="kpi-label">误报<span><CoinIcon /></span></div><div className="kpi-value">{data?.dismissed ?? 0}</div><div className="kpi-foot">用于下线或降级规则</div></div>
      </div>

      <div className="panel">
        <div className="panel-head">
          <div><div className="panel-kicker">Per rule</div><div className="panel-title">规则表现</div></div>
          <span className="hint">OPEN 不计入有效率</span>
        </div>
        {(data?.rules?.length ?? 0) === 0 ? (
          <div className="empty"><strong>还没有反馈数据</strong>在审查详情里对发现项做一次确认或误报标记，这里就会出现统计。</div>
        ) : (
          <table>
            <thead><tr><th>规则</th><th>总数</th><th>确认</th><th>已修复</th><th>误报</th><th>待判</th><th>有效率</th><th>平均置信度</th></tr></thead>
            <tbody>
              {data?.rules.map((rule) => (
                <tr key={rule.ruleId}>
                  <td className="mono">{rule.ruleId}</td>
                  <td className="num">{rule.total}</td>
                  <td className="num">{rule.accepted}</td>
                  <td className="num">{rule.fixed}</td>
                  <td className="num">{rule.dismissed}</td>
                  <td className="num">{rule.open}</td>
                  <td>
                    <div className="quality-cell">
                      <div className="quality-track"><div className="quality-fill" style={{ width: `${Math.round(rule.acceptanceRate * 100)}%` }} /></div>
                      <span className="num">{pct(rule.acceptanceRate)}</span>
                    </div>
                  </td>
                  <td className="num">{rule.avgConfidence.toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
