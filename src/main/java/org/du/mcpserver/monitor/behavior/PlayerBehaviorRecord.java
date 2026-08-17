package org.du.mcpserver.monitor.behavior;

import java.util.List;

public class PlayerBehaviorRecord {

    public final long timestamp;
    public final String playerName;
    public final String playerUuid;

    // 1) 基础状态信息
    public final float health;
    public final float maxHealth;
    public final int foodLevel;
    public final float saturationLevel;

    // 2) 位置数据
    public final double posX;
    public final double posY;
    public final double posZ;
    public final float yaw;
    public final float pitch;
    public final String dimension;

    // 3) 移动属性
    public final double movementSpeed;
    public final boolean isMoving;
    public final boolean isOnGround;
    public final boolean isSprinting;
    public final boolean isSneaking;
    public final boolean isFlying;

    // 4) 交互信息
    public final String mainHandItemId;
    public final int mainHandItemCount;
    public final String offHandItemId;
    public final int offHandItemCount;
    public final String blockBelowId;

    // 5) 背包内容（按需记录，可为null表示该轮未采样背包）
    public final List<ItemEntry> inventorySnapshot;

    public PlayerBehaviorRecord(long timestamp, String playerName, String playerUuid,
                                float health, float maxHealth, int foodLevel, float saturationLevel,
                                double posX, double posY, double posZ, float yaw, float pitch, String dimension,
                                double movementSpeed, boolean isMoving, boolean isOnGround, boolean isSprinting, boolean isSneaking, boolean isFlying,
                                String mainHandItemId, int mainHandItemCount,
                                String offHandItemId, int offHandItemCount,
                                String blockBelowId,
                                List<ItemEntry> inventorySnapshot) {
        this.timestamp = timestamp;
        this.playerName = playerName;
        this.playerUuid = playerUuid;
        this.health = health;
        this.maxHealth = maxHealth;
        this.foodLevel = foodLevel;
        this.saturationLevel = saturationLevel;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.yaw = yaw;
        this.pitch = pitch;
        this.dimension = dimension;
        this.movementSpeed = movementSpeed;
        this.isMoving = isMoving;
        this.isOnGround = isOnGround;
        this.isSprinting = isSprinting;
        this.isSneaking = isSneaking;
        this.isFlying = isFlying;
        this.mainHandItemId = mainHandItemId;
        this.mainHandItemCount = mainHandItemCount;
        this.offHandItemId = offHandItemId;
        this.offHandItemCount = offHandItemCount;
        this.blockBelowId = blockBelowId;
        this.inventorySnapshot = inventorySnapshot;
    }
}
