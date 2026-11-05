param(
    [ValidateSet('inspect', 'bridge', 'trace', 'response-peer')][string]$Action = 'inspect',
    [string]$ExpectedDesktop,
    [string]$ExpectedSha256,
    [int]$ClientPort,
    [int]$ListenerPort,
    [int]$ForwarderPid
)
$ErrorActionPreference = 'Stop'
try {
    foreach ($name in @('Microsoft.PowerShell.Security', 'Microsoft.PowerShell.Utility', 'Microsoft.PowerShell.Management')) {
        Import-Module (Join-Path $PSHOME ('Modules\' + $name)) -ErrorAction Stop
    }
} catch {
    [Console]::Error.WriteLine('WINDOWS_POWERSHELL_MODULE_UNAVAILABLE')
    exit 1
}
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Threading.Tasks;
using Microsoft.Win32.SafeHandles;
public static class CodexManagePipe {
    [DllImport("iphlpapi.dll", SetLastError=true)]
    static extern uint GetExtendedTcpTable(IntPtr table, ref uint size, bool order,
        uint addressFamily, uint tableClass, uint reserved);
    [StructLayout(LayoutKind.Sequential)]
    struct BasicProcessInformation {
        public int ExitStatus;
        public IntPtr Peb;
        public UIntPtr Affinity;
        public int Priority;
        public UIntPtr ProcessId, ParentId;
    }
    [StructLayout(LayoutKind.Sequential)]
    struct TcpRow {
        public uint State, LocalAddress, LocalPort, RemoteAddress, RemotePort, ProcessId;
    }
    [StructLayout(LayoutKind.Sequential)]
    struct TcpTable {
        public uint Count;
        public TcpRow FirstRow;
    }
    [DllImport("ntdll.dll")]
    static extern int NtQueryInformationProcess(IntPtr process, int informationClass,
        out BasicProcessInformation information, int size, out int returned);
    public static uint Parent(uint pid) {
        IntPtr process = OpenProcess(0x0400, false, pid);
        if (process == IntPtr.Zero) throw new Exception("PROCESS_QUERY_FAILED");
        try {
            BasicProcessInformation info; int returned;
            if (NtQueryInformationProcess(process, 0, out info,
                Marshal.SizeOf(typeof(BasicProcessInformation)), out returned) != 0 ||
                returned != Marshal.SizeOf(typeof(BasicProcessInformation)))
                throw new Exception("PROCESS_QUERY_FAILED");
            return checked((uint)info.ParentId.ToUInt64());
        } finally { CloseHandle(process); }
    }
    static int Port(uint value) { return (int)(((value & 255) << 8) | ((value >> 8) & 255)); }
    public static uint ResponsePeer(int clientPort, int listenerPort, uint forwarderPid) {
        // Fixed IPv4 loopback query. Avoid the slow NetTCPIP/CIM provider;
        // no process command lines or socket payloads are read.
        uint size = 0;
        uint status = GetExtendedTcpTable(IntPtr.Zero, ref size, false, 2, 5, 0);
        if (status != 122) throw new Exception("RESPONSE_PEER_REJECTED");
        for (int attempt = 0; attempt < 3; attempt++) {
            if (size < 4 || size > 4 * 1024 * 1024) throw new Exception("RESPONSE_PEER_REJECTED");
            IntPtr buffer = Marshal.AllocHGlobal(checked((int)size));
            try {
                uint capacity = size;
                status = GetExtendedTcpTable(buffer, ref size, false, 2, 5, 0);
                if (status == 122) continue;
                if (status != 0) throw new Exception("RESPONSE_PEER_REJECTED");
                int count = Marshal.ReadInt32(buffer);
                int offset = Marshal.OffsetOf(typeof(TcpTable), "FirstRow").ToInt32();
                int stride = Marshal.SizeOf(typeof(TcpRow));
                if (count < 0 || offset + (long)stride * count > capacity) throw new Exception("RESPONSE_PEER_REJECTED");
                int listeners = 0, peers = 0; uint peer = 0;
                for (int i = 0; i < count; i++) {
                    TcpRow row = (TcpRow)Marshal.PtrToStructure(IntPtr.Add(buffer, offset + stride * i), typeof(TcpRow));
                    if (row.State == 2 && row.LocalAddress == 0x0100007f && Port(row.LocalPort) == listenerPort && row.ProcessId == forwarderPid) listeners++;
                    if (row.State == 5 && row.LocalAddress == 0x0100007f && Port(row.LocalPort) == clientPort &&
                        row.RemoteAddress == 0x0100007f && Port(row.RemotePort) == listenerPort) {
                        peers++; peer = row.ProcessId;
                    }
                }
                if (listeners != 1 || peers != 1 || peer == 0) throw new Exception("RESPONSE_PEER_REJECTED");
                return peer;
            } finally { Marshal.FreeHGlobal(buffer); }
        }
        throw new Exception("RESPONSE_PEER_REJECTED");
    }
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern bool GetNamedPipeServerProcessId(SafePipeHandle pipe, out uint pid);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("advapi32.dll", SetLastError=true)]
    static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    public static string Owner(uint pid) {
        IntPtr process = OpenProcess(0x1000, false, pid), token = IntPtr.Zero;
        if (process == IntPtr.Zero) throw new Exception("PROCESS_QUERY_FAILED");
        try {
            if (!OpenProcessToken(process, 8, out token)) throw new Exception("TOKEN_QUERY_FAILED");
            using (var identity = new WindowsIdentity(token)) return identity.User.Value;
        } finally { if (token != IntPtr.Zero) CloseHandle(token); CloseHandle(process); }
    }
    public static void Bridge(NamedPipeClientStream pipe) {
        Task input = Console.OpenStandardInput().CopyToAsync(pipe);
        Task output = pipe.CopyToAsync(Console.OpenStandardOutput());
        Task.WhenAny(input, output).GetAwaiter().GetResult().GetAwaiter().GetResult();
    }
}
'@
$pipe = $null
try {
    # One retained handle: verify the server before any protocol bytes are forwarded.
    $pipe = [System.IO.Pipes.NamedPipeClientStream]::new('.', 'codex-ipc', [System.IO.Pipes.PipeDirection]::InOut, [System.IO.Pipes.PipeOptions]::Asynchronous)
    try { $pipe.Connect(10000) } catch [System.TimeoutException] { throw 'WINDOWS_DESKTOP_NOT_RUNNING' }
    [uint32]$serverProcessId = 0
    if (-not [CodexManagePipe]::GetNamedPipeServerProcessId($pipe.SafePipeHandle, [ref]$serverProcessId)) { throw 'PIPE_IDENTITY_FAILED' }
    $currentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if ([CodexManagePipe]::Owner($serverProcessId) -ne $currentSid) { throw 'WRONG_DESKTOP_USER' }
    $server = Get-Process -Id $serverProcessId
    if ($server.SessionId -ne [System.Diagnostics.Process]::GetCurrentProcess().SessionId) { throw 'WRONG_DESKTOP_SESSION' }
    $desktop = $server.Path
    if (-not $desktop) { throw 'DESKTOP_PATH_UNAVAILABLE' }
    # Publisher labels and Store package names are not runtime compatibility gates.
    # Keep the same retained pipe, current-user/session checks and exact image hash.
    $digest = (Get-FileHash -LiteralPath $desktop -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($Action -eq 'bridge') {
        if (-not $ExpectedDesktop -or -not $ExpectedSha256 -or
            -not [string]::Equals($desktop, $ExpectedDesktop, [StringComparison]::OrdinalIgnoreCase) -or
            $digest -ne $ExpectedSha256) { throw 'DESKTOP_IDENTITY_CHANGED' }
        [Console]::Error.WriteLine('CODEX_MANAGE_PIPE_READY')
        [CodexManagePipe]::Bridge($pipe)
    } elseif ($Action -eq 'response-peer') {
        # Read-only peer authentication. Retain the verified original IPC handle;
        # never read command lines, credentials or another process's memory.
        if ($ClientPort -lt 1 -or $ClientPort -gt 65535 -or $ListenerPort -lt 1024 -or $ListenerPort -gt 65535 -or $ForwarderPid -lt 1) { throw 'RESPONSE_PEER_REJECTED' }
        $peerId = [CodexManagePipe]::ResponsePeer($ClientPort, $ListenerPort, $ForwarderPid)
        $executor = Get-Process -Id $peerId
        if ([CodexManagePipe]::Parent($peerId) -ne $serverProcessId -or $executor.SessionId -ne $server.SessionId -or [CodexManagePipe]::Owner($peerId) -ne $currentSid -or [IO.Path]::GetFileName($executor.Path) -ine 'codex.exe' -or $executor.StartTime -lt $server.StartTime) { throw 'RESPONSE_PEER_REJECTED' }
        $peerStarted = $executor.StartTime.ToUniversalTime().Ticks
        $executorDigest = (Get-FileHash -LiteralPath $executor.Path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ([CodexManagePipe]::ResponsePeer($ClientPort, $ListenerPort, $ForwarderPid) -ne $peerId -or (Get-Process -Id $peerId).StartTime.ToUniversalTime().Ticks -ne $peerStarted -or (Get-Process -Id $serverProcessId).StartTime.ToUniversalTime().Ticks -ne $server.StartTime.ToUniversalTime().Ticks) { throw 'RESPONSE_PEER_REJECTED' }
        @{ pid = $serverProcessId; startedAtUnixMs = [DateTimeOffset]::new($server.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds(); executableSha256 = $digest; executorPid = $peerId; executorStartedAtUnixMs = [DateTimeOffset]::new($executor.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds(); executorSha256 = $executorDigest; sameUser = $true; sameSession = $true } | ConvertTo-Json -Compress
    } elseif ($Action -eq 'trace') {
        # Retain this authenticated pipe while inspecting the exact main and
        # direct executors. Avoid multiple PowerShell processes per read.
        $started = $server.StartTime.ToUniversalTime().Ticks
        $trace = & (Join-Path $PSScriptRoot 'desktop-trace.ps1') -ExpectedPid $serverProcessId
        if (-not $? -or (Get-Process -Id $serverProcessId).StartTime.ToUniversalTime().Ticks -ne $started) { throw 'DESKTOP_IDENTITY_CHANGED' }
        $value = $trace | ConvertFrom-Json
        if ($value.pid -ne $serverProcessId -or $value.startedAtUnixMs -ne [DateTimeOffset]::new($server.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()) { throw 'DESKTOP_IDENTITY_CHANGED' }
        $trace
    } else {
        @{ pid = $serverProcessId; startedAtUnixMs = [DateTimeOffset]::new($server.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds(); executable = $desktop; executableSha256 = $digest; userSid = $currentSid; sameUser = $true; sameSession = $true } | ConvertTo-Json -Compress
    }
} catch {
    $known = @('WINDOWS_DESKTOP_NOT_RUNNING', 'PROCESS_QUERY_FAILED', 'TOKEN_QUERY_FAILED', 'PIPE_IDENTITY_FAILED', 'WRONG_DESKTOP_USER', 'WRONG_DESKTOP_SESSION', 'DESKTOP_PATH_UNAVAILABLE', 'DESKTOP_IDENTITY_CHANGED', 'RESPONSE_PEER_REJECTED')
    $reason = if ($_.Exception.Message -in $known) { $_.Exception.Message } else { 'WINDOWS_DESKTOP_PIPE_REJECTED' }
    [Console]::Error.WriteLine($reason)
    exit 1
} finally {
    if ($null -ne $pipe) { $pipe.Dispose() }
}
