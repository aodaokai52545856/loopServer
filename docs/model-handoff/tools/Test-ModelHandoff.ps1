#requires -Version 7.0

[CmdletBinding()]
param([switch]$SkipIntegration)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$toolsDir = Split-Path -Parent $PSCommandPath
$handoffRoot = Split-Path -Parent $toolsDir
$outputRoot = Split-Path -Parent $handoffRoot
$repoResult = @(& git -C $handoffRoot rev-parse --show-toplevel 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'model-handoff is not inside a Git repository' }
$repoRoot = (Resolve-Path -LiteralPath $repoResult[0]).Path

$requiredDocs = @(
    '00-模型执行总则.md', '01-不可变架构决策.md', '02-任务索引.md',
    '03-启动提示词模板.md', '04-完成报告模板.md', '05-任务复核提示词.md', '06-人工操作手册.md'
)
foreach ($name in $requiredDocs) {
    if (-not (Test-Path -LiteralPath (Join-Path $handoffRoot $name))) { throw "missing document: $name" }
}
foreach ($toolName in 'New-ModelTaskPrompt.ps1', 'Test-ModelHandoff.ps1') {
    if (-not (Test-Path -LiteralPath (Join-Path $toolsDir $toolName))) { throw "missing tool: $toolName" }
}

$cards = @(Get-ChildItem -LiteralPath (Join-Path $handoffRoot 'task-cards') -Filter 'LE-P??-T??.md' | Sort-Object Name)
if ($cards.Count -ne 44) { throw "expected 44 cards, got $($cards.Count)" }

$expectedIds = @()
$phaseTaskCounts = @(7, 7, 8, 8, 7, 7)
for ($phase = 1; $phase -le 6; $phase++) {
    for ($task = 1; $task -le $phaseTaskCounts[$phase - 1]; $task++) {
        $expectedIds += 'LE-P{0:D2}-T{1:D2}' -f $phase, $task
    }
}
$actualIds = @($cards | ForEach-Object BaseName)
if (@(Compare-Object -ReferenceObject $expectedIds -DifferenceObject $actualIds).Count -ne 0) {
    throw 'Task ID set is not the required 44-ID sequence'
}

$requiredSections = @(
    '唯一目标', '明确不做', '前置条件', '开始状态', '必须阅读', '允许修改',
    '禁止修改', '不可变约束', '执行顺序', '验收命令', '停止条件', '完成报告'
)
$index = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $handoffRoot '02-任务索引.md')
if (([regex]::Matches($index, '(?m)^\|.*\| READY \|\r?$')).Count -ne 1) { throw 'index must contain exactly one READY row' }
if (([regex]::Matches($index, '(?m)^\|.*\| PENDING \|\r?$')).Count -ne 43) { throw 'index must contain exactly 43 PENDING rows' }

for ($cardIndex = 0; $cardIndex -lt $cards.Count; $cardIndex++) {
    $card = $cards[$cardIndex]
    $lines = @(Get-Content -Encoding utf8 -LiteralPath $card.FullName)
    $text = $lines -join [Environment]::NewLine
    if ($text -match 'TBD|TODO|__[A-Z_]+__|matching test|参见|同上') { throw "placeholder or shorthand: $($card.Name)" }
    foreach ($section in $requiredSections) {
        if (-not $text.Contains("## $section")) { throw "$($card.Name) missing $section" }
    }

    $planLine = @($lines | Where-Object { $_.StartsWith('**实施计划：**') })
    $headingLine = @($lines | Where-Object { $_.StartsWith('**计划任务标题：**') })
    $prerequisiteLine = @($lines | Where-Object { $_.StartsWith('**前置任务：**') })
    if ($planLine.Count -ne 1 -or $headingLine.Count -ne 1 -or $prerequisiteLine.Count -ne 1) {
        throw "$($card.Name) has invalid metadata"
    }
    $planParts = $planLine[0].Split([char]96)
    $headingParts = $headingLine[0].Split([char]96)
    if ($planParts.Count -lt 3 -or $headingParts.Count -lt 3) { throw "$($card.Name) has malformed code-span metadata" }
    $planPath = $planParts[1]
    $heading = $headingParts[1]
    if (-not $text.StartsWith("# $($card.BaseName) — $heading")) { throw "ID/title mismatch: $($card.Name)" }

    $expectedPrerequisite = if ($cardIndex -eq 0) { '无' } else { $cards[$cardIndex - 1].BaseName }
    $prerequisite = $prerequisiteLine[0].Substring('**前置任务：**'.Length).Trim()
    if ($prerequisite -ne $expectedPrerequisite) {
        throw "$($card.Name) prerequisite is $prerequisite, expected $expectedPrerequisite"
    }

    $resolvedPlan = [IO.Path]::GetFullPath((Join-Path $card.DirectoryName $planPath))
    if (-not (Test-Path -LiteralPath $resolvedPlan)) { throw "$($card.Name) references missing plan" }
    $phaseNumber = $card.BaseName.Substring(4, 2)
    $taskNumber = [int]$card.BaseName.Substring(8, 2)
    if (-not ([IO.Path]::GetFileName($resolvedPlan)).StartsWith("实施计划-$phaseNumber-")) {
        throw "$($card.Name) references the wrong phase plan"
    }
    $sourceLines = @(Get-Content -Encoding utf8 -LiteralPath $resolvedPlan)
    $sourceHeading = "### Task ${taskNumber}: $heading"
    $sourceStart = [Array]::IndexOf($sourceLines, $sourceHeading)
    if ($sourceStart -lt 0) { throw "$($card.Name) references missing exact heading" }
    $sourceEnd = $sourceLines.Count
    for ($sourceIndex = $sourceStart + 1; $sourceIndex -lt $sourceLines.Count; $sourceIndex++) {
        if ($sourceLines[$sourceIndex].StartsWith('### Task ')) { $sourceEnd = $sourceIndex; break }
    }

    $allowStart = [Array]::IndexOf($lines, '## 允许修改')
    $allowEnd = [Array]::IndexOf($lines, '## 禁止修改')
    if ($allowStart -lt 0 -or $allowEnd -le $allowStart + 1) { throw "$($card.Name) has no concrete allowlist" }
    $allowedPaths = @()
    foreach ($allowLine in $lines[($allowStart + 1)..($allowEnd - 1)]) {
        $parts = $allowLine.Split([char]96)
        for ($partIndex = 1; $partIndex -lt $parts.Count; $partIndex += 2) { $allowedPaths += $parts[$partIndex] }
    }
    if ($allowedPaths.Count -eq 0) { throw "$($card.Name) has no allowlist paths" }

    $sourceFiles = @()
    foreach ($sourceLine in $sourceLines[($sourceStart + 1)..($sourceEnd - 1)]) {
        if ($sourceLine -notmatch '^- (Create|Modify|Test):') { continue }
        $parts = $sourceLine.Split([char]96)
        if ($parts.Count -ge 3) { $sourceFiles += $parts[1] }
    }
    foreach ($sourceFile in $sourceFiles) {
        $covered = $false
        foreach ($allowedPath in $allowedPaths) {
            if ($allowedPath -eq $sourceFile) { $covered = $true; break }
            if ($allowedPath.EndsWith('/**')) {
                $prefix = $allowedPath.Substring(0, $allowedPath.Length - 3)
                if ($sourceFile.StartsWith($prefix, [StringComparison]::Ordinal)) { $covered = $true; break }
            }
        }
        if (-not $covered) { throw "$($card.Name) allowlist does not cover source file $sourceFile" }
    }

    $expectedLink = "[$($card.BaseName)](./task-cards/$($card.Name))"
    if (-not $index.Contains($expectedLink)) { throw "index missing link $expectedLink" }
}

$planFiles = @(Get-ChildItem -LiteralPath $outputRoot -Filter '实施计划-*.md')
$planTaskCount = 0
foreach ($plan in $planFiles) {
    $text = Get-Content -Raw -Encoding utf8 -LiteralPath $plan.FullName
    $planTaskCount += ([regex]::Matches($text, '(?m)^### Task ')).Count
    if (-not $text.Contains('不具备这些技能时')) { throw "$($plan.Name) is not model-neutral" }
}
if ($planFiles.Count -ne 6 -or $planTaskCount -ne 44) {
    throw "expected six plans and 44 tasks, got plans=$($planFiles.Count) tasks=$planTaskCount"
}
$roadmapPath = Join-Path $outputRoot '实施路线图.md'
if (-not (Test-Path -LiteralPath $roadmapPath)) { throw 'missing 实施路线图.md' }
$roadmapText = Get-Content -Raw -Encoding utf8 -LiteralPath $roadmapPath
if (-not $roadmapText.Contains('不具备这些技能时')) { throw '实施路线图.md is not model-neutral' }

foreach ($file in Get-ChildItem -LiteralPath $handoffRoot -Recurse -Filter '*.md') {
    $openFenceLength = 0
    $openFenceLine = 0
    $lineNumber = 0
    foreach ($line in Get-Content -Encoding utf8 -LiteralPath $file.FullName) {
        $lineNumber++
        $fenceLength = 0
        while ($fenceLength -lt $line.Length -and $line[$fenceLength] -eq [char]96) { $fenceLength++ }
        if ($openFenceLength -eq 0 -and $fenceLength -ge 3) {
            $openFenceLength = $fenceLength
            $openFenceLine = $lineNumber
        }
        elseif ($openFenceLength -gt 0 -and $fenceLength -ge $openFenceLength -and $line.Substring($fenceLength).Trim().Length -eq 0) {
            $openFenceLength = 0
            $openFenceLine = 0
        }
    }
    if ($openFenceLength -ne 0) { throw "unclosed Markdown fence: $($file.FullName):$openFenceLine" }
}

$ignorePath = Join-Path $repoRoot '.gitignore'
$ignoreText = Get-Content -Raw -Encoding utf8 -LiteralPath $ignorePath
foreach ($ignoreRule in 'outputs/model-handoff/generated/*.md', 'model-handoff/generated/*.md') {
    if (-not $ignoreText.Contains($ignoreRule)) { throw "missing ignore rule: $ignoreRule" }
}

if (-not $SkipIntegration) {
    $tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("loop-handoff-test-" + [Guid]::NewGuid())
    try {
        New-Item -ItemType Directory -Path $tempRoot | Out-Null
        Copy-Item -Recurse -LiteralPath $handoffRoot -Destination (Join-Path $tempRoot 'model-handoff')
        Copy-Item -LiteralPath (Join-Path $outputRoot '循环工程一期架构设计.md') -Destination $tempRoot
        Get-ChildItem -LiteralPath $outputRoot -Filter '实施计划-*.md' | Copy-Item -Destination $tempRoot
        Get-ChildItem -LiteralPath (Join-Path $tempRoot 'model-handoff/generated') -Filter '*.md' -ErrorAction SilentlyContinue | Remove-Item -Force
        Set-Content -LiteralPath (Join-Path $tempRoot 'README.md') -Value '# test' -Encoding utf8
        & git -C $tempRoot init | Out-Null
        & git -C $tempRoot config user.name 'Handoff Test'
        & git -C $tempRoot config user.email 'handoff-test@example.invalid'
        & git -C $tempRoot config commit.gpgsign false
        & git -C $tempRoot add .
        & git -C $tempRoot commit -m 'test fixture' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'failed to create Git fixture' }

        $generator = Join-Path $tempRoot 'model-handoff/tools/New-ModelTaskPrompt.ps1'
        $success = @(& $generator -TaskId LE-P01-T01 -Model test-model -RepositoryRoot $tempRoot -AllowCommit)
        $successText = $success -join "`n"
        foreach ($requiredText in 'LE-P01-T01', '实施计划-01-仓库骨架与控制面基础.md', '循环工程一期架构设计.md', '前置任务：无') {
            if (-not $successText.Contains($requiredText)) { throw "clean prompt generation missing $requiredText" }
        }
        Get-ChildItem -LiteralPath (Join-Path $tempRoot 'model-handoff/generated') -Filter '*.md' | Remove-Item -Force

        Set-Content -LiteralPath (Join-Path $tempRoot 'README.md') -Value '# dirty' -Encoding utf8
        $dirtyFailed = $false
        try { & $generator -TaskId LE-P01-T01 -RepositoryRoot $tempRoot | Out-Null }
        catch { $dirtyFailed = $_.Exception.Message.Contains('dirty worktree') }
        if (-not $dirtyFailed) { throw 'dirty worktree was accepted' }

        $reasonFailed = $false
        try { & $generator -TaskId LE-P01-T01 -RepositoryRoot $tempRoot -KnownDirtyPath 'README.md' | Out-Null }
        catch { $reasonFailed = $_.Exception.Message.Contains('KnownDeviation is required') }
        if (-not $reasonFailed) { throw 'dirty declaration without reason was accepted' }

        $declared = @(& $generator -TaskId LE-P01-T01 -RepositoryRoot $tempRoot -KnownDirtyPath 'README.md' -KnownDeviation 'integration-test fixture')
        if (-not (($declared -join "`n").Contains('integration-test fixture'))) { throw 'declared dirty worktree was rejected' }

        $unknownFailed = $false
        try { & $generator -TaskId LE-P99-T99 -RepositoryRoot $tempRoot | Out-Null }
        catch { $unknownFailed = $_.Exception.Message.Contains('unknown Task ID') }
        if (-not $unknownFailed) { throw 'unknown Task ID was accepted' }
    }
    finally {
        if ($tempRoot.StartsWith([IO.Path]::GetTempPath(), [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $tempRoot)) {
            Remove-Item -Recurse -Force -LiteralPath $tempRoot
        }
    }
}

"PASS documents=$($requiredDocs.Count) cards=$($cards.Count) planTasks=$planTaskCount integration=$(-not $SkipIntegration)"
