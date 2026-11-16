import type { JobDto, JobStats, Overview, Page, Quality, RepoDto, ReviewDetail, ReviewDiff, ReviewSummary, ScanResult } from "./types";

const API_BASE = (localStorage.getItem("mg_api_base") || import.meta.env.VITE_API_BASE || "").replace(/\/$/, "");
const API_KEY = (localStorage.getItem("mg_api_key") || import.meta.env.VITE_API_KEY || "dev-mergeguard-key");

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      "X-API-Key": API_KEY,
      ...(init?.headers || {})
    }
  });
  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      const body = await response.json();
      message = body.error || body.message || message;
    } catch {
      // ignore
    }
    throw new Error(message);
  }
  return response.json() as Promise<T>;
}

export const api = {
  overview: () => request<Overview>("/api/overview"),
  reviews: (params: { page?: number; size?: number; verdict?: string; repo?: string }) => {
    const search = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== "" && value !== "ALL") search.set(key, String(value));
    });
    return request<Page<ReviewSummary>>(`/api/reviews?${search.toString()}`);
  },
  review: (id: number) => request<ReviewDetail>(`/api/reviews/${id}`),
  reviewDiff: (id: number) => request<ReviewDiff>(`/api/reviews/${id}/diff`),
  feedback: (id: number, status: string, note?: string) =>
    request<void>(`/api/findings/${id}/feedback`, { method: "POST", body: JSON.stringify({ status, note }) }),
  quality: () => request<Quality>("/api/quality"),
  jobs: (limit = 30) => request<JobDto[]>(`/api/jobs?limit=${limit}`),
  jobStats: () => request<JobStats>("/api/jobs/stats"),
  retryJob: (id: number) => request<JobDto>(`/api/jobs/${id}/retry`, { method: "POST" }),
  trigger: (payload: { owner: string; name: string; prNumber: number; publish: boolean }) =>
    request<ReviewDetail>("/api/reviews", { method: "POST", body: JSON.stringify(payload) }),
  repos: () => request<RepoDto[]>("/api/repos"),
  addRepo: (payload: { owner: string; name: string; autoReview: boolean }) =>
    request<RepoDto>("/api/repos", { method: "POST", body: JSON.stringify(payload) }),
  updateRepo: (id: number, payload: { enabled?: boolean; autoReview?: boolean }) =>
    request<RepoDto>(`/api/repos/${id}`, { method: "PATCH", body: JSON.stringify(payload) }),
  scanRepo: (id: number) => request<ScanResult>(`/api/repos/${id}/scan`, { method: "POST" })
};

export function getConnection() {
  return {
    base: localStorage.getItem("mg_api_base") || "",
    key: localStorage.getItem("mg_api_key") || "dev-mergeguard-key"
  };
}

export function setConnection(base: string, key: string) {
  localStorage.setItem("mg_api_base", base.replace(/\/$/, ""));
  localStorage.setItem("mg_api_key", key);
}
