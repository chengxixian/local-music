# Push the current git checkout to GitHub through the Git Database API.
#
# Why: on this machine `git push` fails for both TLS backends
#   - schannel: CRYPT_E_NO_REVOCATION_CHECK
#   - openssl : unable to get local issuer certificate (even with a full CA bundle)
# while `gh` (Go's own cert stack) works. So we build the commit server-side instead:
#   blobs -> tree -> commit -> ref
#
# Usage:
#   pwsh -File scripts/push-via-git-api.ps1 -Owner chengxixian -Repo local-music -Message "feat: ..."
#
# Notes:
#   * File list comes from `git ls-files`, so .gitignore is respected.
#   * Binary files are uploaded as base64 blobs; text files are inlined in the tree.
#   * Creates the initial commit (no parents). Re-running after a successful push
#     would create a second root commit on top of the ref, so it is meant for the first push.

param(
    [string]$Owner = "chengxixian",
    [string]$Repo,
    [string]$Branch = "main",
    [string]$Message = "chore: push via Git Database API",
    [string]$Gh = "D:\tool\gh\gh.exe",
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"

# Extensions we treat as text (inline content in the tree object).
$textExtensions = @(
    ".kt", ".kts", ".java", ".xml", ".md", ".txt", ".properties", ".pro", ".json",
    ".py", ".ps1", ".gitignore", ".yml", ".yaml", ".toml", ".html", ".css", ".js"
)

function Invoke-GhJson {
    param([string]$Method, [string]$Endpoint, [object]$Body)
    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        if ($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Depth 12 -Compress
            [System.IO.File]::WriteAllText($tmp, $json, [System.Text.UTF8Encoding]::new($false))
            $out = & $Gh api --method $Method $Endpoint --input $tmp 2>&1
        } else {
            $out = & $Gh api --method $Method $Endpoint 2>&1
        }
        if ($LASTEXITCODE -ne 0) { throw "gh api $Method $Endpoint failed: $out" }
        return ($out | ConvertFrom-Json)
    } finally {
        Remove-Item $tmp -ErrorAction SilentlyContinue
    }
}

Push-Location $RepoRoot
try {
    if (-not $Repo) { throw "-Repo is required" }

    # Re-runnable: find the current head, otherwise seed the repo.
    $headSha = $null
    try {
        $refInfo = Invoke-GhJson -Method GET -Endpoint "/repos/$Owner/$Repo/git/ref/heads/$Branch"
        $headSha = $refInfo.object.sha
    } catch { $headSha = $null }

    if (-not $headSha) {
        # The Git Database API returns 409 "Git Repository is empty" until the repo has one commit,
        # so put a README in through the Contents API first (it works on empty repos).
        Write-Host "empty repository -> seeding with README.md"
        $seedPath = Join-Path $RepoRoot "README.md"
        $seed = if (Test-Path $seedPath) { [System.IO.File]::ReadAllText($seedPath) } else { "# $Repo`n" }
        $b64 = [System.Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($seed))
        $seedRes = Invoke-GhJson -Method PUT -Endpoint "/repos/$Owner/$Repo/contents/README.md" `
            -Body @{ message = "chore: seed repository"; content = $b64; branch = $Branch }
        $headSha = $seedRes.commit.sha
        Write-Host "  seed commit $headSha"
    } else {
        Write-Host "existing head $headSha"
    }

    $files = & git ls-files
    Write-Host "files to push: $($files.Count)"

    $treeEntries = New-Object System.Collections.ArrayList
    $index = 0
    foreach ($file in $files) {
        $index++
        $ext = [System.IO.Path]::GetExtension($file).ToLowerInvariant()
        $isText = $textExtensions -contains $ext -or [System.IO.Path]::GetFileName($file) -eq ".gitignore"
        $bytes = [System.IO.File]::ReadAllBytes((Join-Path $RepoRoot $file))

        if ($isText) {
            $content = [System.Text.Encoding]::UTF8.GetString($bytes)
            [void]$treeEntries.Add(@{ path = $file; mode = "100644"; type = "blob"; content = $content })
        } else {
            $b64 = [System.Convert]::ToBase64String($bytes)
            $blob = Invoke-GhJson -Method POST -Endpoint "/repos/$Owner/$Repo/git/blobs" -Body @{ content = $b64; encoding = "base64" }
            [void]$treeEntries.Add(@{ path = $file; mode = "100644"; type = "blob"; sha = $blob.sha })
        }
        if ($index % 10 -eq 0) { Write-Host "  prepared $index/$($files.Count)" }
    }

    Write-Host "creating tree..."
    $tree = Invoke-GhJson -Method POST -Endpoint "/repos/$Owner/$Repo/git/trees" -Body @{ tree = $treeEntries }

    Write-Host "creating commit..."
    $commit = Invoke-GhJson -Method POST -Endpoint "/repos/$Owner/$Repo/git/commits" `
        -Body @{ message = $Message; tree = $tree.sha; parents = @($headSha) }

    Write-Host "updating ref refs/heads/$Branch..."
    $ref = Invoke-GhJson -Method PATCH -Endpoint "/repos/$Owner/$Repo/git/refs/heads/$Branch" -Body @{ sha = $commit.sha; force = $true }

    Write-Host ""
    Write-Host "OK  commit $($commit.sha)"
    Write-Host "    https://github.com/$Owner/$Repo"
} finally {
    Pop-Location
}
