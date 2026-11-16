import type { Severity, Verdict } from "../types";

export function VerdictBadge({ verdict }: { verdict: Verdict }) {
  const tone = verdict.toLowerCase();
  return <span className={`badge ${tone}`}>{verdict}</span>;
}

export function SeverityBadge({ severity }: { severity: Severity }) {
  return <span className={`badge ${severity.toLowerCase()}`}>{severity}</span>;
}

export function SourceBadge({ source }: { source: "RULE" | "LLM" }) {
  return <span className="badge src">{source === "RULE" ? "规则" : "模型"}</span>;
}
