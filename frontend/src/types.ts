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
  status: "OPEN" | "ACCEPTED" | "DISMISSED" | "FIXED";
  feedbackNote: string | null;
  feedbackAt: string | null;
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
  diffMs: number;
  ruleMs: number;
  llmMs: number;
  publishMs: number;
  llmChunks: number;
  llmCoverage: number;
  injectionNeutralized: number;
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
  ignoredPaths: string;
  disabledRules: string;
  minPublishSeverity: string;
  maxInlineComments: number;
  updatedAt: string;
}

export interface ScanResult {
  queued: number;
  openPulls: number;
}

export interface DiffFile {
  file: string;
  additions: number;
  deletions: number;
  patch: string | null;
}

export interface ReviewDiff {
  reviewId: number;
  headSha: string;
  files: DiffFile[];
}

export interface JobDto {
  id: number;
  owner: string;
  name: string;
  prNumber: number;
  headSha: string;
  publish: boolean;
  status: "QUEUED" | "RUNNING" | "DONE" | "FAILED";
  attempts: number;
  maxAttempts: number;
  nextAttemptAt: string;
  lastError: string | null;
  reviewId: number | null;
  createdAt: string;
  finishedAt: string | null;
}

export interface JobStats {
  queued: number;
  running: number;
  done: number;
  failed: number;
}

export interface QualityRule {
  ruleId: string;
  total: number;
  accepted: number;
  dismissed: number;
  fixed: number;
  open: number;
  acceptanceRate: number;
  avgConfidence: number;
}

export interface Quality {
  totalFindings: number;
  judged: number;
  accepted: number;
  dismissed: number;
  fixed: number;
  acceptanceRate: number;
  rules: QualityRule[];
}
