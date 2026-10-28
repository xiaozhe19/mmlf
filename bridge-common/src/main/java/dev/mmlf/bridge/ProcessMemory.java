package dev.mmlf.bridge;
import com.sun.jna.*;
import com.sun.jna.win32.*;
import com.sun.jna.platform.win32.*;
/** Own-process RSS without relying on localized Windows performance counters. */
final class ProcessMemory {
 public interface Api extends StdCallLibrary {boolean GetProcessMemoryInfo(WinNT.HANDLE process,Counters counters,int size);}
 @Structure.FieldOrder({"cb","pageFaultCount","peakWorkingSetSize","workingSetSize","quotaPeakPagedPoolUsage","quotaPagedPoolUsage","quotaPeakNonPagedPoolUsage","quotaNonPagedPoolUsage","pagefileUsage","peakPagefileUsage"})
 public static class Counters extends Structure {
  public int cb,pageFaultCount;
  public BaseTSD.SIZE_T peakWorkingSetSize=new BaseTSD.SIZE_T(),workingSetSize=new BaseTSD.SIZE_T();
  public BaseTSD.SIZE_T quotaPeakPagedPoolUsage=new BaseTSD.SIZE_T(),quotaPagedPoolUsage=new BaseTSD.SIZE_T();
  public BaseTSD.SIZE_T quotaPeakNonPagedPoolUsage=new BaseTSD.SIZE_T(),quotaNonPagedPoolUsage=new BaseTSD.SIZE_T();
  public BaseTSD.SIZE_T pagefileUsage=new BaseTSD.SIZE_T(),peakPagefileUsage=new BaseTSD.SIZE_T();
 }
 private static class Windows {static final Api API=Native.load("psapi",Api.class,W32APIOptions.DEFAULT_OPTIONS);}
 static java.util.OptionalDouble residentMb() {
  if(!Platform.isWindows()) return java.util.OptionalDouble.empty();
  Counters counters=new Counters();counters.cb=counters.size();
  return Windows.API.GetProcessMemoryInfo(Kernel32.INSTANCE.GetCurrentProcess(),counters,counters.cb)?java.util.OptionalDouble.of(counters.workingSetSize.longValue()/1048576.0):java.util.OptionalDouble.empty();
 }
}
