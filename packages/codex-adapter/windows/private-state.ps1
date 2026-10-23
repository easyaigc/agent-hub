param([ValidateSet('protect', 'protect-file', 'check')][string]$Action, [Parameter(Mandatory)][string]$Path)
$ErrorActionPreference = 'Stop'
try {
    foreach ($name in @('Microsoft.PowerShell.Security', 'Microsoft.PowerShell.Utility', 'Microsoft.PowerShell.Management')) {
        Import-Module (Join-Path $PSHOME ('Modules\' + $name)) -ErrorAction Stop
    }
} catch {
    [Console]::Error.WriteLine('WINDOWS_POWERSHELL_MODULE_UNAVAILABLE')
    exit 1
}
try {
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) { throw 'REPARSE_POINT' }
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    if ($Action -in @('protect', 'protect-file')) {
        if ($Action -eq 'protect') {
            if (-not $item.PSIsContainer) { throw 'DIRECTORY_REQUIRED' }
            $acl = [System.Security.AccessControl.DirectorySecurity]::new()
        } else {
            if ($item.PSIsContainer) { throw 'FILE_REQUIRED' }
            $acl = [System.Security.AccessControl.FileSecurity]::new()
        }
        $acl.SetOwner($sid)
        $acl.SetAccessRuleProtection($true, $false)
        foreach ($id in @($sid.Value, 'S-1-5-18', 'S-1-5-32-544')) {
            $identity = [System.Security.Principal.SecurityIdentifier]::new($id)
            $rule = if ($Action -eq 'protect') {
                [System.Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
            } else {
                [System.Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'Allow')
            }
            $acl.AddAccessRule($rule)
        }
        Set-Acl -LiteralPath $Path -AclObject $acl
    }
    $acl = Get-Acl -LiteralPath $Path
    if ($acl.GetOwner([System.Security.Principal.SecurityIdentifier]).Value -ne $sid.Value) { throw 'WRONG_FILE_OWNER' }
    foreach ($rule in $acl.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier])) {
        if ($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -notin @($sid.Value, 'S-1-5-18', 'S-1-5-32-544')) { throw 'SHARED_PRIVATE_FILE' }
    }
    [Console]::Out.WriteLine('PRIVATE_STATE_OK')
} catch {
    [Console]::Error.WriteLine('UNSAFE_WINDOWS_PRIVATE_STATE')
    exit 1
}
