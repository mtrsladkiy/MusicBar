 
 
$utf8 = New-Object System.Text.UTF8Encoding $false
[Console]::OutputEncoding = $utf8

$src = @'
using System;
using System.Runtime.InteropServices;

[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator {
    int NotImpl1();
    int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice ppDevice);
}

[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice {
    int Activate(ref Guid iid, int dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
}

[Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioMeterInformation {
    int GetPeakValue(out float pfPeak);
}

[ComImport, Guid("BCDE0395-E52F-46C0-9E5E-9DF6B0C7B7A0")]
class MMDeviceEnumeratorComObject { }

public class AudioPeak {
    static IAudioMeterInformation meter;

    public static float Get() {
        try {
            if (meter == null) {
                IMMDeviceEnumerator en = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
                IMMDevice dev;
                en.GetDefaultAudioEndpoint(0, 1, out dev);
                Guid iid = new Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064");
                object o;
                dev.Activate(ref iid, 23, IntPtr.Zero, out o);
                meter = (IAudioMeterInformation)o;
            }
            float p;
            meter.GetPeakValue(out p);
            return p;
        } catch (Exception) {
            meter = null;
            return -1f;
        }
    }

    public static void Reset() { meter = null; }
}
'@

try {
    Add-Type -TypeDefinition $src -Language CSharp
} catch {
    exit 1
}

$inv = [System.Globalization.CultureInfo]::InvariantCulture
$n = 0
while ($true) {
    $p = [AudioPeak]::Get()
    [Console]::Out.WriteLine($p.ToString('0.0000', $inv))
    Start-Sleep -Milliseconds 15
    $n++
    if ($n % 400 -eq 0) { [AudioPeak]::Reset() }    
}
