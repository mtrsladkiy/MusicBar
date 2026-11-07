param([string]$Expected)
 
$ErrorActionPreference = 'Stop'
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $method = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    })[0]
    function Await($op, $type) {
        $task = $method.MakeGenericMethod($type).Invoke($null, @($op))
        if (-not $task.Wait(3000)) { throw 'WinRT timeout' }
        return $task.Result
    }
    $want = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Expected)) | ConvertFrom-Json
    $managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]
    $propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType=WindowsRuntime]
    $streamType = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType=WindowsRuntime]
    $readerType = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType=WindowsRuntime]
    $manager = Await ($managerType::RequestAsync()) $managerType
    $session = $manager.GetCurrentSession()
    if ($null -eq $session -or [string]$session.SourceAppUserModelId -cne [string]$want.app) { exit 2 }
    $p = Await ($session.TryGetMediaPropertiesAsync()) $propsType
    if ([string]$p.Title -cne [string]$want.title -or [string]$p.Artist -cne [string]$want.artist -or $null -eq $p.Thumbnail) { exit 2 }
    $stream = Await ($p.Thumbnail.OpenReadAsync()) $streamType
    try {
        $size = [long]$stream.Size
        if ($size -le 0 -or $size -gt 1048576) { exit 2 }
        $reader = [Windows.Storage.Streams.DataReader]::new($stream.GetInputStreamAt(0))
        try {
            $loaded = Await ($reader.LoadAsync([uint32]$size)) ([uint32])
            if ([long]$loaded -ne $size) { exit 2 }
            $bytes = New-Object byte[] ([int]$size)
            $reader.ReadBytes($bytes)
        } finally { $reader.Dispose() }
    } finally { $stream.Dispose() }
    $now = $manager.GetCurrentSession()
    if ($null -eq $now -or [string]$now.SourceAppUserModelId -cne [string]$want.app) { exit 2 }
    $p2 = Await ($now.TryGetMediaPropertiesAsync()) $propsType
    if ([string]$p2.Title -cne [string]$want.title -or [string]$p2.Artist -cne [string]$want.artist) { exit 2 }
    $out = [Console]::OpenStandardOutput()
    $out.Write($bytes, 0, $bytes.Length)
    $out.Flush()
} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }
