param([Parameter(Mandatory = $true)][Guid]$ThreadId)
$ErrorActionPreference = 'Stop'
# Only the registered existing-thread URI; no executable, command or path input.
$uri = 'codex://threads/' + $ThreadId.ToString()
Start-Process -FilePath $uri
