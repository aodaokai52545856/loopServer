export type CountByState = {
  state: string
  count: number
}

export type DashboardSummary = {
  tasksByState: CountByState[]
  nodesByState: CountByState[]
}

export type TaskListItem = {
  id: string
  issueRef: string
  projectKey: string
  moduleKey?: string | null
  state: string
  nodeId?: string | null
  executor?: string | null
  waitingDuration?: string | null
  repairDuration?: string | null
  updatedAt: string
  title?: string
  issueIid?: number
  issueUrl?: string | null
  createdAt?: string
}

export type Page<T> = {
  items: T[]
  nextCursor: string | null
}

export type TaskAttachment = {
  name: string
  url: string
}

export type TaskCommand = {
  program: string
  args: string[]
  display: string
  exitCode: number | null
  log: string
}

export type SchedulerCandidate = {
  nodeId: string
  score: number
  rejectionReason: string | null
}

export type TaskEvent = {
  id: string
  type: string
  message: string
  at?: string
  terminal?: boolean
}

export type TaskDetail = {
  id: string
  issueRef: string
  issue: {
    title: string
    url: string
    attachments: TaskAttachment[]
  }
  completeness: {
    decision: string
    missingFields: string[]
  }
  profile: {
    revision: number
    baseSha: string
  }
  scheduling: {
    reason: string
    chosenScore: number
    candidates: SchedulerCandidate[]
  }
  attempt: {
    attemptId: string
    pipelineId: string
    jobId: string
    executor: string
  }
  commands: TaskCommand[]
  patch: {
    summary: string
    changedFiles: string[]
  }
  artifacts: Array<{ name: string; sha256: string }>
  mergeRequest: {
    url: string
    branch: string
    commitSha: string
  }
  stateTransitions: Array<{ from: string; to: string; at: string }>
  auditOperations: Array<{ action: string; actor: string; at: string }>
  events?: TaskEvent[]
  state?: string
  projectKey?: string
  title?: string
  description?: string
  defectState?: string
  missingFieldsJson?: string | null
  profileRevision?: number
  baseSha?: string
  createdAt?: string
  updatedAt?: string
}

export type TaskListQuery = {
  projectKey?: string
  state?: string
  nodeId?: string
  issue?: string
  from?: string
  to?: string
  cursor?: string
  limit?: number
  module?: string
  executor?: string
}
