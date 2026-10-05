package io.github.genichimaruo.worldgenassist.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.genichimaruo.worldgenassist.WorldgenAssist;
import io.github.genichimaruo.worldgenassist.server.FeatureFixture263;
import io.github.genichimaruo.worldgenassist.server.FixtureWeightedOrder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.common.MonsterRoomHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = MonsterRoomHooks.class, remap = false)
abstract class MonsterRoomFixtureMixin263 {
	@Shadow private static WeightedList<EntityType<?>> monsterRoomMobs;
	private static WeightedList<EntityType<?>> worldgenAssist$source, worldgenAssist$ordered;
	private static synchronized WeightedList<EntityType<?>> worldgenAssist$fixtureOrder() {
		if (worldgenAssist$source != monsterRoomMobs) {
			worldgenAssist$ordered = FixtureWeightedOrder.canonical(monsterRoomMobs,
				mob -> BuiltInRegistries.ENTITY_TYPE.getKey(mob).toString());
			worldgenAssist$source = monsterRoomMobs;
			WorldgenAssist.LOGGER.info("[CAWG] fixture.monster_room_order entries={} weights_preserved=true scoped_feature_only=true",
				worldgenAssist$ordered.unwrap().stream().map(entry -> BuiltInRegistries.ENTITY_TYPE.getKey(entry.value()) + ":" + entry.weight()).toList());
		}
		return worldgenAssist$ordered;
	}
	@WrapMethod(method = "getRandomMonsterRoomMob")
	private static EntityType<?> worldgenAssist$canonicalFixtureMob(RandomSource random, Operation<EntityType<?>> original) {
		if (!FeatureFixture263.inScopedFeature()) return original.call(random);
		return worldgenAssist$fixtureOrder().getRandomOrThrow(random);
	}
}
