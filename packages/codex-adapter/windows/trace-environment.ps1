param([ValidateSet('read','write','remove')][string]$Action='read',
      [ValidateSet('String','ExpandString')][string]$Kind='String')
$ErrorActionPreference='Stop'
try {
    $key=[Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment',$Action -ne 'read')
    if(!$key){throw 'USER_ENVIRONMENT_UNAVAILABLE'}
    try {
        $name='CODEX_ROLLOUT_TRACE_ROOT'
        if($Action -eq 'write') {
            $key.SetValue($name,$env:CODEX_MANAGE_TRACE_VALUE,[Microsoft.Win32.RegistryValueKind]::$Kind)
        } elseif($Action -eq 'remove') { $key.DeleteValue($name,$false) }
        if($Action -ne 'read') {
            Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class CodexTraceEnvironment {
    [DllImport("user32.dll",CharSet=CharSet.Unicode,SetLastError=true)]
    public static extern IntPtr SendMessageTimeout(IntPtr window,uint message,UIntPtr wParam,string value,uint flags,uint timeout,out UIntPtr result);
}
'@
            $result=[UIntPtr]::Zero
            [void][CodexTraceEnvironment]::SendMessageTimeout([IntPtr]0xffff,0x1a,[UIntPtr]::Zero,'Environment',2,3000,[ref]$result)
        }
        $value=$key.GetValue($name,$null,[Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        @{value=$value;kind=if($null -eq $value){$null}else{$key.GetValueKind($name).ToString()}} | ConvertTo-Json -Compress
    } finally { $key.Dispose() }
} catch { [Console]::Error.WriteLine('WINDOWS_TRACE_ENVIRONMENT_FAILED');exit 1 }
