import { useEffect, useState } from "react";
import { api, getConnection, setConnection } from "../api";
import type { RepoDto } from "../types";
import { PlusIcon, RefreshIcon } from "../components/Icons";
import { formatTime } from "../format";

export function ReposPage() {
  const [repos, setRepos] = useState<RepoDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [form, setForm] = useState({ owner: "", name: "", autoReview: true });
  const [saving, setSaving] = useState(false);
  const connection = getConnection();
  const [base, setBase] = useState(connection.base);
  const [key, setKey] = useState(connection.key);

  const load = () => {
    api.repos()
      .then((value) => { setRepos(value); setError(null); })
      .catch((e: Error) => setError(e.message));
  };

  useEffect(load, []);

  const add = (event: React.FormEvent) => {
    event.preventDefault();
    if (!form.owner || !form.name) {
      setError("请填写 owner 和仓库名");
      return;
    }
    setSaving(true);
    setError(null);
    api.addRepo({ owner: form.owner.trim(), name: form.name.trim(), autoReview: form.autoReview })
      .then(() => { setForm({ owner: "", name: "", autoReview: true }); setNotice("仓库已接入"); load(); })
      .catch((e: Error) => setError(e.message))
      .finally(() => setSaving(false));
  };

  const toggle = (repo: RepoDto, field: "enabled" | "autoReview") => {
    const payload = field === "enabled" ? { enabled: !repo.enabled } : { autoReview: !repo.autoReview };
    setBusyId(repo.id);
    api.updateRepo(repo.id, payload)
      .then((updated) => setRepos((items) => items.map((item) => item.id === updated.id ? updated : item)))
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusyId(null));
  };

  const scan = (repo: RepoDto) => {
    setBusyId(repo.id);
    setNotice(null);
    api.scanRepo(repo.id)
      .then((result) => setNotice(`${repo.fullName}：${result.openPulls} 个 open PR，新排队 ${result.queued} 个审查`))
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusyId(null));
  };

  return (
    <>
      <div className="topbar">
        <div className="crumb">Console / Repositories</div>
        <div className="top-actions">
          <button className="btn ghost" onClick={load}><RefreshIcon />刷新</button>
        </div>
      </div>

      <div className="page-head">
        <h1 className="page-title">接入仓库</h1>
        <p className="page-sub">
          开启自动审查后，后台每 5 分钟扫描一次 open PR；也可以手动扫描。Webhook 模式见 README，二者共用同一套审查流水线。
        </p>
      </div>

      {error && <div className="alert error">{error}</div>}
      {notice && <div className="alert ok">{notice}</div>}

      <div className="panel" style={{ marginBottom: 14 }}>
        <div className="panel-head">
          <div>
            <div className="panel-kicker">Connect</div>
            <div className="panel-title">添加 GitHub 仓库</div>
          </div>
          <span className="hint">需要 token 对该仓库有读取权限</span>
        </div>
        <div className="panel-body">
          <form className="form-row" onSubmit={add}>
            <div className="field">
              <label>Owner</label>
              <input className="input" placeholder="yjmyp" value={form.owner} onChange={(e) => setForm({ ...form, owner: e.target.value })} />
            </div>
            <div className="field">
              <label>Repository</label>
              <input className="input" placeholder="mergeguard-demo" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </div>
            <label className="toggle" style={{ paddingBottom: 8 }}>
              <input type="checkbox" checked={form.autoReview} onChange={(e) => setForm({ ...form, autoReview: e.target.checked })} />
              自动审查 open PR
            </label>
            <button className="btn primary" type="submit" disabled={saving}><PlusIcon />{saving ? "接入中…" : "接入"}</button>
          </form>
        </div>
      </div>

      <div className="panel">
        <div className="panel-head">
          <div>
            <div className="panel-kicker">Connected</div>
            <div className="panel-title">已接入仓库</div>
          </div>
          <span className="hint">{repos.length} 个</span>
        </div>
        {repos.length === 0 ? (
          <div className="empty"><strong>还没有仓库</strong>用上面的表单添加第一个仓库。</div>
        ) : repos.map((repo) => (
          <div className="repo-card" key={repo.id}>
            <div className="repo-card-left">
              <span className="repo-card-title">{repo.fullName}</span>
              <span className="repo-card-meta">
                ID {repo.id} · 更新于 {formatTime(repo.updatedAt)} · {repo.enabled ? "扫描开启" : "扫描暂停"}
              </span>
            </div>
            <div className="repo-card-actions">
              <label className="toggle">
                <span>自动审查</span>
                <button className={`switch ${repo.autoReview ? "on" : ""}`} disabled={busyId === repo.id} onClick={() => toggle(repo, "autoReview")} aria-pressed={repo.autoReview} />
              </label>
              <label className="toggle">
                <span>启用</span>
                <button className={`switch ${repo.enabled ? "on" : ""}`} disabled={busyId === repo.id} onClick={() => toggle(repo, "enabled")} aria-pressed={repo.enabled} />
              </label>
              <button className="mini-btn" disabled={busyId === repo.id} onClick={() => scan(repo)}>
                {busyId === repo.id ? "扫描中…" : "立即扫描"}
              </button>
            </div>
          </div>
        ))}
      </div>

      <div className="panel settings">
        <div className="panel-head">
          <div>
            <div className="panel-kicker">Connection</div>
            <div className="panel-title">控制台连接</div>
          </div>
        </div>
        <div className="panel-body">
          <div className="settings-row">
            <div className="field">
              <label>API Base</label>
              <input className="input wide" placeholder="留空表示同源" value={base} onChange={(e) => setBase(e.target.value)} />
            </div>
            <div className="field">
              <label>API Key</label>
              <input className="input wide" type="password" value={key} onChange={(e) => setKey(e.target.value)} />
            </div>
            <button className="btn" onClick={() => { setConnection(base, key); window.location.reload(); }}>保存并刷新</button>
          </div>
          <p className="hint" style={{ marginTop: 10 }}>
            本地默认使用 <span className="mono">dev-mergeguard-key</span>。部署时通过环境变量 <span className="mono">MERGEGUARD_API_KEY</span> 注入，不要写进前端构建产物。
          </p>
        </div>
      </div>
    </>
  );
}
