# Sky Islands {Islands}

Floating sky islands, archipelagos, towers, ruins and surface geyser launchers for Minecraft **26.3** (Fabric).
A port of *Sky Lands* (DeepCanionStudio, MIT) that uses **only vanilla blocks, items and entities**.

**Requires:** Minecraft 26.3, Fabric Loader 0.19.5+, [Fabric API](https://modrinth.com/mod/fabric-api) (0.163.0+26.3 or newer), Java 25.

## Building it on GitHub (no local setup)

1. Create a new **empty** GitHub repository.
2. Put this project in it so that `build.gradle`, `settings.gradle`, `src/` and the `.github` folder are at the **top level**
   of the repository (not inside an extra folder). The project has about 570 files, and GitHub's web "upload files"
   page only accepts 100 at a time, so use one of these:
   * **GitHub Desktop** (recommended): clone your new repo, copy this project's *contents* into it, then *Commit* and *Push*.
     Make sure the `.github` folder is included (it starts with a dot, so some tools skip it).
   * or the command line, from inside this folder:
     ```
     git init
     git add .
     git commit -m "Sky Islands"
     git branch -M main
     git remote add origin https://github.com/YOUR-NAME/YOUR-REPO.git
     git push -u origin main
     ```
3. Open the repo's **Actions** tab. The `build` workflow (`.github/workflows/build.yml`) starts by itself on every push (about 2-4 minutes). It installs Gradle itself, so it works even if the Gradle wrapper jar did not upload.
4. When it is green, open the run, scroll to **Artifacts**, and download `Artifacts`. Inside is `sky-islands-2.9.1.jar`
   (ignore the `-sources` jar). Put that jar in your `mods` folder next to Fabric API.

If the build is **red**, open the failed step, copy the first 20 or so lines containing `error:` and send them over;
each one points to an exact file and line.

## What is in it

| Feature | How it works |
| --- | --- |
| Islands, archipelagos, castles, towers, arenas, village | Data pack in `src/main/resources/data` (jigsaw structures + templates). Islands now spawn in **every** overworld biome. |
| Autumn (poplar) islands | Same data pack: poplar wood, orange/red/yellow poplar leaves, red sand, red shrubs, rose bushes. |
| Geyser launchers | Data pack functions (`function/geyser`). Standing near one makes your gravity negative along a curve (about +430 blocks) and switches off fall damage until you land. |
| **Snow and ice under islands** | `GroundFreezer`: after a chunk generates, columns with an island far above the real ground get the snow layer / ice vanilla would have placed at ground level (vanilla puts it on the island's top instead, leaving bare patches and unfrozen rivers below). |
| **No snow on islands in mild biomes** | `BiomeAltitudeMixin`: above y=165 the temperature used for snow/ice ignores altitude. Biomes that are freezing at sea level (snowy plains, frozen ocean, snowy taiga...) still snow, so glaciated islands keep their snow. |
| **Fading music** | `MusicController` (client). Skyborn starts when a geyser launches you and fades out when you land. Sky tracks (now five: Swaying Islands, An Island's Peace, Breathtaking View, Island's Sorrow, Singing Islands) fade in above y=200 and fade out below y=185, with a gap between tracks and never the same track twice in a row. Vanilla music fades out as ours fades in, and stays silent meanwhile. |
| **Clouds** | `CloudSpawner` + `CloudParticle` (client). A port of the original mod's method: huge camera-facing translucent particles spawned in bursts near you, drifting, fading in and out. |
| **Sky light through islands** | `ChunkSkyLightSourcesMixin`: the sky-light "lowest source" height of a column that has an island above a big air gap is found under the island, so the ground below is no longer in shadow. Built from the real 26.3 light engine source. Trade-off: the inside of island buildings counts as open sky and is fully lit. Existing chunks keep their old light until they are relit. |
| **Maps** | `MapItemMixin`: a map remembers (in its custom_data) whether it was first used in the sky. Maps made below the islands ignore them and show the ground; sky maps show only the islands and solid sky blue elsewhere. |
| **Fluids** | `FlowingFluidMixin`: island water/lava cannot flow out of the island. "Generated" means inside the island's structure area, so blocks a player adds *inside* an island are treated as part of it; anything placed in open sky behaves normally. (The falling-block cleanup was removed in 2.6.0 together with the sand islands.) |
| **Deserts** | The sand/desert islands are gone. Desert, badlands and savanna biomes now use the temperate (golden) islands, arenas and structures, and golden foliage tint applies over them too. |
| **Music toasts** | `MusicToast` (client): the now-playing box for every Sky Islands song (the five sky tracks, Skyborn and the boss theme), shown according to the vanilla *Music Toast* setting: Never = nothing, Pause Screen = only while the pause menu is open, Pause Screen and Toast = also slides in from the left edge for about 5 seconds when a song starts. The box is the mod's own 160x32 9-slice texture and the icon is a hand-drawn cloud that bobs 2 pixels every half second; both are baked into the code as pixel rectangles, so there is no texture file to load. |
| **Boss music** | `MusicController` (client): while a Sky Illusioner is within 64 blocks, *Battle amongst the Clouds* fades in quickly (looping) while sky music, the geyser song and vanilla music fade out. When the boss is dead it fades out and normal music can play again. |
| **Golden foliage** | `GoldenFoliageTint` (client): only on floating islands (a tall air gap below the block), not on mountains. Leaves, grass, grass blocks and ferns on islands over plains, forests, taiga, rivers and cooler oceans are golden yellow. Desert, badlands and savanna are golden too; jungles, swamps, warm oceans and snowy biomes keep their own colours. Those islands also have gold ore instead of emerald ore (data). |
| **Orange grass** | `AutumnGrassTint` (client). Grass high up and near any autumn block (poplar, fallen leaves, red shrubs, red sand, roses) is tinted dappled-forest orange (`#df6827`), whatever biome the island floats over. |
| **Sky arena boss** | `ArenaBoss` (server) + `worldgen/template_pool/skyarena_fight.json`. The temperate sky arena is two structures that share one structure set (50/50): `sky_islands:skyarena_chest` (plain tuff centre, no lanterns or soul fire, keeps its chest) and `sky_islands:skyarena_fight` (the boss arena), whose processor always swaps the chest for an invisible `barrier` marker. The marker puffs black smoke; a player within 5 blocks removes it, a spawn sound plays and a Sky Illusioner (vanilla Illusioner, 200 health, boss bar) appears. If it falls off the arena it is teleported back to the centre. The fight has three phases: above 2/3 health it holds a diamond axe and runs at you at half speed (no mirror image spell, visible axe), swinging about once a second like a Vindicator (9 damage, and a 25% chance to disable a raised shield for 5 seconds); if you stay out of its reach for 2 seconds (e.g. you pillar up) it switches to a bow that fires 1.5x faster than normal until it can reach you again. Reachability is sampled every 4 ticks and smoothed (unreachable adds time, reachable removes it twice as fast), and the boss keeps its last player as target when line of sight is lost, so the timer no longer restarts by accident; from 2/3 to 1/3 health it shoots arrows with its bow, and if it cannot see its target for 2 seconds (same smoothing as the reach timer: unseen adds 1 per tick, seen removes 2) it **rains 10-20 arrows** (one per tick) on every player within 40 blocks, landing within 3 blocks of each player and starting up to 12 blocks above them (a ceiling stops the rain), so hiding or pillaring out of sight does not work; below 1/3 it drops the bow and attacks with evoker fangs (rings when you are close, a long line otherwise, about every 1.2 seconds) and, every 10-15 seconds (the first after 6), sends a **wind gust**: a charging sound and wind particles at the feet of every player within 24 blocks for 1 second, then a real `wind_charge` bursts on the ground at their feet (owned by the boss) and the vanilla explosion throws them up (`WIND_CHARGES_PER_PLAYER`, 1 by default; more charges, more height). Vanilla explosion launches also flag the player to ignore the fall damage of that flight, so the flag is cleared every tick while they are airborne (reflection on `Player`, looked up by name; the log says whether it was found) and the landing hurts normally. Drops 5-10 diamonds, 3-8 iron ingots, 5-12 gold ingots and (90%) a random enchanted book (level is the maximum or one below it; single-level enchantments are level 1), rolled in code (`onDeath`). The boss is immune to fire and lava (permanent hidden fire resistance), because the arena has soul fire. Its skin is `src/client/resources/assets/minecraft/textures/entity/illager/illusioner.png`, which replaces the vanilla Illusioner texture (the boss is the only Illusioner in this mod). The fight arena's floor centre is a 3x3 pattern (Blackstone corners, Obsidian sides, Crying Obsidian in the middle); `GlowingObsidian` turns the middle block into the red `sky_islands:glowing_obsidian` (a Crying Obsidian copy with its own texture and the same drops) and back again if the pattern is broken, for any such pattern near a player. **Respawn:** right-click the glowing obsidian with a plain Book (not an Enchanted Book, which the boss drops) while the boss is gone. The book is used up (not in creative) and floats above the altar, spinning and rising slowly while enchanting-table glyphs stream in from around the arena; then it shoots up with a trail and the boss re-appears at full health. While the book rises the **lower part of the arena is reset** (`ArenaReset`): everything from the bottom of the arena up to 5 blocks above the altar is put back as it generated, bottom to top, so blocks players placed there vanish and blocks they broke come back; the columns directly above the middle of the pit (the opening in the roof ring, found by flood fill from the altar over columns with nothing of the template above them) are cleared to air up to 30 blocks above the altar (`TALL_TOP_ABOVE_ALTAR`) (a placed chest is broken so its items drop; players standing where a block returns are lifted out of it). The rest of the upper part (the roof ring and towers, 6+ blocks above the altar) keeps whatever players placed or broke. The reference layout is read from `skyarena_fight.nbt` in the mod's resources, and the arena's random rotation is worked out by matching it against the world, so an altar that players built elsewhere (no arena around it) is left alone; turn it off with `arenaResetOnRespawn=false` in `config/sky_islands.properties`. The ritual does nothing while the boss is alive or waiting, and on Peaceful. Does not wake on Peaceful. The soul soil is set into the floor with the soul fire in the block above it. The chest/fight split is the two `weight` values in `worldgen/structure_set/skyarena.json`; to add the boss to the other arenas (arid, lavic, ice) copy the `rule` processor from `skyarena_fight.json` into their pools. |

## Settings (no rebuild needed)

On first launch the game writes `config/sky_islands.properties`. Edit it with the game closed. Highlights:

* `musicEnabled`, `skyMusicEnterY` (200), `skyMusicExitY` (185), `skyFadeInTicks`, `skyFadeOutTicks`, `skyMusicGapTicks`,
  `launchFadeInTicks`, `launchFadeOutTicks`, `bossFadeInTicks` (30), `bossFadeOutTicks` (60) (20 ticks = 1 second)
* `cloudsEnabled`, `cloudSpawnChance` (lower = fewer clouds, faster game), `cloudsPerBurst`, `cloudSpread`,
  `cloudBaseY`, `cloudHeightVariation`
* `orangeGrass`, `orangeGrassMinY`, `goldenFoliage`, `goldenFoliageMinY`
* `arenaBossHealth` (200), `arenaResetOnRespawn` (true)
* `lightThroughIslands`, `skyMaps`, `naturalFluidsContained` (server side; turn any off to get vanilla behaviour back)

The music files are `src/client/resources/assets/sky_islands/sounds/music/*.ogg`. To swap a track, replace the file
with another **Ogg Vorbis** file of the same name (Opus-in-Ogg will not play) and rebuild. Track names are listed in
`MusicController.java` and `sounds.json`.

## Spots most likely to need a tweak (written without being able to compile)

The code was checked against Fabric API 26.3's own source and published mapping data, but not compiled or run.
If the build fails, the likely candidates are small name differences in:

* `FadingSound`: the protected fields `volume`, `looping`, `relative`, `attenuation`.
* `CloudParticle`: `scale`, `setSize`, `setLifetime`, `setAlpha`, and the fields `friction`, `gravity`, `hasPhysics`, `xd/yd/zd`.
* `AutumnGrassTint`: `Blocks.SHORT_GRASS`, `BlockAndTintGetter.getBlockState`.
* `MusicController`: `player.getAttributeValue(Attributes.GRAVITY)`, `SoundManager.isActive/play`.
* `MapItemMixin` / `ChunkSkyLightSourcesMixin` / `FlowingFluidMixin`: written against the real 26.3 source (signatures checked), but not run. If one fails to apply, the log says `Mixin apply failed`; the rest of the mod still works.
* `GroundFreezer`: `ServerChunkCache.getGenerator()/randomState()`, `ChunkGenerator.getBaseHeight(...)`, `ServerLevel.getBiome`.

The `BiomeAltitudeMixin`, `VanillaMusic` and the biome-temperature lookup in `GroundFreezer` are written so that a wrong name cannot break the game: they just log a
warning and that one feature stays off. Search `latest.log` for `Sky Islands` or `Mixin` to see.

## License

MIT. Based on *Sky Lands* by DeepCanionStudio (MIT).
