package com.durkz.quantumhy.view;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class VisualPressurePolicyBenchmark {

    @Param({"32", "80", "200", "500"})
    public int candidates;

    @Benchmark
    public double projectAndScoreEntityLoad() {
        double projected = VisualPressurePolicy.projectedCandidates(candidates, 64, 128);
        double ratio = VisualPressurePolicy.entityRatio(projected, 80);
        return VisualPressurePolicy.entityShrinkFraction(ratio)
                + VisualPressurePolicy.emergencyScore(ratio, 0.5D);
    }

    @Benchmark
    public double updateSmoothedPressure() {
        double ratio = VisualPressurePolicy.entityRatio(candidates, 80);
        double score = VisualPressurePolicy.emergencyScore(ratio, 0.5D);
        return VisualPressurePolicy.ema(0.75D, score, 0.25D, true);
    }
}
