package com.skyislands;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * The red "glowing obsidian": a Crying Obsidian copy (same strength, light and particles) with its own texture. It is a
 * real block with its own item: it is placed by the sky arena's template, mined with a diamond pickaxe or better
 * (data/minecraft/tags/block), drops itself (data/sky_islands/loot_table/blocks/glowing_obsidian.json), and can be placed
 * by players anywhere. It is the altar of the boss ritual (see ArenaBoss), which only works while the block sits in the Blackstone/Obsidian pattern ({@link #hasPattern}).
 */
public final class GlowingObsidian {
	public static Block BLOCK;
	public static Item ITEM;

	private GlowingObsidian() { }

	/** The altar pattern: Blackstone on the four corners and Obsidian on the four sides of the block, at the same height. */
	public static boolean hasPattern(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos centre) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}

				net.minecraft.world.level.block.state.BlockState state = level.getBlockState(centre.offset(dx, 0, dz));

				if (!(dx != 0 && dz != 0 ? state.is(Blocks.BLACKSTONE) : state.is(Blocks.OBSIDIAN))) {
					return false;
				}
			}
		}

		return true;
	}

	public static void register() {
		Identifier id = SkyIslands.id("glowing_obsidian");
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
		BLOCK = Registry.register(BuiltInRegistries.BLOCK, id,
				new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.CRYING_OBSIDIAN).setId(key)));

		Item.Properties properties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id));
		properties.component(DataComponents.ITEM_MODEL, id);
		properties.component(DataComponents.ITEM_NAME, Component.translatable("block.sky_islands.glowing_obsidian"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(BLOCK, properties));

		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.NATURAL_BLOCKS).register(output -> output.insertAfter(Items.CRYING_OBSIDIAN, ITEM));
	}
}
