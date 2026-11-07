 
 
$utf8 = New-Object System.Text.UTF8Encoding $false
[Console]::OutputEncoding = $utf8
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($op, $type) {
    $t = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
    if ($t.Wait(3000)) { return $t.Result } else { return $null }
}

$mgrType  = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$propType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]

$mgr = $null
try { $mgr = Await ($mgrType::RequestAsync()) $mgrType } catch { }
if ($null -eq $mgr) {
    [Console]::Out.WriteLine('{"error":"Windows media API unavailable"}')
    Start-Sleep -Seconds 5
    exit 1
}

while ($true) {
    try {
        $s = $mgr.GetCurrentSession()
        if ($null -eq $s) {
            [Console]::Out.WriteLine('{"none":true}')
        } else {
            $p  = Await ($s.TryGetMediaPropertiesAsync()) $propType
            $tl = $s.GetTimelineProperties()
            $pb = $s.GetPlaybackInfo()
            $o = [ordered]@{
                title   = [string]$p.Title
                artist  = [string]$p.Artist
                album   = [string]$p.AlbumTitle
                status  = $pb.PlaybackStatus.ToString()
                pos     = [int64]$tl.Position.TotalMilliseconds
                start   = [int64]$tl.StartTime.TotalMilliseconds
                end     = [int64]$tl.EndTime.TotalMilliseconds
                updated = [int64]$tl.LastUpdatedTime.ToUnixTimeMilliseconds()
                app     = [string]$s.SourceAppUserModelId
            }
            [Console]::Out.WriteLine(($o | ConvertTo-Json -Compress))
            [Console]::Out.Flush()
        }
    } catch {
        [Console]::Out.WriteLine('{"none":true}')
    }
    Start-Sleep -Milliseconds 500
}
