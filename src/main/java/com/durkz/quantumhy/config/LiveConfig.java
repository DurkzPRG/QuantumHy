package com.durkz.quantumhy.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Publishes detached configuration snapshots. Published instances are read-only to consumers. */
public final class LiveConfig implements Supplier<QuantumHyConfig> {
    private static final Gson GSON = new Gson();
    private static final Set<String> RELOADABLE = Set.of(
            "verboseLog", "targetClientViewRadius", "minClientViewRadius", "maxClientViewRadius",
            "minEntityViewBlocks", "densityLowPerChunk", "densityHighPerChunk", "densityRingEdgeWeight",
            "densitySmoothing", "baselineShrinkFraction", "chunkLoadLowChunks", "chunkLoadHighChunks",
            "streamingBacklogThreshold", "maxEntityVerticalDistance", "maxVisibleEntitiesPerPlayer",
            "minViewRadiusDelta", "maxExpandChunksPerPass", "maxShrinkChunksPerPass",
            "maxExpandEntityBlocksPerPass", "expandHysteresisPasses", "worldPassBudgetMs",
            "maxChunksPerSecond", "maxChunksPerTick", "streamCatchUpPerSecond",
            "streamCatchUpPerTick", "streamCatchUpHoldMs", "clientStrainFrameLagLowMs",
            "clientStrainFrameLagHighMs", "clientStrainQueueLow", "clientStrainQueueHigh",
            "effectBudgetPerSecond", "effectProtectRadius", "effectSoftRadius", "effectMaxDistance",
            "pressureItemMergeRadius", "leanCoreMemoryAware");

    private volatile QuantumHyConfig current;

    public LiveConfig(QuantumHyConfig initial) {
        current = copy(initial);
    }

    @Override
    public QuantumHyConfig get() {
        return current;
    }

    public record ReloadResult(List<String> applied, List<String> restartRequired) { }

    public synchronized ReloadResult reload(Path file) throws IOException {
        return reloadJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    synchronized ReloadResult reloadJson(String json) throws IOException {
        QuantumHyConfig before = current;
        JsonObject root = strictObject(json);
        QuantumHyConfig requested = copy(before);
        QuantumHyConfig next = copy(before);
        List<String> applied = new ArrayList<>();
        List<String> restart = new ArrayList<>();
        try {
            for (var entry : root.entrySet()) {
                String key = entry.getKey();
                Field field;
                try {
                    field = QuantumHyConfig.class.getField(key);
                } catch (NoSuchFieldException unknown) {
                    throw new IllegalArgumentException("Unknown setting: " + key);
                }
                if (Modifier.isStatic(field.getModifiers())) {
                    throw new IllegalArgumentException("Invalid setting: " + key);
                }
                Object value = typedValue(key, entry.getValue(), field.getType());
                field.set(requested, value);
                if (!value.equals(field.get(before))) {
                    if (RELOADABLE.contains(key)) {
                        field.set(next, value);
                        applied.add(key);
                    } else {
                        restart.add(key);
                    }
                }
            }
        } catch (IllegalAccessException impossible) {
            throw new IllegalStateException(impossible);
        }
        validate(requested);
        validate(next);
        if (!applied.isEmpty()) {
            current = next;
        }
        return new ReloadResult(List.copyOf(applied), List.copyOf(restart));
    }

    private static JsonObject strictObject(String json) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonObject root = new JsonObject();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (root.has(name)) {
                    throw new IllegalArgumentException("Duplicate setting: " + name);
                }
                root.add(name, JsonParser.parseReader(reader));
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Unexpected content after configuration");
            }
            return root;
        }
    }

    private static Object typedValue(String key, JsonElement value, Class<?> type) {
        if (!value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Invalid value for " + key);
        }
        var primitive = value.getAsJsonPrimitive();
        if (type == boolean.class && primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            try {
                if (type == int.class) {
                    return primitive.getAsBigDecimal().intValueExact();
                }
                if (type == long.class) {
                    return primitive.getAsBigDecimal().longValueExact();
                }
                if (type == double.class && Double.isFinite(primitive.getAsDouble())) {
                    return primitive.getAsDouble();
                }
            } catch (ArithmeticException invalidInteger) {
                throw new IllegalArgumentException("Invalid integer for " + key);
            }
        }
        throw new IllegalArgumentException("Invalid type or non-finite value for " + key);
    }

    private static void validate(QuantumHyConfig candidate) {
        QuantumHyConfig normalized = copy(candidate);
        normalized.applyDefaults();
        JsonObject original = GSON.toJsonTree(candidate).getAsJsonObject();
        JsonObject repaired = GSON.toJsonTree(normalized).getAsJsonObject();
        for (String key : original.keySet()) {
            if (!original.get(key).equals(repaired.get(key))) {
                throw new IllegalArgumentException("Out-of-range setting or inconsistent limits: " + key);
            }
        }
    }

    private static QuantumHyConfig copy(QuantumHyConfig config) {
        return GSON.fromJson(GSON.toJsonTree(config), QuantumHyConfig.class);
    }
}
