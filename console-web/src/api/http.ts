import type { DashboardSummary, Page, TaskDetail, TaskListItem, TaskListQuery } from './types'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      ...(init?.headers ?? {}),
    },
    ...init,
  })
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${path}`)
  }
  return (await response.json()) as T
}

function toQuery(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') {
      continue
    }
    search.set(key, String(value))
  }
  const qs = search.toString()
  return qs ? `?${qs}` : ''
}

function normalizeTaskListItem(raw: Record<string, unknown>): TaskListItem {
  const projectKey = String(raw.projectKey ?? '')
  const issueIid = raw.issueIid as number | undefined
  const issueUrl = (raw.issueUrl as string | null | undefined) ?? null
  const issueRef =
    (raw.issueRef as string | undefined) ??
    (issueUrl ? issueUrl.replace(/^https?:\/\/[^/]+\//, '').replace(/\/-\/issues\//, '#') : undefined) ??
    (issueIid != null ? `${projectKey}#${issueIid}` : String(raw.id ?? ''))

  return {
    id: String(raw.id),
    issueRef,
    projectKey,
    moduleKey: (raw.moduleKey as string | null | undefined) ?? null,
    state: String(raw.state ?? ''),
    nodeId: (raw.nodeId as string | null | undefined) ?? null,
    executor: (raw.executor as string | null | undefined) ?? null,
    waitingDuration: (raw.waitingDuration as string | null | undefined) ?? null,
    repairDuration: (raw.repairDuration as string | null | undefined) ?? null,
    updatedAt: String(raw.updatedAt ?? ''),
    title: raw.title as string | undefined,
    issueIid,
    issueUrl,
    createdAt: raw.createdAt as string | undefined,
  }
}

function parseJsonArray(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.map(String)
  }
  if (typeof value !== 'string' || !value.trim()) {
    return []
  }
  try {
    const parsed = JSON.parse(value) as unknown
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}

function normalizeTaskDetail(raw: Record<string, unknown>): TaskDetail {
  const projectKey = String(raw.projectKey ?? '')
  const issueIid = raw.issueIid as number | undefined
  const issueUrl = String(raw.issueUrl ?? '')
  const issueRef =
    (raw.issueRef as string | undefined) ??
    (issueUrl ? issueUrl.replace(/^https?:\/\/[^/]+\//, '').replace(/\/-\/issues\//, '#') : undefined) ??
    (issueIid != null ? `${projectKey}#${issueIid}` : String(raw.id ?? ''))

  const nestedIssue = (raw.issue as TaskDetail['issue'] | undefined) ?? {
    title: String(raw.title ?? ''),
    url: issueUrl,
    attachments: Array.isArray(raw.attachments) ? (raw.attachments as TaskDetail['issue']['attachments']) : [],
  }

  const missingFields = parseJsonArray(raw.missingFieldsJson ?? raw.missingFields)
  const completeness =
    (raw.completeness as TaskDetail['completeness'] | undefined) ??
    ({
      decision:
        typeof raw.completenessDecision === 'string'
          ? raw.completenessDecision
          : missingFields.length > 0
            ? '缺失字段判断'
            : '',
      missingFields,
    } satisfies TaskDetail['completeness'])

  const profile = (raw.profile as TaskDetail['profile'] | undefined) ?? {
    revision: Number(raw.profileRevision ?? 0),
    baseSha: String(raw.baseSha ?? ''),
  }

  const scheduling = (raw.scheduling as TaskDetail['scheduling'] | undefined) ?? {
    reason: String(raw.schedulingReason ?? ''),
    chosenScore: Number(raw.chosenScore ?? 0),
    candidates: Array.isArray(raw.schedulerCandidates)
      ? (raw.schedulerCandidates as TaskDetail['scheduling']['candidates'])
      : [],
  }

  const attempt = (raw.attempt as TaskDetail['attempt'] | undefined) ?? {
    attemptId: String(raw.attemptId ?? ''),
    pipelineId: String(raw.pipelineId ?? ''),
    jobId: String(raw.jobId ?? ''),
    executor: String(raw.executor ?? ''),
  }

  const mergeRequest = (raw.mergeRequest as TaskDetail['mergeRequest'] | undefined) ?? {
    url: String((raw.mergeRequestUrl as string | undefined) ?? ''),
    branch: String((raw.branch as string | undefined) ?? ''),
    commitSha: String((raw.commitSha as string | undefined) ?? ''),
  }

  return {
    id: String(raw.id),
    issueRef,
    issue: nestedIssue,
    completeness,
    profile,
    scheduling,
    attempt,
    commands: Array.isArray(raw.commands) ? (raw.commands as TaskDetail['commands']) : [],
    patch: (raw.patch as TaskDetail['patch'] | undefined) ?? {
      summary: String(raw.patchSummary ?? ''),
      changedFiles: Array.isArray(raw.changedFiles) ? raw.changedFiles.map(String) : [],
    },
    artifacts: Array.isArray(raw.artifacts) ? (raw.artifacts as TaskDetail['artifacts']) : [],
    mergeRequest,
    stateTransitions: Array.isArray(raw.stateTransitions)
      ? (raw.stateTransitions as TaskDetail['stateTransitions'])
      : [],
    auditOperations: Array.isArray(raw.auditOperations)
      ? (raw.auditOperations as TaskDetail['auditOperations'])
      : [],
    events: Array.isArray(raw.events) ? (raw.events as TaskDetail['events']) : [],
    state: raw.state as string | undefined,
    projectKey,
    title: raw.title as string | undefined,
    description: raw.description as string | undefined,
    defectState: raw.defectState as string | undefined,
    missingFieldsJson: raw.missingFieldsJson as string | null | undefined,
    profileRevision: raw.profileRevision as number | undefined,
    baseSha: raw.baseSha as string | undefined,
    createdAt: raw.createdAt as string | undefined,
    updatedAt: raw.updatedAt as string | undefined,
  }
}

export async function fetchDashboard(): Promise<DashboardSummary> {
  return request<DashboardSummary>('/api/dashboard')
}

export async function fetchTasks(query: TaskListQuery = {}): Promise<Page<TaskListItem>> {
  const page = await request<Page<Record<string, unknown>>>(
    `/api/tasks${toQuery({
      projectKey: query.projectKey,
      state: query.state,
      nodeId: query.nodeId,
      issue: query.issue,
      from: query.from,
      to: query.to,
      cursor: query.cursor,
      limit: query.limit,
    })}`,
  )
  return {
    items: page.items.map(normalizeTaskListItem),
    nextCursor: page.nextCursor,
  }
}

export async function fetchTaskDetail(taskId: string): Promise<TaskDetail> {
  const raw = await request<Record<string, unknown>>(`/api/tasks/${encodeURIComponent(taskId)}`)
  return normalizeTaskDetail(raw)
}

export function taskEventsUrl(taskId: string): string {
  return `/api/tasks/${encodeURIComponent(taskId)}/events`
}
