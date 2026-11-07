package dev.musicbar;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

 
final class DefaultServer {
    static final String ADDRESS = "xudobox.online";
    static final String NAME = "Лучший BoxPvP";
    private DefaultServer() {}

    static void install(Path gameDir) throws IOException {
        Path marker = gameDir.resolve("config/musicbar-xudobox-added.flag");
        if (Files.exists(marker)) return;
        Path file = gameDir.resolve("servers.dat");
        boolean existed = Files.exists(file);
        CompoundTag root = existed ? NbtIo.read(file) : new CompoundTag();
        if (root == null) throw new IOException("servers.dat could not be read; preserving it");
        ListTag servers;
        if (root.contains("servers")) {
            servers = root.getList("servers").orElseThrow(() -> new IOException("Unexpected servers.dat structure"));
        } else if (existed) {
            throw new IOException("Missing server list; preserving servers.dat");
        } else {
            servers = new ListTag(); root.put("servers", servers);
        }
        for (Tag tag : servers) {
            if (!(tag instanceof CompoundTag entry)) throw new IOException("Unexpected server entry; preserving servers.dat");
            String addr=entry.getStringOr("ip", "").trim().toLowerCase(Locale.ROOT);
            if (addr.equals(ADDRESS) || addr.equals(ADDRESS + ":25565")) { mark(marker); return; }
        }
        CompoundTag server = new CompoundTag();
        server.putString("name", NAME); server.putString("ip", ADDRESS);
        servers.add(server);
        Path temp = Files.createTempFile(gameDir, "musicbar-servers-", ".dat");
        try {
            NbtIo.write(root,temp);
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
        mark(marker);
    }
    private static void mark(Path marker) throws IOException {
        Files.createDirectories(marker.getParent());
        Files.writeString(marker,"Server added once. Delete this file to allow Music Bar to add it again.\n",StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
    }
}
