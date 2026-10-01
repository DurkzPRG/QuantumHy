package com.durkz.quantumhy.pressure;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventItemMerging;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * While a world is under pressure, widens the radius dropped items merge at so loose piles
 * collapse into fewer entities. Once pressure ends, items we widened go back to their normal
 * radius ({@code setMergeRadius(-1)} clears the override and the engine refetches it from config).
 *
 * The override lives only in memory ({@code ItemComponent} does not save it), so a restart or a
 * chunk reload also returns items to normal. Items with {@link PreventItemMerging} are skipped, the
 * same way the engine's merge system skips them.
 */
public final class ItemMergeBoostSystem extends DelayedEntitySystem<EntityStore> {

    private final Supplier<QuantumHyConfig> settings;
    private final ComponentType<EntityStore, ItemComponent> itemType;
    private final Query<EntityStore> query;

    public ItemMergeBoostSystem(@Nonnull Supplier<QuantumHyConfig> settings) {
        super(1.0F);
        this.settings = settings;
        this.itemType = ItemComponent.getComponentType();
        this.query = Query.and(itemType, Query.not(PreventItemMerging.getComponentType()));
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        ItemComponent item = archetypeChunk.getComponent(index, itemType);
        if (item == null || item.getItemStack() == null) {
            return;
        }
        QuantumHyConfig config = settings.get();
        World world = store.getExternalData().getWorld();
        boolean pressured = config.enabled && config.pressureItemMerge && world != null
                && PressureGovernor.isPressured(world.getWorldConfig().getUuid());
        float boost = (float) config.pressureItemMergeRadius;
        float current = item.getMergeRadius(store);
        float next = nextRadius(pressured, current, boost);
        if (next != current) {
            item.setMergeRadius(next);
        }
    }

    /**
     * Radius to write: widen to {@code boost} under pressure when the item merges closer than that,
     * clear our override ({@code -1}) once pressure ends. Items whose own radius is already wider,
     * or that some other code set to a different value, are left alone.
     */
    static float nextRadius(boolean pressured, float current, float boost) {
        if (pressured) {
            return current < boost ? boost : current;
        }
        return current == boost ? -1.0F : current;
    }
}
