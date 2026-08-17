package org.du.mcpserver.monitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.du.mcpserver.util.MCCompat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

public class PlayerInfoManager {

    private static final Logger LOGGER = LogManager.getLogger("MCPServer");

    private final MinecraftServer server;

    public PlayerInfoManager(MinecraftServer server) {
        this.server = server;
    }

    public JsonObject getAllPlayersInfo() {
        JsonObject result = new JsonObject();
        JsonArray players = new JsonArray();

        if (server == null || !server.isRunning()) {
            result.addProperty("error", "服务器未运行");
            return result;
        }

        Collection<ServerPlayerEntity> onlinePlayers = server.getPlayerManager().getPlayerList();

        for (ServerPlayerEntity player : onlinePlayers) {
            players.add(getPlayerInfo(player));
        }

        result.add("players", players);
        result.addProperty("total", players.size());
        return result;
    }

    public JsonObject getPlayerInfo(String playerName) {
        if (server == null || !server.isRunning()) {
            JsonObject result = new JsonObject();
            result.addProperty("error", "服务器未运行");
            return result;
        }

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);

        if (player == null) {
            JsonObject result = new JsonObject();
            result.addProperty("error", "玩家不存在或未在线");
            return result;
        }

        return getPlayerInfo(player);
    }

    private JsonObject getPlayerInfo(ServerPlayerEntity player) {
        JsonObject playerInfo = new JsonObject();

        playerInfo.addProperty("name", player.getName().getString());
        playerInfo.addProperty("uuid", player.getUuidAsString());
        playerInfo.addProperty("displayName", player.getDisplayName().getString());
        playerInfo.addProperty("isOnline", true);

        Vec3d pos = MCCompat.getEntityPos(player);
        JsonObject position = new JsonObject();
        position.addProperty("x", pos.x);
        position.addProperty("y", pos.y);
        position.addProperty("z", pos.z);

        ServerWorld world = (ServerWorld) MCCompat.getEntityWorld(player);
        position.addProperty("dimension", getDimensionName(world));
        playerInfo.add("position", position);

        BlockPos blockPos = player.getBlockPos();
        JsonObject biome = getBiomeInfo(world, blockPos);
        playerInfo.add("biome", biome);

        JsonObject gameMode = new JsonObject();
        try {
            Object gameModeObj = player.interactionManager.getGameMode();
            Method getNameMethod = gameModeObj.getClass().getMethod("getName");
            gameMode.addProperty("name", (String) getNameMethod.invoke(gameModeObj));
            Method getIdMethod = gameModeObj.getClass().getMethod("getId");
            gameMode.addProperty("id", (Integer) getIdMethod.invoke(gameModeObj));
        } catch (Exception e) {
            gameMode.addProperty("name", "unknown");
            gameMode.addProperty("id", 0);
        }
        playerInfo.add("gameMode", gameMode);

        JsonObject stats = new JsonObject();
        stats.addProperty("health", player.getHealth());
        stats.addProperty("maxHealth", player.getMaxHealth());
        stats.addProperty("healthPercent", (player.getHealth() / player.getMaxHealth()) * 100);
        stats.addProperty("hunger", player.getHungerManager().getFoodLevel());
        stats.addProperty("saturation", player.getHungerManager().getSaturationLevel());
        stats.addProperty("experienceLevel", player.experienceLevel);
        stats.addProperty("experienceProgress", player.experienceProgress);
        stats.addProperty("totalExperience", player.totalExperience);
        stats.addProperty("level", player.experienceLevel);
        stats.addProperty("flySpeed", player.getAbilities().getFlySpeed());
        stats.addProperty("walkSpeed", player.getAbilities().getWalkSpeed());
        stats.addProperty("canFly", player.getAbilities().allowFlying);
        stats.addProperty("isFlying", player.getAbilities().flying);
        stats.addProperty("isCreative", player.isCreative());
        stats.addProperty("isSpectator", player.isSpectator());
        playerInfo.add("stats", stats);

        playerInfo.add("inventory", getPlayerInventory(player.getInventory()));
        playerInfo.add("equipment", getPlayerEquipment(player));

        JsonObject advancements = new JsonObject();
        try {
            Object tracker = player.getAdvancementTracker();
            Method getCompletedMethod = tracker.getClass().getMethod("getCompletedAdvancements");
            Object completed = getCompletedMethod.invoke(tracker);
            Method sizeMethod = completed.getClass().getMethod("size");
            advancements.addProperty("completed", (Integer) sizeMethod.invoke(completed));
        } catch (Exception e) {
            advancements.addProperty("completed", 0);
        }
        playerInfo.add("advancements", advancements);

        JsonObject misc = new JsonObject();
        misc.addProperty("isSleeping", player.isSleeping());
        misc.addProperty("isDead", player.isDead());
        misc.addProperty("isSilent", player.isSilent());
        misc.addProperty("fallDistance", player.fallDistance);
        misc.addProperty("air", player.getAir());
        misc.addProperty("maxAir", player.getMaxAir());

        try {
            Field lastDeathTimeField = ServerPlayerEntity.class.getDeclaredField("lastDeathTime");
            lastDeathTimeField.setAccessible(true);
            misc.addProperty("lastDeathTime", lastDeathTimeField.getLong(player));
        } catch (Exception e) {
            misc.addProperty("lastDeathTime", 0);
        }

        try {
            Field sleepTimerField = PlayerEntity.class.getDeclaredField("sleepTimer");
            sleepTimerField.setAccessible(true);
            misc.addProperty("sleepTimer", sleepTimerField.getInt(player));
        } catch (Exception e) {
            misc.addProperty("sleepTimer", 0);
        }

        playerInfo.add("misc", misc);

        return playerInfo;
    }

    private JsonObject getBiomeInfo(ServerWorld world, BlockPos pos) {
        JsonObject biome = new JsonObject();

        try {
            Biome biomeObj = world.getBiome(pos).value();

            String biomeName = "unknown";
            try {
                Method getRegistryMethod = world.getClass().getMethod("getRegistryManager");
                Object registryManager = getRegistryMethod.invoke(world);
                Method getRegistryMethod2 = registryManager.getClass().getMethod("getRegistry", net.minecraft.registry.RegistryKey.class);

                Object biomeKey = null;
                try {
                    Class<?> registryKeysClass = Class.forName("net.minecraft.registry.RegistryKeys");
                    java.lang.reflect.Field biomeKeyField = registryKeysClass.getDeclaredField("BIOME");
                    biomeKey = biomeKeyField.get(null);
                } catch (Exception e) {
                    try {
                        java.lang.reflect.Field biomeKeyField = net.minecraft.registry.Registry.class.getDeclaredField("BIOME_KEY");
                        biomeKeyField.setAccessible(true);
                        biomeKey = biomeKeyField.get(null);
                    } catch (Exception e2) {
                    }
                }

                if (biomeKey != null) {
                    Object biomeRegistry = getRegistryMethod2.invoke(registryManager, biomeKey);
                    Method getIdMethod = biomeRegistry.getClass().getMethod("getId", Object.class);
                    Identifier biomeId = (Identifier) getIdMethod.invoke(biomeRegistry, biomeObj);
                    if (biomeId != null) {
                        biomeName = biomeId.toString();
                    }
                }
            } catch (Exception e) {
                try {
                    String toString = biomeObj.toString();
                    if (toString.contains("minecraft:")) {
                        biomeName = toString;
                    } else {
                        int idx = toString.lastIndexOf('.');
                        if (idx > 0) {
                            biomeName = "minecraft:" + toString.substring(idx + 1).toLowerCase();
                        }
                    }
                } catch (Exception e2) {
                    biomeName = "unknown";
                }
            }
            biome.addProperty("name", biomeName);

            try {
                Method getCategoryMethod = biomeObj.getClass().getMethod("getCategory");
                Object category = getCategoryMethod.invoke(biomeObj);
                Method getNameMethod = category.getClass().getMethod("getName");
                biome.addProperty("category", (String) getNameMethod.invoke(category));
            } catch (Exception e) {
                biome.addProperty("category", "unknown");
            }

            try {
                Method getTemperatureMethod = biomeObj.getClass().getMethod("getTemperature");
                biome.addProperty("temperature", (Double) getTemperatureMethod.invoke(biomeObj));
            } catch (Exception e) {
                try {
                    java.lang.reflect.Field tempField = biomeObj.getClass().getDeclaredField("temperature");
                    tempField.setAccessible(true);
                    biome.addProperty("temperature", (Double) tempField.get(biomeObj));
                } catch (Exception e2) {
                    biome.addProperty("temperature", 0.0);
                }
            }

            try {
                Method getDownfallMethod = biomeObj.getClass().getMethod("getDownfall");
                biome.addProperty("downfall", (Double) getDownfallMethod.invoke(biomeObj));
            } catch (Exception e) {
                try {
                    java.lang.reflect.Field downfallField = biomeObj.getClass().getDeclaredField("downfall");
                    downfallField.setAccessible(true);
                    biome.addProperty("downfall", (Double) downfallField.get(biomeObj));
                } catch (Exception e2) {
                    biome.addProperty("downfall", 0.0);
                }
            }

            try {
                Method getDepthMethod = biomeObj.getClass().getMethod("getDepth");
                biome.addProperty("depth", (Double) getDepthMethod.invoke(biomeObj));
            } catch (Exception e) {
                try {
                    java.lang.reflect.Field depthField = biomeObj.getClass().getDeclaredField("depth");
                    depthField.setAccessible(true);
                    biome.addProperty("depth", (Double) depthField.get(biomeObj));
                } catch (Exception e2) {
                    biome.addProperty("depth", 0.0);
                }
            }

            try {
                Method getScaleMethod = biomeObj.getClass().getMethod("getScale");
                biome.addProperty("scale", (Double) getScaleMethod.invoke(biomeObj));
            } catch (Exception e) {
                try {
                    java.lang.reflect.Field scaleField = biomeObj.getClass().getDeclaredField("scale");
                    scaleField.setAccessible(true);
                    biome.addProperty("scale", (Double) scaleField.get(biomeObj));
                } catch (Exception e2) {
                    biome.addProperty("scale", 0.0);
                }
            }

        } catch (Exception e) {
            LOGGER.warn("Failed to get biome info: {}", e.getMessage());
            biome.addProperty("name", "unknown");
        }

        return biome;
    }

    private String getDimensionName(ServerWorld world) {
        Identifier dimId = world.getRegistryKey().getValue();
        if (dimId != null) {
            String name = dimId.toString();
            return switch (name) {
                case "minecraft:overworld" -> "主世界 (Overworld)";
                case "minecraft:the_nether" -> "下界 (Nether)";
                case "minecraft:the_end" -> "末地 (End)";
                default -> name;
            };
        }
        return "unknown";
    }

    private JsonObject getPlayerInventory(PlayerInventory inventory) {
        JsonObject result = new JsonObject();

        JsonArray mainInventory = new JsonArray();
        List<ItemStack> mainInv = MCCompat.getInvMain(inventory);
        for (int i = 0; i < mainInv.size(); i++) {
            ItemStack stack = mainInv.get(i);
            if (!stack.isEmpty()) {
                mainInventory.add(createItemStackJson(stack, i));
            }
        }
        result.add("main", mainInventory);

        JsonArray hotbar = new JsonArray();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) {
                JsonObject item = createItemStackJson(stack, i);
                item.addProperty("slot", i);
                hotbar.add(item);
            }
        }
        result.add("hotbar", hotbar);

        JsonArray armor = new JsonArray();
        List<ItemStack> armorInv = MCCompat.getInvArmor(inventory);
        for (int i = 0; i < armorInv.size(); i++) {
            ItemStack stack = armorInv.get(i);
            if (!stack.isEmpty()) {
                armor.add(createItemStackJson(stack, i + 36));
            }
        }
        result.add("armor", armor);

        JsonArray offhand = new JsonArray();
        ItemStack offhandStack = MCCompat.getInvOffHand(inventory).get(0);
        if (!offhandStack.isEmpty()) {
            offhand.add(createItemStackJson(offhandStack, 45));
        }
        result.add("offhand", offhand);

        result.addProperty("totalItems", countNonEmptyItems(inventory));

        return result;
    }

    private JsonObject getPlayerEquipment(ServerPlayerEntity player) {
        JsonObject equipment = new JsonObject();

        equipment.add("helmet", createItemStackJson(player.getEquippedStack(EquipmentSlot.HEAD), 39));
        equipment.add("chestplate", createItemStackJson(player.getEquippedStack(EquipmentSlot.CHEST), 38));
        equipment.add("leggings", createItemStackJson(player.getEquippedStack(EquipmentSlot.LEGS), 37));
        equipment.add("boots", createItemStackJson(player.getEquippedStack(EquipmentSlot.FEET), 36));
        equipment.add("mainhand", createItemStackJson(player.getEquippedStack(EquipmentSlot.MAINHAND), MCCompat.getSelectedSlot(player.getInventory())));
        equipment.add("offhand", createItemStackJson(player.getEquippedStack(EquipmentSlot.OFFHAND), 45));

        return equipment;
    }

    private JsonObject createItemStackJson(ItemStack stack, int slot) {
        JsonObject item = new JsonObject();

        String itemId = getItemId(stack);
        item.addProperty("id", itemId);

        item.addProperty("name", stack.getName().getString());
        item.addProperty("count", stack.getCount());
        item.addProperty("maxCount", stack.getMaxCount());
        item.addProperty("slot", slot);

        if (stack.isDamageable()) {
            item.addProperty("damage", stack.getDamage());
            item.addProperty("maxDamage", stack.getMaxDamage());
            item.addProperty("damagePercent", ((stack.getMaxDamage() - stack.getDamage()) * 100.0) / stack.getMaxDamage());
        }

        net.minecraft.nbt.NbtCompound nbt = org.du.mcpserver.util.MCCompat.itemGetNbt(stack);
        if (nbt != null) {
            item.addProperty("hasNbt", true);
            try {
                item.addProperty("nbtSize", nbt.toString().length());
                JsonObject nbtJson = nbtToJson(nbt);
                item.add("nbt", nbtJson);
            } catch (Exception e) {
                item.addProperty("hasNbtData", true);
            }
        }

        item.addProperty("isEnchanted", stack.hasEnchantments());

        if (stack.hasEnchantments()) {
            JsonArray enchantments = getEnchantmentsJson(stack);
            item.add("enchantments", enchantments);
        }

        return item;
    }

    private String getItemId(ItemStack stack) {
        try {
            Object item = stack.getItem();
            Method getRegistryEntryMethod = item.getClass().getMethod("getRegistryEntry");
            Object registryEntry = getRegistryEntryMethod.invoke(item);
            Method getIdMethod = registryEntry.getClass().getMethod("getId");
            Identifier id = (Identifier) getIdMethod.invoke(registryEntry);
            return id != null ? id.toString() : "unknown";
        } catch (Exception e) {}

        try {
            Class<?> registriesClass = Class.forName("net.minecraft.registry.Registries");
            java.lang.reflect.Field itemRegistryField = registriesClass.getDeclaredField("ITEM");
            Object itemRegistry = itemRegistryField.get(null);
            Method getIdMethod = itemRegistry.getClass().getMethod("getId", Object.class);
            Identifier id = (Identifier) getIdMethod.invoke(itemRegistry, stack.getItem());
            return id != null ? id.toString() : "unknown";
        } catch (Exception e) {}

        try {
            Class<?> registryClass = Class.forName("net.minecraft.registry.Registry");
            java.lang.reflect.Field itemField = registryClass.getDeclaredField("ITEM");
            itemField.setAccessible(true);
            Object itemRegistry = itemField.get(null);
            Method getIdMethod = itemRegistry.getClass().getMethod("getId", Object.class);
            Identifier id = (Identifier) getIdMethod.invoke(itemRegistry, stack.getItem());
            return id != null ? id.toString() : "unknown";
        } catch (Exception e) {}

        try {
            Method getRegistryNameMethod = stack.getItem().getClass().getMethod("getRegistryName");
            Identifier id = (Identifier) getRegistryNameMethod.invoke(stack.getItem());
            return id != null ? id.toString() : "unknown";
        } catch (Exception e) {}

        try {
            String translationKey = MCCompat.itemTranslationKey(stack);
            if (translationKey != null && !translationKey.isEmpty()) {
                String[] parts = translationKey.split("\\.");
                if (parts.length >= 3) {
                    return parts[1] + ":" + parts[2];
                }
            }
        } catch (Exception e) {}

        try {
            String itemStr = stack.getItem().toString();
            if (itemStr.contains("minecraft:")) {
                return itemStr;
            }
        } catch (Exception e) {}

        return "unknown";
    }

    private JsonArray getEnchantmentsJson(ItemStack stack) {
        JsonArray enchantments = new JsonArray();

        try {
            Method getEnchantmentsMethod = stack.getClass().getMethod("getEnchantments");
            Object enchantmentList = getEnchantmentsMethod.invoke(stack);
            Method iteratorMethod = enchantmentList.getClass().getMethod("iterator");
            java.util.Iterator<?> iterator = (java.util.Iterator<?>) iteratorMethod.invoke(enchantmentList);

            while (iterator.hasNext()) {
                Object enchantment = iterator.next();
                JsonObject ench = new JsonObject();

                try {
                    Method getEnchantmentMethod = enchantment.getClass().getMethod("getEnchantment");
                    Object enchantmentObj = getEnchantmentMethod.invoke(enchantment);
                    String enchId = getEnchantmentId(enchantmentObj);
                    ench.addProperty("id", enchId);
                } catch (Exception e) {
                    ench.addProperty("id", "unknown");
                }

                try {
                    Method getLevelMethod = enchantment.getClass().getMethod("getLevel");
                    ench.addProperty("level", (Integer) getLevelMethod.invoke(enchantment));
                } catch (Exception e) {
                    ench.addProperty("level", 1);
                }

                enchantments.add(ench);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to get enchantments: {}", e.getMessage());
        }

        return enchantments;
    }

    private String getEnchantmentId(Object enchantment) {
        try {
            Method getRegistryEntryMethod = enchantment.getClass().getMethod("getRegistryEntry");
            Object registryEntry = getRegistryEntryMethod.invoke(enchantment);
            Method getIdMethod = registryEntry.getClass().getMethod("getId");
            Identifier id = (Identifier) getIdMethod.invoke(registryEntry);
            return id != null ? id.toString() : "unknown";
        } catch (Exception e) {}

        try {
            Class<?> registriesClass = Class.forName("net.minecraft.registry.Registries");
            java.lang.reflect.Field enchantmentField = registriesClass.getDeclaredField("ENCHANTMENT");
            Object enchantmentRegistry = enchantmentField.get(null);
            Method getIdMethod = enchantmentRegistry.getClass().getMethod("getId", Object.class);
            Identifier id = (Identifier) getIdMethod.invoke(enchantmentRegistry, enchantment);
            return id != null ? id.toString() : "unknown";
        } catch (Exception e) {}

        try {
            Method getRegistryNameMethod = enchantment.getClass().getMethod("getRegistryName");
            Identifier id = (Identifier) getRegistryNameMethod.invoke(enchantment);
            return id != null ? id.toString() : "unknown";
        } catch (Exception e) {}

        try {
            String enchStr = enchantment.toString();
            if (enchStr.contains("minecraft:")) {
                return enchStr;
            }
        } catch (Exception e) {}

        return "unknown";
    }

    private int countNonEmptyItems(PlayerInventory inventory) {
        int count = 0;
        for (ItemStack stack : MCCompat.getInvMain(inventory)) {
            if (!stack.isEmpty()) count++;
        }
        for (ItemStack stack : MCCompat.getInvArmor(inventory)) {
            if (!stack.isEmpty()) count++;
        }
        if (!MCCompat.getInvOffHand(inventory).get(0).isEmpty()) count++;
        return count;
    }

    private JsonObject nbtToJson(net.minecraft.nbt.NbtCompound nbt) {
        JsonObject json = new JsonObject();
        try {
            for (String key : nbt.getKeys()) {
                net.minecraft.nbt.NbtElement element = nbt.get(key);
                json.add(key, nbtElementToJson(element));
            }
        } catch (Exception e) {
            json.addProperty("error", "Failed to parse NBT: " + e.getMessage());
        }
        return json;
    }

    private com.google.gson.JsonElement nbtElementToJson(net.minecraft.nbt.NbtElement element) {
        if (element == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }

        try {
            switch (element.getType()) {
                case 1:
                    return new com.google.gson.JsonPrimitive(element instanceof net.minecraft.nbt.NbtByte ? ((net.minecraft.nbt.NbtByte) element).byteValue() : 0);
                case 2:
                    return new com.google.gson.JsonPrimitive(element instanceof net.minecraft.nbt.NbtShort ? ((net.minecraft.nbt.NbtShort) element).shortValue() : 0);
                case 3:
                    return new com.google.gson.JsonPrimitive(element instanceof net.minecraft.nbt.NbtInt ? ((net.minecraft.nbt.NbtInt) element).intValue() : 0);
                case 4:
                    return new com.google.gson.JsonPrimitive(element instanceof net.minecraft.nbt.NbtLong ? ((net.minecraft.nbt.NbtLong) element).longValue() : 0L);
                case 5:
                    return new com.google.gson.JsonPrimitive(element instanceof net.minecraft.nbt.NbtFloat ? ((net.minecraft.nbt.NbtFloat) element).floatValue() : 0.0f);
                case 6:
                    return new com.google.gson.JsonPrimitive(element instanceof net.minecraft.nbt.NbtDouble ? ((net.minecraft.nbt.NbtDouble) element).doubleValue() : 0.0);
                case 7:
                    if (element instanceof net.minecraft.nbt.NbtByteArray) {
                        JsonArray array = new JsonArray();
                        for (byte b : ((net.minecraft.nbt.NbtByteArray) element).getByteArray()) {
                            array.add(b);
                        }
                        return array;
                    }
                    break;
                case 8:
                    return new com.google.gson.JsonPrimitive(element instanceof net.minecraft.nbt.NbtString ? MCCompat.nbtStringValue((net.minecraft.nbt.NbtString) element) : "");
                case 9:
                    if (element instanceof net.minecraft.nbt.NbtList) {
                        JsonArray array = new JsonArray();
                        net.minecraft.nbt.NbtList list = (net.minecraft.nbt.NbtList) element;
                        for (int i = 0; i < list.size(); i++) {
                            array.add(nbtElementToJson(list.get(i)));
                        }
                        return array;
                    }
                    break;
                case 10:
                    if (element instanceof net.minecraft.nbt.NbtCompound) {
                        return nbtToJson((net.minecraft.nbt.NbtCompound) element);
                    }
                    break;
                case 11:
                    if (element instanceof net.minecraft.nbt.NbtIntArray) {
                        JsonArray array = new JsonArray();
                        for (int i : ((net.minecraft.nbt.NbtIntArray) element).getIntArray()) {
                            array.add(i);
                        }
                        return array;
                    }
                    break;
                case 12:
                    if (element instanceof net.minecraft.nbt.NbtLongArray) {
                        JsonArray array = new JsonArray();
                        for (long l : ((net.minecraft.nbt.NbtLongArray) element).getLongArray()) {
                            array.add(l);
                        }
                        return array;
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            return new com.google.gson.JsonPrimitive(element.toString());
        }

        return new com.google.gson.JsonPrimitive(element.toString());
    }
}