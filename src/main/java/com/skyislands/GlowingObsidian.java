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
 * by players anywhere. It is the altar of the boss ritual (see ArenaBoss). There is no pattern conversion any more.
 */
public final class GlowingObsidian {
	public static Block BLOCK;
	public static Item ITEM;

	private GlowingObsidian() { }

	public static void register() {
		Identifier id = SkyIslands.id("glowing_obsidian");
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
		BLOCK = Registry.register(BuiltInRegistries.BLOCK, id,
				new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.CRYING_OBSIDIAN).setId(key)));

		Item.Properties properties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id));
		properties.component(DataComponents.ITEM_MODEL, id);
		properties.component(DataComponents.ITEM_NAME, Component.translatable("block.sky_islands.glowing_obsidian"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(BLOCK, properties));

		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS).register(output -> output.insertAfter(Items.CRYING_OBSIDIAN, ITEM));
	}
}
