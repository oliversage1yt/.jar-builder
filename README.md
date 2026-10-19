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
4. When it is green, open the run, scroll to **Artifacts**, and download `Artifacts`. Inside is `sky-islands-2.0.0.jar`
   (ignore the `-sources` jar). Put that jar in your `mods` folder next to Fabric API.

If the build is **red**, open the failed step, copy the first 20 or so lines containing `error:` and send them over;
each one points to an exact file and line.

## What is in it

| Feature | How it works |
| --- | --- |
| Islands, archipelagos, castles, towers, arenas, village | Data pack in `src/main/resources/data` (jigsaw structures + templates). Islands now spawn in **every** overworld biome. |
| Autumn (poplar) islands | Same data pack: poplar wood, orange/red/yellow poplar leaves, red sand, red shrubs, rose bushes. |
| Geyser launchers | Data pack functions (`function/geyser`). Standing near one makes your gravity negative along a curve (about +430 blocks) and switches off fall damage until you land. |
| **No snow on islands in mild biomes** | `BiomeAltitudeMixin`: above y=165 the temperature used for snow/ice ignores altitude. Biomes that are freezing at sea level (snowy plains, frozen ocean, snowy taiga...) still snow, so glaciated islands keep their snow. |
| **Fading music** | `MusicController` (client). Skyborn starts when a geyser launches you and fades out when you land. Sky tracks fade in above y=200 and fade out below y=185, with a gap between tracks and never the same track twice in a row. Vanilla music is silenced meanwhile. |
| **Clouds** | `CloudSpawner` + `CloudParticle` (client). A port of the original mod's method: huge camera-facing translucent particles spawned in bursts near you, drifting, fading in and out. |
| **Orange grass** | `AutumnGrassTint` (client). Grass high up and near poplar wood/leaves is tinted dappled-forest orange (`#df6827`), whatever biome the island floats over. |

## Settings (no rebuild needed)

On first launch the game writes `config/sky_islands.properties`. Edit it with the game closed. Highlights:

* `musicEnabled`, `skyMusicEnterY` (200), `skyMusicExitY` (185), `skyFadeInTicks`, `skyFadeOutTicks`, `skyMusicGapTicks`,
  `launchFadeInTicks`, `launchFadeOutTicks` (20 ticks = 1 second)
* `cloudsEnabled`, `cloudSpawnChance` (lower = fewer clouds, faster game), `cloudsPerBurst`, `cloudSpread`,
  `cloudBaseY`, `cloudHeightVariation`
* `orangeGrass`, `orangeGrassMinY`

The music files are `src/client/resources/assets/sky_islands/sounds/music/*.ogg`. To swap a track, replace the file
with another **Ogg Vorbis** file of the same name (Opus-in-Ogg will not play) and rebuild. Track names are listed in
`MusicController.java` and `sounds.json`.

## Not included: shadows under islands

The dark patches under islands come from Minecraft's sky-light engine, which treats islands like roofs. Fixing that
means changing the light engine's internals, and I would not write that without seeing the real 26.3 source. Use the
**decompile-minecraft** workflow (Actions tab > *Run workflow*) to download the decompiled source as an artifact,
then send the relevant light-engine files and the fix can be written against the real code. Meanwhile the data pack
raises the overworld's minimum brightness a little (`ambient_light_color` in `dimension_type/overworld.json`).

## Spots most likely to need a tweak (written without being able to compile)

The code was checked against Fabric API 26.3's own source and published mapping data, but not compiled or run.
If the build fails, the likely candidates are small name differences in:

* `FadingSound`: the protected fields `volume`, `looping`, `relative`, `attenuation`.
* `CloudParticle`: `scale`, `setSize`, `setLifetime`, `setAlpha`, and the fields `friction`, `gravity`, `hasPhysics`, `xd/yd/zd`.
* `AutumnGrassTint`: `Blocks.SHORT_GRASS`, `BlockAndTintGetter.getBlockState`.
* `MusicController`: `player.getAttributeValue(Attributes.GRAVITY)`, `SoundManager.isActive/play`.

The `BiomeAltitudeMixin` and `VanillaMusic` are written so that a wrong name cannot break the game: they just log a
warning and that one feature stays off. Search `latest.log` for `Sky Islands` or `Mixin` to see.

## License

MIT. Based on *Sky Lands* by DeepCanionStudio (MIT).
