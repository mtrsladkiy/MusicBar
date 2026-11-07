package dev.musicbar;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MusicBarClient implements ClientModInitializer {
    public static final String MOD_ID = "musicbar";
    public static final Logger LOG = LoggerFactory.getLogger("MusicBar");

    private static MediaMonitor monitor;

    public static MediaMonitor monitor() {
        return monitor;
    }

    @Override
    public void onInitializeClient() {
        MusicConfig.load();
        monitor = new MediaMonitor();
        monitor.start();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> monitor.stop());
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            try { DefaultServer.install(client.gameDirectory.toPath()); }
            catch (Exception e) { LOG.warn("MusicBar: unable to add XudoBox to the server list", e); }
        });

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        KeyMapping openEditor = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.musicbar.open_editor", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openEditor.consumeClick()) {
                mc.setScreen(new EditorScreen(mc.screen));
            }
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "music_bar"), MusicHud::render);
    }
}
