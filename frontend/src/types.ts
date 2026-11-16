export type Verdict = "SHIP" | "FIX" | "BLOCK";
export type Severity = "BLOCKER" | "MAJOR" | "MINOR" | "INFO";

export interface ReviewSummary {
  id: number;
  repo: string;
  prNumber: number;
  title: string;
  verdict: Verdict;
  score: number;
  findings: number;
  durationMs: number;
  tokens: number;
  published: boolean;
  createdAt: string;
  prUrl: string | null;
}

export interface Finding {
  id: number;
  file: string;
  line: number | null;
  severity: Severity;
  category: string;
  title: string;
  evidence: string;
  suggestion: string;
  source: "RULE" | "LLM";
  confidence: number;
  ruleId: string | null;
}

export interface ReviewDetail extends Omit<ReviewSummary, "findings"> {
  author: string | null;
  baseRef: string | null;
  headSha: string;
  summary: string;
  llmModel: string | null;
  inputTokens: number;
  outputTokens: number;
  publishError: string | null;
  findings: Finding[];
}

export interface VerdictCount { verdict: Verdict; count: number }
export interface DailyCount { date: string; total: number; blocked: number }

export interface Overview {
  reviews: number;
  blocked: number;
  blockRate: number;
  avgDurationMs: number;
  totalTokens: number;
  llmReviews: number;
  verdicts: VerdictCount[];
  daily: DailyCount[];
  recent: ReviewSummary[];
}

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
  pages: number;
}

export interface RepoDto {
  id: number;
  owner: string;
  name: string;
  fullName: string;
  enabled: boolean;
  autoReview: boolean;
  updatedAt: string;
}

export interface ScanResult {
  queued: number;
  openPulls: number;
}
