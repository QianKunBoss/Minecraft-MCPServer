package org.du.mcpserver.monitor.behavior;

public class ItemEntry {

    public final String itemId;
    public final int slot;
    public final int count;
    public final int damage;
    public final int maxDamage;

    public ItemEntry(String itemId, int slot, int count, int damage, int maxDamage) {
        this.itemId = itemId;
        this.slot = slot;
        this.count = count;
        this.damage = damage;
        this.maxDamage = maxDamage;
    }
}
