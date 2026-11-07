 
$ErrorActionPreference = 'Stop'
$src = @'
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorComObject {}
[ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator {
    [PreserveSig] int EnumAudioEndpoints(int flow, int mask, out IntPtr devices);
    [PreserveSig] int GetDefaultAudioEndpoint(int flow, int role, out IMMDevice device);
}
[ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice {
    [PreserveSig] int Activate(ref Guid iid, int context, IntPtr activation, [MarshalAs(UnmanagedType.IUnknown)] out object result);
}
[ComImport, Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioClient {
    [PreserveSig] int Initialize(int shareMode, uint flags, long duration, long periodicity, IntPtr format, IntPtr session);
    [PreserveSig] int GetBufferSize(out uint frames);
    [PreserveSig] int GetStreamLatency(out long latency);
    [PreserveSig] int GetCurrentPadding(out uint frames);
    [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
    [PreserveSig] int GetMixFormat(out IntPtr format);
    [PreserveSig] int GetDevicePeriod(out long a, out long b);
    [PreserveSig] int Start();
    [PreserveSig] int Stop();
    [PreserveSig] int Reset();
    [PreserveSig] int SetEventHandle(IntPtr handle);
    [PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
}
[ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioCaptureClient {
    [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePos, out ulong qpcPos);
    [PreserveSig] int ReleaseBuffer(uint frames);
    [PreserveSig] int GetNextPacketSize(out uint frames);
}
public class MusicBarLoopback {
    static void Check(int hr) { if (hr < 0) Marshal.ThrowExceptionForHR(hr); }
    public static void Run() {
        IMMDeviceEnumerator en = null; IMMDevice dev = null; IAudioClient client = null; IAudioCaptureClient capture = null;
        IntPtr format = IntPtr.Zero;
        try {
            en = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
            Check(en.GetDefaultAudioEndpoint(0, 1, out dev));
            Guid iid = new Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"); object obj;
            Check(dev.Activate(ref iid, 23, IntPtr.Zero, out obj)); client = (IAudioClient)obj;
            Check(client.GetMixFormat(out format));
            int tag = (ushort)Marshal.ReadInt16(format, 0), channels = (ushort)Marshal.ReadInt16(format, 2);
            int rate = Marshal.ReadInt32(format, 4), align = (ushort)Marshal.ReadInt16(format, 12), bits = (ushort)Marshal.ReadInt16(format, 14);
            if (tag == 0xFFFE) tag = Marshal.ReadInt32(format, 24);  
            if (channels < 1 || channels > 32 || rate < 8000 || rate > 384000 || align < channels * ((bits+7)/8) ||
                !((tag == 3 && bits == 32) || (tag == 1 && (bits == 16 || bits == 24 || bits == 32))))
                throw new Exception("Unsupported Windows mix format: " + tag + "/" + bits);
            Check(client.Initialize(0, 0x00020000, 10000000, 0, format, IntPtr.Zero));
            iid = new Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"); Check(client.GetService(ref iid, out obj)); capture = (IAudioCaptureClient)obj;
            Check(client.Start());
            Stream output = Console.OpenStandardOutput(); byte[] header = BitConverter.GetBytes(rate); output.Write(header, 0, header.Length); output.Flush();
            while (true) {
                uint count; Check(capture.GetNextPacketSize(out count));
                while (count > 0) {
                    IntPtr data; uint frames, flags; ulong p, q; Check(capture.GetBuffer(out data, out frames, out flags, out p, out q));
                    try {
                        if (frames > 1000000) throw new Exception("Invalid packet length");
                        int size = checked((int)frames * align); byte[] raw = new byte[size];
                        if ((flags & 2) == 0) Marshal.Copy(data, raw, 0, size);
                        byte[] mono = new byte[checked((int)frames * 2)]; int bytesPerSample = (bits+7)/8;
                        for (int i=0;i<(int)frames;i++) {
                            double sample = 0;
                            for (int ch=0; ch<channels; ch++) {
                                int j=i*align+ch*bytesPerSample; double v;
                                if (tag == 3) v=BitConverter.ToSingle(raw,j);
                                else if (bits == 16) v=BitConverter.ToInt16(raw,j)/32768.0;
                                else if (bits == 24) { int n=raw[j]|(raw[j+1]<<8)|(raw[j+2]<<16); if ((n&0x800000)!=0) n|=unchecked((int)0xff000000); v=n/8388608.0; }
                                else v=BitConverter.ToInt32(raw,j)/2147483648.0;
                                if (!Double.IsNaN(v) && !Double.IsInfinity(v)) sample+=v;
                            }
                            sample=Math.Max(-1,Math.Min(1,sample/channels)); short s=(short)(sample*32767);
                            mono[i*2]=(byte)(s&255); mono[i*2+1]=(byte)((s>>8)&255);
                        }
                        output.Write(mono,0,mono.Length); output.Flush();
                    } finally { Check(capture.ReleaseBuffer(frames)); }
                    Check(capture.GetNextPacketSize(out count));
                }
                Thread.Sleep(10);
            }
        } finally {
            if (client != null) client.Stop();
            if (format != IntPtr.Zero) Marshal.FreeCoTaskMem(format);
            if (capture != null) Marshal.ReleaseComObject(capture);
            if (client != null) Marshal.ReleaseComObject(client);
            if (dev != null) Marshal.ReleaseComObject(dev);
            if (en != null) Marshal.ReleaseComObject(en);
        }
    }
}
'@
Add-Type -TypeDefinition $src -Language CSharp
[MusicBarLoopback]::Run()
