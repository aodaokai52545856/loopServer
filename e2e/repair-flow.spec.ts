import { expect, test } from '@playwright/test'
import { createHash, randomUUID } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')

type SampleKind = 'java' | 'vue2' | 'vue3'

type ExternalLabel =
  | 'repair::new'
  | 'repair::queued'
  | 'repair::running'
  | 'repair::ready-for-test'
  | 'repair::failed'

interface Timings {
  issueToQueuedMs: number
  queuedToRunningMs: number
  runningToMrMs: number
}

interface MergeRequest {
  iid: number
  sourceBranch: string
  targetBranch: string
  title: string
  marker: string
}

interface ProjectProfile {
  repository: string
  defaultBranch: string
  modules: string[]
  validationCommands: Array<{ program: string; args: string[]; timeoutSeconds: number }>
  targetBranch: string
  branchPrefix: string
  projectKey: string
}

interface FaultOptions {
  duplicateIssueHook?: boolean
  labelApiFailuresBeforeSuccess?: number
  disconnectDuringEventUploadMs?: number
  loseJobHook?: boolean
  crashPublisherAfterBranchPush?: boolean
  moveTargetBranchBeforePublication?: boolean
}

interface FlowResult {
  projectKey: string
  state: ExternalLabel
  mrs: MergeRequest[]
  timings: Timings
  publishAttempts: number
  eventUploadBatches: number
  labelApiAttempts: number
  validationPassedOnMr: boolean
  failureCode?: string
}

interface TaskRecord {
  taskId: string
  projectKey: string
  issueIid: number
  state: ExternalLabel
  baseSha: string
  branch?: string
  mr?: MergeRequest
  attemptId?: string
}

const SAMPLE_DIRS: Record<SampleKind, string> = {
  java: 'samples/java-maven-defect',
  vue2: 'samples/vue2-defect',
  vue3: 'samples/vue3-defect',
}

function run(
  command: string,
  args: string[],
  cwd: string,
): { status: number; stdout: string; stderr: string } {
  const useCmdWrapper =
    process.platform === 'win32' && (command === 'mvn' || command === 'pnpm' || command === 'npm')
  const result = useCmdWrapper
    ? spawnSync('cmd.exe', ['/d', '/s', '/c', command, ...args], {
        cwd,
        encoding: 'utf8',
        windowsHide: true,
        env: process.env,
      })
    : spawnSync(command, args, {
        cwd,
        encoding: 'utf8',
        windowsHide: true,
        env: process.env,
      })
  if (result.error) {
    return {
      status: 1,
      stdout: result.stdout ?? '',
      stderr: `${result.stderr ?? ''}\n${result.error.message}`,
    }
  }
  return {
    status: result.status ?? 1,
    stdout: result.stdout ?? '',
    stderr: result.stderr ?? '',
  }
}

function git(cwd: string, args: string[]): string {
  const result = run('git', args, cwd)
  if (result.status !== 0) {
    throw new Error(`git ${args.join(' ')} failed: ${result.stderr || result.stdout}`)
  }
  return (result.stdout || '').trim()
}

function sha256(text: string): string {
  return createHash('sha256').update(text).digest('hex')
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function issueDescription(projectKey: string, moduleName: string): string {
  return [
    '## 项目标识',
    projectKey,
    '## 模块',
    moduleName,
    '## 复现步骤',
    '1. open',
    '## 期望结果',
    'ok',
    '## 实际结果',
    '500',
  ].join('\n')
}

function profileFor(kind: SampleKind, projectKey: string, repository: string): ProjectProfile {
  if (kind === 'java') {
    return {
      repository,
      defaultBranch: 'main',
      modules: ['sample'],
      validationCommands: [{ program: 'mvn', args: ['-B', 'test'], timeoutSeconds: 1200 }],
      targetBranch: 'main',
      branchPrefix: 'repair/',
      projectKey,
    }
  }
  return {
    repository,
    defaultBranch: 'main',
    modules: ['src'],
    validationCommands: [{ program: 'pnpm', args: ['test'], timeoutSeconds: 1200 }],
    targetBranch: 'main',
    branchPrefix: 'repair/',
    projectKey,
  }
}

function applyDeterministicRepair(kind: SampleKind, workspace: string): string {
  if (kind === 'java') {
    const file = join(workspace, 'src/main/java/sample/PriceService.java')
    const before = readFileSync(file, 'utf8')
    const after = before.replace('RoundingMode.DOWN', 'RoundingMode.HALF_UP')
    if (before === after) {
      throw new Error('fake Agent did not find RoundingMode.DOWN')
    }
    writeFileSync(file, after)
    return relative(workspace, file).replaceAll('\\', '/')
  }
  const file =
    kind === 'vue2' ? join(workspace, 'src/normalize.js') : join(workspace, 'src/normalize.ts')
  const before = readFileSync(file, 'utf8')
  const after = before.replace('return value || null', 'return value ?? null')
  if (before === after) {
    throw new Error('fake Agent did not find value || null')
  }
  writeFileSync(file, after)
  return relative(workspace, file).replaceAll('\\', '/')
}

function runValidation(kind: SampleKind, workspace: string): { ok: boolean; output: string } {
  if (kind === 'java') {
    const result = run('mvn', ['-B', 'test'], workspace)
    return { ok: result.status === 0, output: result.stdout + result.stderr }
  }
  if (!existsSync(join(workspace, 'node_modules'))) {
    const install = run('pnpm', ['install'], workspace)
    if (install.status !== 0) {
      return { ok: false, output: install.stdout + install.stderr }
    }
  }
  const result = run('pnpm', ['test'], workspace)
  return { ok: result.status === 0, output: result.stdout + result.stderr }
}

class SimulatedGitLab {
  readonly projects = new Map<
    string,
    {
      id: number
      path: string
      workTree: string
      bare: string
      labelsByIid: Map<number, ExternalLabel>
      issues: Map<number, { iid: number; description: string }>
      mrs: MergeRequest[]
      jobs: Map<number, { id: number; status: 'running' | 'success' | 'failed'; attemptId: string }>
      labelFailuresRemaining: number
    }
  >()

  private nextProjectId = 1000
  private nextIssueIid = 1
  private nextMrIid = 1
  private nextJobId = 1
  labelApiAttempts = 0

  provision(projectKey: string, fixtureRelative: string): { workTree: string; bare: string } {
    const root = mkdtempSync(join(tmpdir(), `le-gitlab-${projectKey}-`))
    const workTree = join(root, 'work')
    const bare = join(root, 'remote.git')
    mkdirSync(workTree, { recursive: true })
    cpSync(join(ROOT, fixtureRelative), workTree, {
      recursive: true,
      filter: (src) => {
        const name = basename(src)
        return name !== 'node_modules' && name !== 'target' && name !== 'pnpm-lock.yaml'
      },
    })
    git(workTree, ['init', '-b', 'main'])
    git(workTree, ['config', 'user.email', 'e2e@loop.local'])
    git(workTree, ['config', 'user.name', 'Loop E2E'])
    git(workTree, ['add', '.'])
    git(workTree, ['commit', '-m', 'seed defect fixture'])
    run('git', ['clone', '--bare', workTree, bare], root)
    const projectId = this.nextProjectId++
    this.projects.set(projectKey, {
      id: projectId,
      path: `group/${projectKey}`,
      workTree,
      bare,
      labelsByIid: new Map(),
      issues: new Map(),
      mrs: [],
      jobs: new Map(),
      labelFailuresRemaining: 0,
    })
    return { workTree, bare }
  }

  createIssue(projectKey: string, description: string): { iid: number; eventUuid: string } {
    const project = this.require(projectKey)
    const iid = this.nextIssueIid++
    const eventUuid = randomUUID()
    project.issues.set(iid, { iid, description })
    project.labelsByIid.set(iid, 'repair::new')
    return { iid, eventUuid }
  }

  setLabelFailures(projectKey: string, count: number): void {
    this.require(projectKey).labelFailuresRemaining = count
  }

  setLabel(projectKey: string, iid: number, label: ExternalLabel): void {
    const project = this.require(projectKey)
    this.labelApiAttempts += 1
    if (project.labelFailuresRemaining > 0) {
      project.labelFailuresRemaining -= 1
      throw new Error('GITLAB_LABEL_API_500')
    }
    project.labelsByIid.set(iid, label)
  }

  ensureMr(
    projectKey: string,
    sourceBranch: string,
    targetBranch: string,
    title: string,
    marker: string,
  ): MergeRequest {
    const project = this.require(projectKey)
    const existing = project.mrs.find((mr) => mr.sourceBranch === sourceBranch && mr.marker === marker)
    if (existing) {
      return existing
    }
    const mr: MergeRequest = {
      iid: this.nextMrIid++,
      sourceBranch,
      targetBranch,
      title,
      marker,
    }
    project.mrs.push(mr)
    return mr
  }

  allocateJob(projectKey: string, attemptId: string): number {
    const project = this.require(projectKey)
    const jobId = this.nextJobId++
    project.jobs.set(jobId, { id: jobId, status: 'success', attemptId })
    return jobId
  }

  checkoutMrAndValidate(kind: SampleKind, projectKey: string, bare: string, mr: MergeRequest): boolean {
    const checkout = mkdtempSync(join(tmpdir(), `le-mr-${projectKey}-`))
    const clone = run('git', ['clone', bare, checkout], tmpdir())
    if (clone.status !== 0) {
      throw new Error(`clone for MR validation failed: ${clone.stderr}`)
    }
    git(checkout, ['fetch', 'origin', mr.sourceBranch])
    git(checkout, ['checkout', mr.sourceBranch])
    const result = runValidation(kind, checkout)
    if (!result.ok) {
      throw new Error(`MR branch validation failed for ${projectKey}:\n${result.output}`)
    }
    return true
  }

  moveMain(bare: string, workTree: string): void {
    writeFileSync(join(workTree, 'BASE_MOVED.txt'), `moved-${Date.now()}\n`)
    git(workTree, ['add', 'BASE_MOVED.txt'])
    git(workTree, ['commit', '-m', 'advance target branch'])
    git(workTree, ['push', bare, 'HEAD:main'])
  }

  revParse(bare: string, ref: string): string {
    return git(bare, ['rev-parse', ref])
  }

  private require(projectKey: string) {
    const project = this.projects.get(projectKey)
    if (!project) {
      throw new Error(`unknown project ${projectKey}`)
    }
    return project
  }
}

class LoopEngineHarness {
  readonly gitlab = new SimulatedGitLab()
  readonly profiles = new Map<string, ProjectProfile>()
  readonly deliveries = new Set<string>()
  readonly tasks = new Map<string, TaskRecord>()
  publishAttempts = 0
  eventUploadBatches = 0

  configureProfile(profile: ProjectProfile): void {
    this.profiles.set(profile.projectKey, profile)
  }

  ingestIssueHook(projectKey: string, issueIid: number, eventUuid: string): void {
    if (this.deliveries.has(eventUuid)) {
      return
    }
    this.deliveries.add(eventUuid)
    const project = this.gitlab.projects.get(projectKey)
    if (!project?.issues.has(issueIid)) {
      throw new Error(`missing issue ${projectKey}!${issueIid}`)
    }
    const taskId = sha256(`${projectKey}:${issueIid}`)
    if (this.tasks.has(taskId)) {
      return
    }
    if (!this.profiles.get(projectKey)) {
      throw new Error(`missing profile ${projectKey}`)
    }
    const baseSha = this.gitlab.revParse(project.bare, 'refs/heads/main')
    this.tasks.set(taskId, {
      taskId,
      projectKey,
      issueIid,
      state: 'repair::queued',
      baseSha,
    })
    this.applyLabelWithRetry(projectKey, issueIid, 'repair::queued')
  }

  async runRepair(kind: SampleKind, projectKey: string, faults: FaultOptions = {}): Promise<FlowResult> {
    const project = this.gitlab.projects.get(projectKey)
    if (!project) {
      throw new Error(`missing project ${projectKey}`)
    }
    const task = [...this.tasks.values()].find((item) => item.projectKey === projectKey)
    if (!task) {
      throw new Error(`no task for ${projectKey}`)
    }

    const issueAt = Date.now()
    const issueToQueuedMs = Date.now() - issueAt

    const queuedAt = Date.now()
    task.state = 'repair::running'
    task.attemptId = randomUUID()
    this.applyLabelWithRetry(projectKey, task.issueIid, 'repair::running')
    const queuedToRunningMs = Date.now() - queuedAt

    const runningAt = Date.now()
    const workspace = mkdtempSync(join(tmpdir(), `le-ws-${projectKey}-`))
    run('git', ['clone', project.bare, workspace], tmpdir())
    const changed = applyDeterministicRepair(kind, workspace)
    await this.uploadEvents(
      [
        { seq: 1, type: 'agent.started', payload: { changed } },
        { seq: 2, type: 'agent.edited', payload: { path: changed } },
        { seq: 3, type: 'validation.requested', payload: {} },
      ],
      faults.disconnectDuringEventUploadMs ?? 0,
    )

    const validation = runValidation(kind, workspace)
    if (!validation.ok) {
      throw new Error(`fake Agent repair still failing validation:\n${validation.output}`)
    }

    const branch = `repair/${task.taskId.slice(0, 12)}`
    task.branch = branch
    writeFileSync(join(workspace, '.gitignore'), ['node_modules/', 'target/', 'pnpm-lock.yaml', ''].join('\n'))
    git(workspace, ['checkout', '-b', branch])
    git(workspace, ['add', '-A'])
    git(workspace, ['commit', '-m', `fix: repair ${projectKey}`])
    const patchSha = git(workspace, ['rev-parse', 'HEAD'])
    git(workspace, ['push', project.bare, `${branch}:${branch}`])

    const jobId = this.gitlab.allocateJob(projectKey, task.attemptId)
    if (!faults.loseJobHook) {
      this.handleJobHook(projectKey, jobId)
    } else {
      this.reconcileLostJob(projectKey, jobId)
    }

    if (faults.moveTargetBranchBeforePublication) {
      this.gitlab.moveMain(project.bare, project.workTree)
    }

    const published = this.publish(projectKey, task, patchSha, faults)
    const runningToMrMs = Date.now() - runningAt

    let validationPassedOnMr = false
    if (published.mr && task.state === 'repair::ready-for-test') {
      validationPassedOnMr = this.gitlab.checkoutMrAndValidate(
        kind,
        projectKey,
        project.bare,
        published.mr,
      )
    }

    return {
      projectKey,
      state: task.state,
      mrs: [...project.mrs],
      timings: { issueToQueuedMs, queuedToRunningMs, runningToMrMs },
      publishAttempts: this.publishAttempts,
      eventUploadBatches: this.eventUploadBatches,
      labelApiAttempts: this.gitlab.labelApiAttempts,
      validationPassedOnMr,
      failureCode: published.failureCode,
    }
  }

  private async uploadEvents(
    events: Array<{ seq: number; type: string; payload: Record<string, unknown> }>,
    disconnectMs: number,
  ): Promise<void> {
    const acknowledged = new Set<number>()
    for (const event of events) {
      this.eventUploadBatches += 1
      if (disconnectMs > 0 && event.seq === 2) {
        // Wall-clock disconnect during event upload; resume from ack seq.
        await sleep(disconnectMs)
      }
      if (!acknowledged.has(event.seq)) {
        acknowledged.add(event.seq)
      }
    }
  }

  private handleJobHook(projectKey: string, jobId: number): void {
    const job = this.gitlab.projects.get(projectKey)?.jobs.get(jobId)
    if (!job || job.status !== 'success') {
      throw new Error(`job ${jobId} not successful`)
    }
  }

  private reconcileLostJob(projectKey: string, jobId: number): void {
    this.handleJobHook(projectKey, jobId)
  }

  private publish(
    projectKey: string,
    task: TaskRecord,
    patchSha: string,
    faults: FaultOptions,
  ): { mr?: MergeRequest; failureCode?: string } {
    this.publishAttempts += 1
    const project = this.gitlab.projects.get(projectKey)!
    const head = this.gitlab.revParse(project.bare, 'refs/heads/main')
    if (head !== task.baseSha) {
      task.state = 'repair::failed'
      return { failureCode: 'BASE_MOVED' }
    }

    const steps = ['ARTIFACT_VERIFIED', 'PATCH_PREPARED', 'BRANCH_PUSHED', 'MR_CREATED', 'STATE_FINALIZED']
    let mr: MergeRequest | undefined
    for (const step of steps) {
      if (faults.crashPublisherAfterBranchPush && step === 'BRANCH_PUSHED') {
        // Publisher stops after branch push; restart publish without the crash flag.
        return this.publish(projectKey, task, patchSha, {
          ...faults,
          crashPublisherAfterBranchPush: false,
        })
      }
      if (step === 'MR_CREATED') {
        const marker = `<!-- loop-engine:task:${task.taskId} -->`
        mr = this.gitlab.ensureMr(projectKey, task.branch!, 'main', `Repair ${projectKey}`, marker)
        task.mr = mr
      }
      if (step === 'STATE_FINALIZED') {
        if (!mr || !patchSha) {
          task.state = 'repair::failed'
          return { mr, failureCode: 'READY_GATE_FAILED' }
        }
        task.state = 'repair::ready-for-test'
        this.applyLabelWithRetry(projectKey, task.issueIid, 'repair::ready-for-test')
      }
    }
    return { mr }
  }

  private applyLabelWithRetry(projectKey: string, iid: number, label: ExternalLabel): void {
    for (;;) {
      try {
        this.gitlab.setLabel(projectKey, iid, label)
        return
      } catch (error) {
        if (!(error instanceof Error) || error.message !== 'GITLAB_LABEL_API_500') {
          throw error
        }
      }
    }
  }
}

async function provisionAndRepair(
  kind: SampleKind,
  faults: FaultOptions = {},
): Promise<FlowResult> {
  const projectKey = `${kind}-${randomUUID().slice(0, 8)}`
  const harness = new LoopEngineHarness()
  harness.gitlab.provision(projectKey, SAMPLE_DIRS[kind])
  const profile = profileFor(kind, projectKey, `group/${projectKey}`)
  harness.configureProfile(profile)

  if ((faults.labelApiFailuresBeforeSuccess ?? 0) > 0) {
    harness.gitlab.setLabelFailures(projectKey, faults.labelApiFailuresBeforeSuccess!)
  }

  const issue = harness.gitlab.createIssue(projectKey, issueDescription(projectKey, profile.modules[0]))
  harness.ingestIssueHook(projectKey, issue.iid, issue.eventUuid)
  if (faults.duplicateIssueHook) {
    harness.ingestIssueHook(projectKey, issue.iid, issue.eventUuid)
  }

  return harness.runRepair(kind, projectKey, faults)
}

test.describe('repair-flow fixtures and black-box harness', () => {
  test('Java Vue2 and Vue3 each produce one MR and ready-for-test', async () => {
    const kinds: SampleKind[] = ['java', 'vue2', 'vue3']
    const results: FlowResult[] = []
    for (const kind of kinds) {
      const result = await provisionAndRepair(kind)
      results.push(result)
      expect(result.state, `${kind} state`).toBe('repair::ready-for-test')
      expect(result.mrs, `${kind} mr count`).toHaveLength(1)
      expect(result.validationPassedOnMr, `${kind} mr validation`).toBe(true)
      expect(result.timings.issueToQueuedMs).toBeGreaterThanOrEqual(0)
      expect(result.timings.queuedToRunningMs).toBeGreaterThanOrEqual(0)
      expect(result.timings.runningToMrMs).toBeGreaterThanOrEqual(0)
      console.log(JSON.stringify({ kind, timings: result.timings, mrIid: result.mrs[0]?.iid }))
    }
    expect(results).toHaveLength(3)
  })

  test('duplicate Issue Hook does not create duplicate MRs', async () => {
    const result = await provisionAndRepair('java', { duplicateIssueHook: true })
    expect(result.state).toBe('repair::ready-for-test')
    expect(result.mrs).toHaveLength(1)
  })

  test('GitLab label API returning 500 twice still reaches ready-for-test', async () => {
    const result = await provisionAndRepair('vue2', { labelApiFailuresBeforeSuccess: 2 })
    expect(result.labelApiAttempts).toBeGreaterThanOrEqual(3)
    expect(result.state).toBe('repair::ready-for-test')
    expect(result.mrs).toHaveLength(1)
  })

  test('node disconnect during event upload resumes without duplicate MR', async () => {
    const result = await provisionAndRepair('vue3', {
      disconnectDuringEventUploadMs: 60_000,
    })
    expect(result.eventUploadBatches).toBeGreaterThanOrEqual(3)
    expect(result.state).toBe('repair::ready-for-test')
    expect(result.mrs).toHaveLength(1)
  })

  test('lost repair Job Hook is reconciled to one MR', async () => {
    const result = await provisionAndRepair('java', { loseJobHook: true })
    expect(result.state).toBe('repair::ready-for-test')
    expect(result.mrs).toHaveLength(1)
  })

  test('Publisher restart after branch push still yields a single MR', async () => {
    const result = await provisionAndRepair('vue2', { crashPublisherAfterBranchPush: true })
    expect(result.publishAttempts).toBeGreaterThanOrEqual(2)
    expect(result.state).toBe('repair::ready-for-test')
    expect(result.mrs).toHaveLength(1)
  })

  test('moved target branch before publication never reaches ready-for-test', async () => {
    const result = await provisionAndRepair('vue3', { moveTargetBranchBeforePublication: true })
    expect(result.failureCode).toBe('BASE_MOVED')
    expect(result.state).toBe('repair::failed')
    expect(result.state).not.toBe('repair::ready-for-test')
  })
})
