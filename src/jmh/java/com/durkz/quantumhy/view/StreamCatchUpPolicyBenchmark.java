package com.durkz.quantumhy.view;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class StreamCatchUpPolicyBenchmark {

    private int moveScore;
    private int calmSamples;
    private StreamCatchUpPolicy.Tier tier = StreamCatchUpPolicy.Tier.CRUISE;

    @Benchmark
    public int healthyFlightPolicy() {
        StreamCatchUpPolicy.Outcome outcome = next(false, 20.0D, 20.0D, 1);
        update(outcome);
        return outcome.perSecond() + outcome.perTick();
    }

    @Benchmark
    public int overloadedFlightPolicy() {
        StreamCatchUpPolicy.Outcome outcome = next(false, 30.0D, 60.0D, 2);
        update(outcome);
        return outcome.perSecond() + outcome.perTick();
    }

    private StreamCatchUpPolicy.Outcome next(boolean governorPressured,
            double average, double last, int movement) {
        return StreamCatchUpPolicy.next(
                true, true, governorPressured, true, average, last,
                45.0D, 35.0D, 0.75D, movement, 40, moveScore, calmSamples,
                tier, 1000L, 0L, 80, 1500,
                128, 8, 256, 12, 2560, 40);
    }

    private void update(StreamCatchUpPolicy.Outcome outcome) {
        moveScore = outcome.moveScore();
        calmSamples = outcome.protectCalmSamples();
        tier = outcome.tier();
    }
}
