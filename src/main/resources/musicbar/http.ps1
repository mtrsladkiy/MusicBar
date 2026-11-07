param([string]$Url,[string]$Out,[string]$UserAgent)
 
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try { [Net.ServicePointManager]::SecurityProtocol = (3072 -bor 12288) } catch { try { [Net.ServicePointManager]::SecurityProtocol = 3072 } catch {} }
try { [Net.WebRequest]::DefaultWebProxy.Credentials = [Net.CredentialCache]::DefaultCredentials } catch {}
try {
    $r = Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Out -UserAgent $UserAgent -MaximumRedirection 0 -TimeoutSec 15 -PassThru
    Write-Output ([int]$r.StatusCode)
} catch {
    $resp = $_.Exception.Response
    if ($resp) { Write-Output ([int]$resp.StatusCode) } else { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }
}
