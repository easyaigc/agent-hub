# Used only by the explicit Host profile-v1 mode. Existing AppData objects are
# never adopted, protected or granted an exception by this bootstrap.
function Assert-HostBootstrapPath([string]$Path, [bool]$Private) {
    $item=Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if(-not $item.PSIsContainer -or (([int]$item.Attributes -band [int][IO.FileAttributes]::ReparsePoint) -ne 0)){throw 'UNSAFE_HOST_BOOTSTRAP_PATH'}
    $acl=Get-Acl -LiteralPath $Path -ErrorAction Stop
    $user=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $allowed=@($user.Value,'S-1-5-18','S-1-5-32-544')
    if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin $allowed){throw 'UNSAFE_HOST_BOOTSTRAP_OWNER'}
    if($Private -and -not $acl.AreAccessRulesProtected){throw 'UNPROTECTED_HOST_BOOTSTRAP_PATH'}
    foreach($rule in $acl.Access){
        if($rule.AccessControlType -ne 'Allow' -or (([int]$rule.PropagationFlags -band [int][Security.AccessControl.PropagationFlags]::InheritOnly) -ne 0)){continue}
        $sid=$rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        # Namespace ancestors cannot permit replacement, reparse/attribute or
        # ACL changes. Append-directory alone on a system ancestor is not a
        # grant on our already-existing child. The private anchor permits none.
        $mask=([long]$rule.FileSystemRights -band 0xffffffffL)
        if($sid -notin $allowed -and ($Private -or (($mask -band 0x500D0152L) -ne 0))){throw 'SHARED_HOST_BOOTSTRAP_PATH'}
    }
}
function New-HostBootstrapDirectory([string]$Path) {
    if(Test-Path -LiteralPath $Path){Assert-HostBootstrapPath $Path $true;return}
    $security=[Security.AccessControl.DirectorySecurity]::new()
    $user=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $security.SetOwner($user);$security.SetAccessRuleProtection($true,$false)
    foreach($sid in @($user.Value,'S-1-5-18','S-1-5-32-544')){
        $security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),[Security.AccessControl.FileSystemRights]::FullControl,([Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit),[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow))
    }
    [void][IO.Directory]::CreateDirectory($Path,$security)
    Assert-HostBootstrapPath $Path $true
}
function Assert-HostBootstrapSource([string]$SourceDirectory,[string]$Anchor) {
    $full=[IO.Path]::GetFullPath($SourceDirectory).TrimEnd('\')
    $prefix=[IO.Path]::GetFullPath($Anchor).TrimEnd('\')+'\'
    if(-not $full.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){throw 'UNSAFE_HOST_BOOTSTRAP_SOURCE'}
    $cursor=$prefix.TrimEnd('\')
    foreach($part in $full.Substring($prefix.Length).Split('\')){
        $cursor=Join-Path $cursor $part;Assert-HostBootstrapPath $cursor $false
    }
    # Frozen code may be readable, but no untrusted subject may write its files
    # or add/replace source children. Unlike system ancestors, APPEND is unsafe.
    $user=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $allowed=@($user.Value,'S-1-5-18','S-1-5-32-544')
    foreach($item in @((Get-Item -LiteralPath $full -Force)) + @(Get-ChildItem -LiteralPath $full -Force)){
        if(([int]$item.Attributes -band [int][IO.FileAttributes]::ReparsePoint) -ne 0){throw 'UNSAFE_HOST_BOOTSTRAP_SOURCE'}
        $acl=Get-Acl -LiteralPath $item.FullName
        if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin $allowed){throw 'UNSAFE_HOST_BOOTSTRAP_SOURCE_OWNER'}
        foreach($rule in $acl.Access){
            if($rule.AccessControlType -ne 'Allow' -or (([int]$rule.PropagationFlags -band [int][Security.AccessControl.PropagationFlags]::InheritOnly) -ne 0)){continue}
            $sid=$rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
            if($sid -notin $allowed -and (([long]$rule.FileSystemRights -band 0x500D0156L) -ne 0)){throw 'SHARED_HOST_BOOTSTRAP_SOURCE'}
        }
    }
}
function Open-HostBootstrapScratch([string]$SourceDirectory) {
    $mode=[Environment]::GetEnvironmentVariable('CODEX_MANAGE_WINDOWS_NATIVE_STORAGE')
    if($null -eq $mode){return $null}
    if($mode -cne 'profile-v1'){throw 'UNKNOWN_HOST_NATIVE_STORAGE'}
    $userProfileDirectory=[Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    if([string]::IsNullOrWhiteSpace($userProfileDirectory)){throw 'HOST_PROFILE_MISSING'}
    $cursor=$userProfileDirectory
    Assert-HostBootstrapPath $cursor $false
    $cursor=Join-Path $cursor '.config';Assert-HostBootstrapPath $cursor $false
    $cursor=Join-Path $cursor 'agent-hub';Assert-HostBootstrapPath $cursor $true
    Assert-HostBootstrapSource $SourceDirectory $cursor
    $cursor=Join-Path $cursor 'windows-native-v1';New-HostBootstrapDirectory $cursor
    $cursor=Join-Path $cursor 'bootstrap';New-HostBootstrapDirectory $cursor
    $scratch=Join-Path $cursor ([Guid]::NewGuid().ToString('D'))
    if(Test-Path -LiteralPath $scratch){throw 'HOST_BOOTSTRAP_ALREADY_EXISTS'}
    New-HostBootstrapDirectory $scratch
    $result=@{path=$scratch;temp=$env:TEMP;tmp=$env:TMP}
    $env:TEMP=$scratch;$env:TMP=$scratch
    return $result
}
function Close-HostBootstrapScratch($Scratch) {
    if($null -eq $Scratch){return}
    $env:TEMP=$Scratch.temp;$env:TMP=$Scratch.tmp
    # Called only after synchronous CodeDom/native compilation returned. A
    # terminated caller never executes this cleanup or guesses compiler exit.
    try {
        Assert-HostBootstrapPath $Scratch.path $true
        foreach($item in Get-ChildItem -LiteralPath $Scratch.path -Recurse -Force){if(([int]$item.Attributes -band [int][IO.FileAttributes]::ReparsePoint) -ne 0){throw 'HOST_BOOTSTRAP_REPARSE'}}
        [IO.Directory]::Delete($Scratch.path,$true)
    } catch {[Console]::Error.WriteLine('WINDOWS_PRIVATE_BOOTSTRAP_SCRATCH_RETAINED')}
}
