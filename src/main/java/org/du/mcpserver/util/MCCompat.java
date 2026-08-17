package org.du.mcpserver.util;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 跨版本兼容层（单一共享源码方案的核心）。
 *
 * 问题背景：yarn 映射名在 1.20.1 → 1.20.4+ → 1.20.6+ → 1.21+ 之间会发生重命名/移除，
 * 导致同一份源码无法在全部版本下编译。本类用「反射」规避：
 *   - 方法：按【返回类型 + 参数类型】签名匹配（不依赖方法名），因此在 dev（yarn 命名）
 *     和生产（intermediary 命名）两种运行时都能正确解析；必要时也先尝试已知的方法名。
 *   - 字段（PlayerInventory 的 main/armor/offHand/selectedSlot）：类型相同无法靠签名区分，
 *     故同时尝试【intermediary 中间名】与【yarn 名】两个候选。
 *
 * 涉及的版本断点：
 *  - CommandManager.executeWithPrefix 返回 int(1.20.1) → void(1.20.4+)；中间名 method_44252
 *  - ItemStack.hasNbt()/getNbt() 于 1.20.6+ 被移除；中间名 getNbt=method_7969
 *  - MinecraftServer.getRunDirectory() 返回 File → Path(1.21+)，统一改用 FabricLoader.getGameDir()
 *  - Entity.getPos()/getWorld()、ItemStack.getTranslationKey()、ServerCommandSource.hasPermissionLevel/withLevel
 *    在 1.21.x 被重命名；本层用签名反射兜底。
 *  - PlayerInventory.main/armor/offHand/selectedSlot 在 1.21.x 变为 private；中间名
 *    main=field_7547, armor=field_7548, offHand=field_7544, selectedSlot=field_7545
 *  - HoverEvent/ClickEvent 在 1.21.x 变为 abstract，改用静态工厂 showText()/copyToClipboard()
 *  - NbtString.asString() 返回类型由 String(1.20.1) 变为 Optional<String>(1.21+)
 */
public final class MCCompat {

    // 跨版本稳定的中间名（来自 yarn 映射，intermediary 不会随版本变化）
    private static final String M_EXECUTE_WITH_PREFIX = "method_44252";   // CommandManager.executeWithPrefix(ServerCommandSource, String)
    private static final String M_ITEMSTACK_GET_NBT = "method_7969";      // ItemStack.getNbt() -> NbtCompound
    private static final String F_INV_MAIN = "field_7547";                // PlayerInventory.main
    private static final String F_INV_ARMOR = "field_7548";              // PlayerInventory.armor
    private static final String F_INV_OFFHAND = "field_7544";            // PlayerInventory.offHand
    private static final String F_INV_SELECTED_SLOT = "field_7545";     // PlayerInventory.selectedSlot

    private static final Method EXECUTE_WITH_PREFIX;
    private static final Method ITEM_GET_NBT;

    static {
        EXECUTE_WITH_PREFIX = findMethod(CommandManager.class,
                new String[]{M_EXECUTE_WITH_PREFIX, "executeWithPrefix"}, null,
                ServerCommandSource.class, String.class);
        ITEM_GET_NBT = findMethod(ItemStack.class,
                new String[]{M_ITEMSTACK_GET_NBT, "getNbt"}, NbtCompound.class);
    }

    private MCCompat() {}

    // ===================== 通用反射工具 =====================

    /**
     * 按候选方法名 + 签名（返回类型/参数类型）查找方法，任一命中即可。
     * 先在类及其父类中按名字精确查找，再退化为按签名扫描，从而兼容 dev 与 prod 两套命名。
     */
    private static Method findMethod(Class<?> clazz, String[] names, Class<?> returnType, Class<?>... params) {
        for (String n : names) {
            try {
                Method m = clazz.getMethod(n, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignore) {
            }
            try {
                Method m = clazz.getDeclaredMethod(n, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignore) {
            }
        }
        // 退化：按签名扫描（不依赖方法名）
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() == params.length
                        && Arrays.equals(m.getParameterTypes(), params)
                        && (returnType == null || returnType.equals(m.getReturnType())
                            || returnType.isAssignableFrom(m.getReturnType()))) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String[] names) {
        for (String n : names) {
            try {
                Field f = clazz.getField(n);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignore) {
            }
            try {
                Field f = clazz.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignore) {
            }
        }
        return null;
    }

    private static Object invoke(Method m, Object obj, Object... args) {
        if (m == null) return null;
        try {
            return m.invoke(obj, args);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object readField(Field f, Object obj) {
        if (f == null) return null;
        try {
            return f.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== 原兼容性 API =====================

    /** 取物品自定义 NBT；1.20.6+ 不可用则返回 null（优雅降级）。 */
    @SuppressWarnings("unchecked")
    public static NbtCompound itemGetNbt(ItemStack stack) {
        return (NbtCompound) invoke(ITEM_GET_NBT, stack);
    }

    /** 跨版本稳定地取服务端运行目录（等价于旧版 server.getRunDirectory().toPath()）。 */
    public static java.nio.file.Path getRunDirectoryPath(MinecraftServer server) {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
    }

    /**
     * 执行命令。1.20.1 返回真实结果码（>0 成功）；1.20.4+ 返回 void，统一返回 1（已执行）；方法不存在返回 0。
     */
    public static int executeCommand(MinecraftServer server, ServerCommandSource source, String command) {
        if (EXECUTE_WITH_PREFIX == null) return 0;
        Object r = invoke(EXECUTE_WITH_PREFIX, server.getCommandManager(), source, command);
        if (r instanceof Integer) return (Integer) r;
        return 1;
    }

    // ===================== 实体/世界 =====================

    /** 取实体坐标（Vec3d）。1.21.x 中 getPos 可能改名，靠签名反射兜底。 */
    public static net.minecraft.util.math.Vec3d getEntityPos(net.minecraft.entity.Entity entity) {
        Method m = findMethod(entity.getClass(), new String[]{"getPos"}, net.minecraft.util.math.Vec3d.class);
        return (net.minecraft.util.math.Vec3d) invoke(m, entity);
    }

    /** 取实体所在世界（World/ServerWorld）。 */
    public static net.minecraft.world.World getEntityWorld(net.minecraft.entity.Entity entity) {
        Method m = findMethod(entity.getClass(), new String[]{"getWorld", "getServerWorld"}, net.minecraft.world.World.class);
        return (net.minecraft.world.World) invoke(m, entity);
    }

    // ===================== 物品 =====================

    /**
     * 物品翻译键。优先 stack.getItem().getTranslationKey()（跨版本极其稳定），
     * 仅在 getItem 不可用时回退到 stack.getTranslationKey()。
     */
    public static String itemTranslationKey(ItemStack stack) {
        try {
            Item item = stack.getItem();
            Method m = findMethod(item.getClass(), new String[]{"getTranslationKey"}, String.class);
            if (m != null) {
                String r = (String) invoke(m, item);
                if (r != null) return r;
            }
        } catch (Exception ignore) {
        }
        return stack.getItem().toString();
    }

    /** NbtString 的字符串值；兼容 String(1.20.1) 与 Optional<String>(1.21+) 两种返回类型。 */
    @SuppressWarnings("unchecked")
    public static String nbtStringValue(NbtString s) {
        for (Method m : s.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() == 0) {
                Class<?> rt = m.getReturnType();
                if (rt.equals(String.class) || rt.equals(Optional.class)) {
                    m.setAccessible(true);
                    Object r = invoke(m, s);
                    if (r instanceof Optional) return ((Optional<String>) r).orElse("");
                    if (r instanceof String) return (String) r;
                }
            }
        }
        return "";
    }

    // ===================== 权限 / 命令源 =====================

    /** 判断命令源是否拥有指定权限等级（1.21.x 中 hasPermissionLevel 可能改名）。 */
    public static boolean hasPermissionLevel(ServerCommandSource source, int level) {
        Method m = findMethod(source.getClass(), new String[]{"hasPermissionLevel"}, boolean.class, int.class);
        if (m != null) {
            Object r = invoke(m, source, level);
            if (r instanceof Boolean) return (Boolean) r;
        }
        Method m2 = findMethod(source.getClass(), new String[]{"hasPermission"}, boolean.class, int.class);
        if (m2 != null) {
            Object r = invoke(m2, source, level);
            if (r instanceof Boolean) return (Boolean) r;
        }
        return level <= 0;
    }

    /** ServerCommandSource.withLevel(int)；1.21.x 中可能改名。 */
    public static ServerCommandSource withLevel(ServerCommandSource source, int level) {
        Method m = findMethod(source.getClass(), new String[]{"withLevel"}, ServerCommandSource.class, int.class);
        if (m != null) {
            Object r = invoke(m, source, level);
            if (r instanceof ServerCommandSource) return (ServerCommandSource) r;
        }
        return source;
    }

    // ===================== 玩家背包字段（private in 1.21+） =====================

    @SuppressWarnings("unchecked")
    public static List<ItemStack> getInvMain(PlayerInventory inv) {
        Field f = findField(inv.getClass(), new String[]{F_INV_MAIN, "main"});
        Object v = readField(f, inv);
        return v instanceof List ? (List<ItemStack>) v : java.util.Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public static List<ItemStack> getInvArmor(PlayerInventory inv) {
        Field f = findField(inv.getClass(), new String[]{F_INV_ARMOR, "armor"});
        Object v = readField(f, inv);
        return v instanceof List ? (List<ItemStack>) v : java.util.Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public static List<ItemStack> getInvOffHand(PlayerInventory inv) {
        Field f = findField(inv.getClass(), new String[]{F_INV_OFFHAND, "offHand"});
        Object v = readField(f, inv);
        return v instanceof List ? (List<ItemStack>) v : java.util.Collections.emptyList();
    }

    public static int getSelectedSlot(PlayerInventory inv) {
        Field f = findField(inv.getClass(), new String[]{F_INV_SELECTED_SLOT, "selectedSlot"});
        Object v = readField(f, inv);
        return v instanceof Integer ? (Integer) v : 0;
    }

    // ===================== 文本事件（HoverEvent / ClickEvent） =====================

    /** 创建「复制到剪贴板」ClickEvent。1.21+ 用静态工厂 copyToClipboard，旧版用构造器。 */
    public static ClickEvent clickEvent(String token) {
        Method m = findMethod(ClickEvent.class, new String[]{"copyToClipboard"}, ClickEvent.class, String.class);
        if (m != null) {
            Object r = invoke(m, null, token);
            if (r instanceof ClickEvent) return (ClickEvent) r;
        }
        try {
            Constructor<?> c = ClickEvent.class.getDeclaredConstructor(ClickEvent.Action.class, String.class);
            c.setAccessible(true);
            return (ClickEvent) c.newInstance(ClickEvent.Action.COPY_TO_CLIPBOARD, token);
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 创建展示文本的 HoverEvent。1.21+ 用静态工厂 showText，旧版用构造器。 */
    public static HoverEvent hoverEvent(Text text) {
        Method m = findMethod(HoverEvent.class, new String[]{"showText"}, HoverEvent.class, Text.class);
        if (m != null) {
            Object r = invoke(m, null, text);
            if (r instanceof HoverEvent) return (HoverEvent) r;
        }
        try {
            Constructor<?> c = HoverEvent.class.getDeclaredConstructor(HoverEvent.Action.class, Text.class);
            c.setAccessible(true);
            return (HoverEvent) c.newInstance(HoverEvent.Action.SHOW_TEXT, text);
        } catch (Exception ignore) {
            return null;
        }
    }
}
