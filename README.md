<img src="https://i.imgur.com/6z0DETt.png" alt="QuantumHy" width="240">

[![CurseForge](https://img.shields.io/badge/CurseForge-QuantumHy-F16436?logo=curseforge&logoColor=white)](https://www.curseforge.com/hytale/mods/quantumhy)

# QuantumHy

QuantumHy is a server-side mod that makes your client run smoother in Hytale. It works by cutting
how much the server tells your client to draw, and it adjusts that per player depending on where
you are.

## How it actually works

The Hytale client is native, so no mod can touch the renderer. What a server mod can do is decide
how much each client has to render, and that's the whole trick here: fewer chunks and entities in
view means fewer things to draw, which means more FPS.

Two honest limits:

- It only helps where it's installed. Your singleplayer world, your own server, or a server that
  runs it. If you just join someone else's server, this can't do anything there.
- It never pushes your view further than you asked for. Your own view radius is the cap. QuantumHy
  only ever pulls it down, never up.

## What it does

Every few seconds it checks how crowded the area around each player is (lots of entities means
expensive to render) and sets the client view radius to match:

- Out in the open with nothing around: you get your full view radius back.
- Packed area with tons of stuff: it pulls your view in toward the minimum, so your FPS doesn't
  tank exactly where it normally would.
- Still loading chunks (just joined, or moving fast): it leaves your view alone, so it doesn't make
  the client drop chunks it's busy loading.

Out of the box there's no hard cap, so you lose nothing in the open and only get trimmed when it's
crowded. If you'd rather trade some view distance for FPS everywhere, set `targetClientViewRadius`
above 0.

It also smooths how fast chunks stream to you, so moving into fresh terrain arrives spread out
instead of in one burst that makes the client hitch. That's the `smoothChunkStreaming` keys below.

## Performance

I ran the same kind of scene with the mod off and on (solo, lots of birds and mobs on screen) and
read the in-game FPS overlay:

- Average FPS: about 84 off, about 113 on. Roughly +34%.
- Median FPS: 74 off, 106 on. Roughly +44%, and the median is closer to how it actually feels.
- In the heavy moments, with a swarm of entities on screen, off dropped to 50-60 FPS while on held
  around 90-105. That's about +55% to +75% right where you need it.

Fair warning: this isn't a lab benchmark. The two runs aren't the exact same path or length, and the
recordings are at different frame rates, so I leaned on the overlay numbers, not the video files.
Realistic gain is somewhere around +30% to +45%, with bigger spikes in crowded areas.

Mod on: https://www.youtube.com/watch?v=gjRcmj12A8A

<p align="center">
  <a href="https://www.youtube.com/watch?v=gjRcmj12A8A">
    <img src="https://img.youtube.com/vi/gjRcmj12A8A/maxresdefault.jpg" alt="QuantumHy mod on" width="720">
  </a>
</p>

Mod off: https://www.youtube.com/watch?v=I8I05ioJG7g

<p align="center">
  <a href="https://www.youtube.com/watch?v=I8I05ioJG7g">
    <img src="https://img.youtube.com/vi/I8I05ioJG7g/maxresdefault.jpg" alt="QuantumHy mod off" width="720">
  </a>
</p>

## Config

Lives in `QuantumHy.json` in the plugin data folder, created on first run.

| Key | Default | What it does |
| --- | --- | --- |
| `enabled` | `true` | Turn the whole thing on or off. |
| `verboseLog` | `true` | Log every pass with each player's density and view decision. |
| `tickIntervalSeconds` | `5` | How often it re-checks each player. |
| `initialDelaySeconds` | `20` | Wait this long after start before the first pass. |
| `targetClientViewRadius` | `0` | Hard cap in chunks. `0` means no cap, just adapt. |
| `minClientViewRadius` | `6` | Never pull anyone below this. |
| `maxClientViewRadius` | `32` | Ceiling for the hard cap (your own view radius still wins). |
| `densityScanChunkRadius` | `4` | How many chunks around you it counts entities in. |
| `densityLowPerChunk` | `2.0` | Entities per chunk at or below this: you get the full radius. |
| `densityHighPerChunk` | `8.0` | Entities per chunk at or above this: you get pulled to the minimum. |
| `densitySmoothing` | `0.4` | Smooths the density signal so a moving player's view doesn't flip-flop. Lower is smoother; `1.0` is off. |
| `adaptEntityRadius` | `true` | Also shrink how far entities are streamed (not just chunks). The big win in mob-heavy spots. |
| `minEntityViewBlocks` | `48` | Never stream entities closer than this, in blocks (16 blocks = 1 chunk). |
| `entityLodAggressiveness` | `1.5` | Global entity LOD cull. `1.0` is the engine default; higher drops small/distant entities sooner. |
| `maxEntityVerticalDistance` | `40` | Drop entities too far above/below you from the stream (caves, ceilings). `0` = off. |
| `maxVisibleEntitiesPerPlayer` | `0` | Cap streamed entities per player in crowds (`0` = off). |
| `holdSpawnOnLoadingChunks` | `true` | Pause environmental spawning while any player has chunks streaming to the client. |
| `minViewRadiusDelta` | `2` | Don't bother changing the view for tiny differences. |
| `respectStreamingGrace` | `true` | Don't shrink while you're still loading chunks. |
| `streamingBacklogThreshold` | `8` | How many loading chunks counts as "still streaming". |
| `smoothChunkStreaming` | `true` | Spread chunk streaming out so moving into new terrain doesn't hitch. |
| `maxChunksPerSecond` | `128` | Cap on chunks streamed per second to a managed client. `0` keeps the engine default. |
| `maxChunksPerTick` | `2` | Cap on chunks streamed per tick. This is the real anti-hitch lever (engine default is 4). `0` keeps the default. |
| `leanCoreTakeover` | `true` | If LeanCore is installed, take the view radius over from it (see below). |
| `yieldToLeanCoreViewRadius` | `false` | The opposite: leave the view radius to LeanCore (see below). |
| `pressureGovernorEnabled` | `true` | Tighten render levers when world MSPT stays high. |
| `pressureMsptEnter` | `52` | 10s average MSPT at or above this enters pressure mode. |
| `pressureMsptExit` | `47` | MSPT at or below this exits pressure mode (hysteresis). |
| `pressureSustainSeconds` | `6` | How long MSPT must stay high before levers tighten. |
| `pressureCooldownSeconds` | `15` | How long MSPT must stay low before levers restore. |
| `pressureDensityMultiplier` | `1.35` | Under pressure, density thresholds tighten by this factor. |
| `pressureChunkRateMultiplier` | `0.75` | Under pressure, multiply chunk streaming caps. |
| `pressureLodMultiplier` | `1.15` | Under pressure, extra entity LOD cull on top of `entityLodAggressiveness`. |
| `pressureVerticalTrimBlocks` | `8` | Under pressure, subtract from `maxEntityVerticalDistance`. |
| `pressureWorldLevers` | `false` | Under pressure, pause NPC spawn and block tick on the world config (restored on release). |
| `pressureTrimClientEffects` | `true` | Under pressure, trim bloom/sunshaft client effects (restored on release). |
| `pressureEffectScale` | `0.5` | Multiplier for client effect intensities while trimmed. |

## Running it with LeanCore

If you run LeanCore too, each mod owns different levers. QuantumHy never double-writes the same
field LeanCore is already driving.

| Lever | Default owner (both mods) |
| --- | --- |
| Client view radius | **QuantumHy** (`leanCoreTakeover=true` turns off LeanCore view governance) |
| Entity stream radius | **QuantumHy** |
| Hot/simulation radius | **LeanCore** (`maxHotLoadedChunksRadius`) |
| Chunk send rate (`maxChunks/s`, `maxChunks/tick`) | **QuantumHy** if LeanCore `chunkThroughputGovernanceEnabled=false` (LeanCore default). **LeanCore** if that flag is true: QuantumHy auto-yields and stops writing send-rate caps. |
| MSPT render trim (density, LOD, vertical, effects) | **QuantumHy** (heap/memory tiers stay LeanCore; signals are complementary) |
| Spawn stream pause | **QuantumHy** |
| Zone dormancy / memory unload | **LeanCore** |

**Recommended combo:** `leanCoreTakeover=true`, LeanCore `chunkThroughputGovernanceEnabled=false`,
QuantumHy `smoothChunkStreaming=true`. No overlap.

**Risky combo:** LeanCore `chunkThroughputGovernanceEnabled=true` **and** QuantumHy trying to set
chunk rate. QuantumHy detects this and yields chunk send-rate to LeanCore automatically; MSPT
pressure still trims density, LOD, vertical cull, and client effects.

Do not enable QuantumHy `pressureWorldLevers` alongside LeanCore unless you intend to flip
world-level spawn/block-tick flags under MSPT (LeanCore does not touch those, but it is extra
simulation pressure on top of memory governance).

View-radius knobs:

- `leanCoreTakeover` (default `true`): detect LeanCore and take the view radius over. Set false
  to leave LeanCore alone, but then both may fight over client view radius.
- `yieldToLeanCoreViewRadius` (default `false`): QuantumHy stays out of view radius and chunk
  send-rate entirely; LeanCore keeps both if its throughput governance is on.

Use `/q status` for live ownership (`chunkRateOwner=`, `LeanCore: view=...`).

## Commands

- `/q status` (alias `/quantumhy`, `/qhy`): shows what QuantumHy is doing right now, the active
  levers, and per-player chunk load.
- `/q help`: lists the commands.

## Build

You need a Hytale install (that's where `HytaleServer.jar` comes from) and JDK 25.

```
./gradlew build
```

The jar lands in `build/libs/`. Drop it in `%AppData%/Hytale/UserData/Mods/` to test it.

## Links

- [Mod page](https://durkzprgmods.pages.dev/mods/quantumhy)
- [Docs](https://durkzprgmods.pages.dev/documentation/quantumhy)
- [GitHub](https://github.com/DurkzPRG/QuantumHy)
- [CurseForge](https://www.curseforge.com/hytale/mods/quantumhy)

## License

MIT. See [LICENSE](LICENSE).
