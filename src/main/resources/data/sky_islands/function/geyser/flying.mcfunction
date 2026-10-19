scoreboard players add @s si_fly 1
# Phase 0: still in the pool -> strong buoyancy to pop out of the water
execute if score @s si_t matches 0 if block ~ ~ ~ minecraft:water run attribute @s minecraft:gravity modifier remove sky_islands:geyser
execute if score @s si_t matches 0 if block ~ ~ ~ minecraft:water run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.88 add_value
# Out of the water: the flight clock (si_t) starts counting
execute unless block ~ ~ ~ minecraft:water run scoreboard players add @s si_t 1
execute if score @s si_t matches 1.. run attribute @s minecraft:gravity modifier remove sky_islands:geyser
# Gravity curve: value = (wanted total gravity) - 0.08 (vanilla base). Two ticks per step.
execute if score @s si_t matches 1..2 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.0801 add_value
execute if score @s si_t matches 3..4 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.0804 add_value
execute if score @s si_t matches 5..6 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.081 add_value
execute if score @s si_t matches 7..8 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.0819 add_value
execute if score @s si_t matches 9..10 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.0833 add_value
execute if score @s si_t matches 11..12 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.085 add_value
execute if score @s si_t matches 13..14 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.0872 add_value
execute if score @s si_t matches 15..16 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.09 add_value
execute if score @s si_t matches 17..18 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.0932 add_value
execute if score @s si_t matches 19..20 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.0971 add_value
execute if score @s si_t matches 21..22 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1015 add_value
execute if score @s si_t matches 23..24 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1067 add_value
execute if score @s si_t matches 25..26 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1125 add_value
execute if score @s si_t matches 27..28 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1191 add_value
execute if score @s si_t matches 29..30 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1265 add_value
execute if score @s si_t matches 31..32 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1348 add_value
execute if score @s si_t matches 33..34 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1439 add_value
execute if score @s si_t matches 35..36 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1539 add_value
execute if score @s si_t matches 37..38 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1649 add_value
execute if score @s si_t matches 39..40 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1768 add_value
execute if score @s si_t matches 41..42 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 43..44 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 45..46 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 47..48 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 49..50 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 51..52 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 53..54 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 55..56 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 57..58 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 59..60 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 61..62 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 63..64 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 65..66 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 67..68 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 69..70 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 71..72 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 73..74 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 75..76 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 77..78 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 79..80 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 81..82 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 83..84 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 85..86 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 87..88 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 89..90 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 91..92 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 93..94 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 95..96 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 97..98 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 99..100 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 101..102 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 103..104 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 105..106 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 107..108 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 109..110 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 111..112 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 113..114 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 115..116 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 117..118 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 119..120 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 121..122 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 123..124 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 125..126 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 127..128 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 129..130 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.18 add_value
execute if score @s si_t matches 131..132 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.179 add_value
execute if score @s si_t matches 133..134 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.175 add_value
execute if score @s si_t matches 135..136 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1681 add_value
execute if score @s si_t matches 137..138 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1587 add_value
execute if score @s si_t matches 139..140 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1474 add_value
execute if score @s si_t matches 141..142 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1345 add_value
execute if score @s si_t matches 143..144 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1203 add_value
execute if score @s si_t matches 145..146 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.1054 add_value
execute if score @s si_t matches 147..148 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.09 add_value
execute if score @s si_t matches 149..150 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.0746 add_value
execute if score @s si_t matches 151..152 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.0597 add_value
execute if score @s si_t matches 153..154 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.0455 add_value
execute if score @s si_t matches 155..156 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.0326 add_value
execute if score @s si_t matches 157..158 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.0213 add_value
execute if score @s si_t matches 159..160 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.0119 add_value
execute if score @s si_t matches 161..162 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.005 add_value
execute if score @s si_t matches 163..164 run attribute @s minecraft:gravity modifier add sky_islands:geyser -0.001 add_value
# Landing / safety timeouts
execute if score @s si_t matches 20.. if entity @s[nbt={OnGround:1b}] run function sky_islands:geyser/land
execute if score @s si_t matches 20.. if block ~ ~ ~ minecraft:water run function sky_islands:geyser/land
execute if score @s si_t matches 0 if score @s si_fly matches 100.. run function sky_islands:geyser/land
execute if score @s si_fly matches 1800.. run function sky_islands:geyser/land
