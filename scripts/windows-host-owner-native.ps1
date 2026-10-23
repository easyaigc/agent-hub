param([Parameter(Mandatory)][string]$ScriptPath)
$ErrorActionPreference = 'Stop'
# Load only the identity primitive from the canonical script. Never execute a
# scheduler command, stop a product process, or use another account's credentials.
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path -LiteralPath $ScriptPath), [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) { throw 'Task script parse failed' }
$function = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Initialize-HostProcessIdentity' }, $true))
if ($function.Count -ne 1) { throw 'Expected exactly one native identity initializer' }
Invoke-Expression $function[0].Extent.Text
$clock = [System.Diagnostics.Stopwatch]::StartNew()
Initialize-HostProcessIdentity
$coldMs = $clock.ElapsedMilliseconds
$process = [System.Diagnostics.Process]::GetCurrentProcess()
try {
    $handle = $process.Handle
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if ([AgentHub.HostProcessIdentityV1]::Read($handle) -ne $sid) { throw 'Native owner mismatch' }
    $process.Refresh()
    $before = $process.HandleCount
    $clock.Restart()
    for ($i = 0; $i -lt 100; $i++) {
        if ([AgentHub.HostProcessIdentityV1]::Read($handle) -ne $sid) { throw 'Native owner mismatch' }
    }
    $readsMs = $clock.ElapsedMilliseconds
    $process.Refresh()
    if ($process.HandleCount -gt $before + 5) { throw 'Native token handles leaked' }
    $rejected = $false
    try { $null = [AgentHub.HostProcessIdentityV1]::Read([IntPtr]::Zero) } catch { $rejected = $true }
    if (-not $rejected) { throw 'Invalid process handle accepted' }
    @{ passed = $true; native = $true; coldInitializerMs = $coldMs; reads = 100; readsMs = $readsMs; invalidHandleRejected = $true; schedulerMutations = 0; productProcessesStopped = 0 } | ConvertTo-Json -Compress
} finally { $process.Dispose() }
