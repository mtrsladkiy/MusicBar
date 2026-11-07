param([Parameter(Mandatory=$true)][int]$TargetPid)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
$src = @'
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

[ComImport, Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioClient {
    [PreserveSig] int Initialize(int mode, uint flags, long duration, long periodicity, IntPtr format, IntPtr session);
    [PreserveSig] int GetBufferSize(out uint frames);
    [PreserveSig] int GetStreamLatency(out long latency);
    [PreserveSig] int GetCurrentPadding(out uint frames);
    [PreserveSig] int IsFormatSupported(int mode, IntPtr format, out IntPtr closest);
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
    [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong p, out ulong q);
    [PreserveSig] int ReleaseBuffer(uint frames);
    [PreserveSig] int GetNextPacketSize(out uint frames);
}
[ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IActivateAudioInterfaceAsyncOperation {
    [PreserveSig] int GetActivateResult(out int result, [MarshalAs(UnmanagedType.IUnknown)] out object activated);
}
[ComVisible(true), Guid("41D949AB-9862-444A-80F6-C261334DA5EB"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IActivateAudioInterfaceCompletionHandler {
    [PreserveSig] int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation);
}
[ComVisible(true), Guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IAgileObject {}
[ComVisible(true), ClassInterface(ClassInterfaceType.None)]
public class ActivationHandler : IActivateAudioInterfaceCompletionHandler, IAgileObject {
    public readonly ManualResetEvent Done = new ManualResetEvent(false);
    internal IAudioClient Client; public string Stage = "GetActivateResult"; public int Result = unchecked((int)0x80004005);
    public int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation) {
        try { int hr; object client; int call = operation.GetActivateResult(out hr, out client); Result = call < 0 ? call : hr; if (Result >= 0) { Stage = "QueryInterface IAudioClient in MTA callback"; Client = (IAudioClient)client; } }
        catch (Exception e) { Result = Marshal.GetHRForException(e); }
        finally { Done.Set(); }
        return 0;
    }
}
public class MusicBarProcessAudio {
    [DllImport("Mmdevapi.dll", CharSet=CharSet.Unicode, ExactSpelling=true)]
    static extern int ActivateAudioInterfaceAsync(string device, ref Guid iid, IntPtr parameters,
        [MarshalAs(UnmanagedType.Interface)] IActivateAudioInterfaceCompletionHandler handler, out IntPtr operation);
    [DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
    static extern IntPtr CreateEvent(IntPtr security, bool manualReset, bool initialState, string name);
    [DllImport("kernel32.dll", SetLastError=true)] static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool CloseHandle(IntPtr handle);
    static void Check(int hr) { if (hr < 0) Marshal.ThrowExceptionForHR(hr); }
    public static void Run(int pid) {
        IntPtr param=IntPtr.Zero, prop=IntPtr.Zero, op=IntPtr.Zero, format=IntPtr.Zero, audioEvent=IntPtr.Zero;
        IAudioClient client=null; IAudioCaptureClient capture=null; string stage="activate";
        try {
             
            param=Marshal.AllocHGlobal(12); Marshal.WriteInt32(param,0,1); Marshal.WriteInt32(param,4,pid); Marshal.WriteInt32(param,8,0);
            int pvSize=IntPtr.Size==8 ? 24 : 16; prop=Marshal.AllocHGlobal(pvSize);
            for (int i=0;i<pvSize;i++) Marshal.WriteByte(prop,i,0);
            Marshal.WriteInt16(prop,0,65); Marshal.WriteInt32(prop,8,12); Marshal.WriteIntPtr(prop,IntPtr.Size==8?16:12,param);
            var handler=new ActivationHandler(); Guid iid=new Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2");
            Check(ActivateAudioInterfaceAsync("VAD\\Process_Loopback",ref iid,prop,handler,out op));
             
            if(!handler.Done.WaitOne(15000)) { Console.Error.WriteLine("MusicBar: audio activation timed out"); Environment.Exit(2); }
            stage = handler.Stage; Check(handler.Result); client=handler.Client;
             
            format=Marshal.AllocHGlobal(18); for(int i=0;i<18;i++) Marshal.WriteByte(format,i,0);
            Marshal.WriteInt16(format,0,1); Marshal.WriteInt16(format,2,2); Marshal.WriteInt32(format,4,44100);
            Marshal.WriteInt32(format,8,176400); Marshal.WriteInt16(format,12,4); Marshal.WriteInt16(format,14,16);
            stage="IAudioClient.Initialize"; Check(client.Initialize(0,0x80060000u,0,0,format,IntPtr.Zero));
            audioEvent=CreateEvent(IntPtr.Zero,false,false,null); if(audioEvent==IntPtr.Zero) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            stage="IAudioClient.SetEventHandle"; Check(client.SetEventHandle(audioEvent));
            iid=new Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"); object obj; stage="IAudioClient.GetService"; Check(client.GetService(ref iid,out obj)); stage="QueryInterface IAudioCaptureClient"; capture=(IAudioCaptureClient)obj;
            stage="IAudioClient.Start"; Check(client.Start()); stage="audio capture"; Stream output=Console.OpenStandardOutput(); byte[] header=BitConverter.GetBytes(44100); output.Write(header,0,4); output.Flush();
            while(true) {
                uint count; Check(capture.GetNextPacketSize(out count));
                while(count>0) {
                    IntPtr data; uint frames,flags; ulong p,q; Check(capture.GetBuffer(out data,out frames,out flags,out p,out q));
                    try {
                        if(frames>1000000) throw new Exception("Invalid audio packet");
                        byte[] raw=new byte[checked((int)frames*4)];
                        if((flags&2)==0) Marshal.Copy(data,raw,0,raw.Length);
                        byte[] mono=new byte[checked((int)frames*2)];
                        for(int i=0;i<(int)frames;i++) {
                            int left=(short)(raw[4*i] | raw[4*i+1]<<8), right=(short)(raw[4*i+2] | raw[4*i+3]<<8);
                            int sample=(left+right)/2; mono[i*2]=(byte)(sample&255); mono[i*2+1]=(byte)((sample>>8)&255);
                        }
                        output.Write(mono,0,mono.Length); output.Flush();
                    } finally { Check(capture.ReleaseBuffer(frames)); }
                    Check(capture.GetNextPacketSize(out count));
                }
                WaitForSingleObject(audioEvent,1000);
            }
        } catch(Exception e) { Console.Error.WriteLine("MusicBar stage=" + stage + " exception=" + e.GetType().FullName + " HRESULT=0x" + e.HResult.ToString("X8")); throw; } finally {
            if(client!=null) client.Stop(); if(audioEvent!=IntPtr.Zero) CloseHandle(audioEvent); if(op!=IntPtr.Zero) Marshal.Release(op);
            if(format!=IntPtr.Zero) Marshal.FreeHGlobal(format); if(prop!=IntPtr.Zero) Marshal.FreeHGlobal(prop); if(param!=IntPtr.Zero) Marshal.FreeHGlobal(param);
            if(capture!=null) Marshal.ReleaseComObject(capture); if(client!=null) Marshal.ReleaseComObject(client);
        }
    }
}
'@
Add-Type -TypeDefinition $src -Language CSharp
[MusicBarProcessAudio]::Run($TargetPid)
