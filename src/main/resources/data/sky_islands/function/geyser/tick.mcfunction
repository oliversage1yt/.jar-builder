# Cooldown so you do not relaunch the instant you land back near a geyser
execute as @a[scores={si_cd=1..}] run scoreboard players remove @s si_cd 1
execute as @a[tag=!si_launched] unless score @s si_cd matches 1.. at @s positioned ~-1 ~ ~-1 run function sky_islands:geyser/scan
execute as @a[tag=!si_launched] unless score @s si_cd matches 1.. at @s positioned ~-1 ~ ~0 run function sky_islands:geyser/scan
execute as @a[tag=!si_launched] unless score @s si_cd matches 1.. at @s positioned ~-1 ~ ~1 run function sky_islands:geyser/scan
execute as @a[tag=!si_launched] unless score @s si_cd matches 1.. at @s positioned ~0 ~ ~-1 run function sky_islands:geyser/scan
execute as @a[tag=!si_launched] unless score @s si_cd matches 1.. at @s positioned ~0 ~ ~0 run function sky_islands:geyser/scan
execute as @a[tag=!si_launched] unless score @s si_cd matches 1.. at @s positioned ~0 ~ ~1 run function sky_islands:geyser/scan
execute as @a[tag=!si_launched] unless score @s si_cd matches 1.. at @s positioned ~1 ~ ~-1 run function sky_islands:geyser/scan
execute as @a[tag=!si_launched] unless score @s si_cd matches 1.. at @s positioned ~1 ~ ~0 run function sky_islands:geyser/scan
execute as @a[tag=!si_launched] unless score @s si_cd matches 1.. at @s positioned ~1 ~ ~1 run function sky_islands:geyser/scan
execute as @a[tag=si_launched] at @s run function sky_islands:geyser/flying
# safety net: anyone not mid-launch never keeps our attribute changes
execute as @a[tag=!si_launched] run attribute @s minecraft:gravity modifier remove sky_islands:geyser
execute as @a[tag=!si_launched] run attribute @s minecraft:fall_damage_multiplier modifier remove sky_islands:geyser
