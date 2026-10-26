package com.skyislands;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * "Recipe unlocked!" for this mod's recipes (the Wind Spears, the Prismarine Stick and Axe), like vanilla's: the first time a
 * player has one of a group's trigger items in their inventory, the group's recipes are unlocked (recipe book, and crafting
 * in worlds with limited crafting) and the player gets a popup in the top right of the screen plus the sound
 * {@code sky_islands:recipe_unlock} (assets/sky_islands/sounds/ui/recipe_unlock.ogg) instead of vanilla's. The popup is drawn
 * by the client class RecipeToastHud. The shaped recipes have "show_notification": false so vanilla's own popup stays away
 * (smithing recipes have no such switch, so the Netherite Wind Spear's recipe also shows vanilla's popup).
 */
public final class RecipeUnlocks {
	private static final Identifier SOUND_ID = SkyIslands.id("recipe_unlock");
	private static SoundEvent sound;

	/** Recipes (named like their result item) unlocked when {@code trigger} matches an item the player carries. */
	private record Group(Predicate<ItemStack> trigger, List<String> recipes) { }

	private static final List<Group> GROUPS = new ArrayList<>();

	private RecipeUnlocks() { }

	private static boolean isWindSpear(ItemStack stack) {
		Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return key.getNamespace().equals(SkyIslands.MOD_ID) && key.getPath().endsWith("_windspear");
	}

	public static void register() {
		sound = Registry.register(BuiltInRegistries.SOUND_EVENT, SOUND_ID, SoundEvent.createVariableRangeEvent(SOUND_ID));
		PayloadTypeRegistry.clientboundPlay().register(RecipeUnlockPayload.TYPE, RecipeUnlockPayload.CODEC);

		// like vanilla: the recipe appears as soon as you hold something you can craft it with
		GROUPS.add(new Group(s -> s.is(Items.PRISMARINE_SHARD), List.of("prismarine_stick")));
		GROUPS.add(new Group(s -> s.is(Items.PRISMARINE_SHARD) || s.is(Items.DARK_PRISMARINE) || s.is(PrismarineItems.PRISMARINE_STICK),
				List.of("prismarine_axe")));
		GROUPS.add(new Group(s -> s.is(Items.BREEZE_ROD) || isWindSpear(s),
				List.of("wooden_windspear", "stone_windspear", "copper_windspear", "iron_windspear", "golden_windspear", "diamond_windspear")));
		GROUPS.add(new Group(s -> s.is(Items.NETHERITE_INGOT) || BuiltInRegistries.ITEM.getKey(s.getItem()).equals(SkyIslands.id("diamond_windspear"))
				|| BuiltInRegistries.ITEM.getKey(s.getItem()).equals(SkyIslands.id("netherite_windspear")),
				List.of("netherite_windspear")));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 10 != 0) {
				return;
			}

			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				check(server, player);
			}
		});
	}

	private static void check(MinecraftServer server, ServerPlayer player) {
		List<RecipeHolder<?>> toAward = new ArrayList<>();
		List<String> shown = new ArrayList<>();

		for (Group group : GROUPS) {
			boolean triggered = false;

			for (int i = 0; i < player.getInventory().getContainerSize() && !triggered; i++) {
				ItemStack stack = player.getInventory().getItem(i);
				triggered = !stack.isEmpty() && group.trigger().test(stack);
			}

			if (!triggered) {
				continue;
			}

			for (String name : group.recipes()) {
				ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, SkyIslands.id(name));

				if (player.getRecipeBook().contains(key)) {
					continue;
				}

				Optional<RecipeHolder<?>> holder = server.getRecipeManager().byKey(key);

				if (holder.isPresent()) {
					toAward.add(holder.get());
					shown.add(SkyIslands.id(name).toString());
				}
			}
		}

		if (toAward.isEmpty()) {
			return;
		}

		player.awardRecipes(toAward);
		player.level().playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, 1.0F, 1.0F);
		ServerPlayNetworking.send(player, new RecipeUnlockPayload(shown));
	}
}
