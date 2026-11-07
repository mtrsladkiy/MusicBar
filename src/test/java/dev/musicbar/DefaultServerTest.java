package dev.musicbar;
import java.nio.file.*;
import net.minecraft.nbt.*;
public final class DefaultServerTest {
  static void check(boolean b, String s) { if(!b)throw new AssertionError(s); }
  public static void main(String[] args) throws Exception {
    Path dir=Files.createTempDirectory("musicbar-servers-test-"); Path file=dir.resolve("servers.dat");
    CompoundTag root=new CompoundTag();root.putString("custom", "preserve");ListTag list=new ListTag();
    CompoundTag other=new CompoundTag();other.putString("name","Other");other.putString("ip","example.org");other.putString("customData","safe");list.add(other);root.put("servers",list);NbtIo.write(root,file);
    DefaultServer.install(dir);root=NbtIo.read(file);list=root.getList("servers").orElseThrow();
    check(list.size()==2,"server count");check(root.getStringOr("custom","").equals("preserve"),"root fields");
    check(list.getCompoundOrEmpty(0).getStringOr("customData","").equals("safe"),"entry fields");
    check(list.getCompoundOrEmpty(1).getStringOr("name","").equals("Лучший BoxPvP"),"name");
    check(list.getCompoundOrEmpty(1).getStringOr("ip","").equals("xudobox.online"),"address");
    list.remove(1);NbtIo.write(root,file);DefaultServer.install(dir);check(NbtIo.read(file).getList("servers").orElseThrow().size()==1,"deleted server must not return");
    Files.delete(dir.resolve("config/musicbar-xudobox-added.flag"));list.getCompoundOrEmpty(0).putString("ip","XUDOBOX.ONLINE:25565");NbtIo.write(root,file);DefaultServer.install(dir);
    check(NbtIo.read(file).getList("servers").orElseThrow().size()==1,"duplicate");
    Path bad=Files.createTempDirectory("musicbar-bad-test-");byte[] bytes={1,2,3};Files.write(bad.resolve("servers.dat"),bytes);
    try { DefaultServer.install(bad); throw new AssertionError("corrupt file accepted"); } catch(java.io.IOException | RuntimeException expected) { }
    check(java.util.Arrays.equals(bytes,Files.readAllBytes(bad.resolve("servers.dat"))),"corrupt file altered");
    System.out.println("Passed: add, preserve, delete, duplicates, corrupt list");
  }
}
