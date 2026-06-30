<p align="center">
  <img src="https://i.imgur.com/6z0DETt.png" alt="QuantumHy" width="480">
</p>

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
| `minViewRadiusDelta` | `2` | Don't bother changing the view for tiny differences. |
| `respectStreamingGrace` | `true` | Don't shrink while you're still loading chunks. |
| `streamingBacklogThreshold` | `8` | How many loading chunks counts as "still streaming". |
| `leanCoreTakeover` | `true` | If LeanCore is installed, take the view radius over from it (see below). |
| `yieldToLeanCoreViewRadius` | `false` | The opposite: leave the view radius to LeanCore (see below). |

## Running it with LeanCore

If you run LeanCore too, QuantumHy sorts out the overlap for you. Both can set the client view
radius and only one should, so QuantumHy takes it: on startup it detects LeanCore and turns off
LeanCore's view-radius governance, then drives the view radius itself. LeanCore keeps doing
everything else (simulation radius, chunk throughput, memory). You don't have to touch LeanCore's
config.

This matters on dedicated servers and in solo, where LeanCore manages the view radius by default.

Two knobs if you want it different:

- `leanCoreTakeover` (default `true`): detect LeanCore and take the view radius over. Set it false
  to leave LeanCore alone, but then both can fight over it.
- `yieldToLeanCoreViewRadius` (default `false`): the opposite. QuantumHy stays out of the view
  radius entirely and lets LeanCore keep it.

## Build

You need a Hytale install (that's where `HytaleServer.jar` comes from) and JDK 25.

```
./gradlew build
```

The jar lands in `build/libs/`. Drop it in `%AppData%/Hytale/UserData/Mods/` to test it.

## License

MIT.
