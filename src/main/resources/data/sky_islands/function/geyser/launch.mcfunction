# Gravity is bent along a curve (see flying.mcfunction); fall damage is switched off until you land.
attribute @s minecraft:fall_damage_multiplier modifier remove sky_islands:geyser
attribute @s minecraft:fall_damage_multiplier modifier add sky_islands:geyser -1 add_multiplied_total
tag @s add si_launched
scoreboard players set @s si_fly 0
scoreboard players set @s si_t 0
playsound minecraft:entity.generic.splash player @a ~ ~ ~ 1.5 0.6
