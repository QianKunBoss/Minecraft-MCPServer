package org.du.mcpserver.util;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.du.mcpserver.util.MCCompat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VersionCompat {

    private static final Logger LOGGER = LogManager.getLogger("MCPServer");

    private static final String MINECRAFT_VERSION;
    private static final int VERSION_MAJOR;
    private static final int VERSION_MINOR;
    private static final int VERSION_PATCH;

    static {
        MINECRAFT_VERSION = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("minecraft").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();

        Pattern pattern = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
        Matcher matcher = pattern.matcher(MINECRAFT_VERSION);

        if (matcher.find()) {
            VERSION_MAJOR = Integer.parseInt(matcher.group(1));
            VERSION_MINOR = Integer.parseInt(matcher.group(2));
            VERSION_PATCH = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
        } else {
            VERSION_MAJOR = 1;
            VERSION_MINOR = 20;
            VERSION_PATCH = 1;
        }

        LOGGER.info("Minecraft version detected: {}.{}.{}", VERSION_MAJOR, VERSION_MINOR, VERSION_PATCH);
    }

    public static String getMinecraftVersion() {
        return MINECRAFT_VERSION;
    }

    public static boolean isVersionAtLeast(int major, int minor, int patch) {
        if (VERSION_MAJOR > major) return true;
        if (VERSION_MAJOR < major) return false;
        if (VERSION_MINOR > minor) return true;
        if (VERSION_MINOR < minor) return false;
        return VERSION_PATCH >= patch;
    }

    public static boolean isVersionAtLeast(int major, int minor) {
        return isVersionAtLeast(major, minor, 0);
    }

    public static boolean isCommandV2Available() {
        return isVersionAtLeast(1, 19, 4);
    }

    public static boolean hasPermission(ServerCommandSource source, int level) {
        return MCCompat.hasPermissionLevel(source, level);
    }

    public static Text literal(String text) {
        return Text.literal(text);
    }

    public static void sendFeedback(ServerCommandSource source, Text message) {
        source.sendFeedback(() -> message, false);
    }

    public static void sendError(ServerCommandSource source, Text message) {
        source.sendError(message);
    }
}