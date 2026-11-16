import { NavLink, Outlet } from "react-router-dom";
import { GridIcon, ListIcon, PulseIcon, RefreshIcon, RepoIcon, ShieldIcon } from "./Icons";

export function Layout() {
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark"><ShieldIcon /></div>
          <div>
            <div className="brand-name">MergeGuard</div>
            <div className="brand-sub">merge risk review</div>
          </div>
        </div>
        <nav className="nav">
          <NavLink to="/" end><GridIcon />概览</NavLink>
          <NavLink to="/reviews"><ListIcon />审查记录</NavLink>
          <NavLink to="/quality"><PulseIcon />质量闭环</NavLink>
          <NavLink to="/jobs"><RefreshIcon />任务队列</NavLink>
          <NavLink to="/repos"><RepoIcon />接入仓库</NavLink>
        </nav>
        <div className="side-foot">
          <div className="live-pill"><span className="live-dot" />服务在线 · 本地 8081</div>
          <div className="side-note">
            规则负责确定性风险，模型负责语义判断；误报反馈会沉淀为规则质量数据。
          </div>
        </div>
      </aside>
      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}
