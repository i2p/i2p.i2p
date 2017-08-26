package net.i2p.crypto;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;

/**
 * Test the JVM's implementation for speed
 *
 * Old results (2011-05):
 * <ul>
 * <li>eeepc Atom
 * <li>100,000 runs
 * <li>MessageDigest.getInstance time was included
 * <li>One println included
 * <li>Also shows GNU impl time (removed in 0.9.28)
 * </ul><pre>
 * JVM		strlen	GNU ms	JVM  ms 
 * Oracle	387	  3861	 3565
 * Oracle	 40	   825	  635
 * Harmony	387	  8082	 5158
 * Harmony	 40	  4137	 1753
 * JamVM	387	 36301	34100
 * JamVM	 40	  7022	 6016
 * gij		387	125833	 4342
 * gij		 40	 22417    988
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class SHA256Bench {
    I2PAppContext ctx = I2PAppContext.getGlobalContext();

    @Param({"40", "387", "10240"})
    public int len;

    byte[] data;

    @Setup
    public void prepare() {
        data = new byte[len];
        ctx.random().nextBytes(data);
    }

    @Benchmark
    public Hash calculateHash() {
        return ctx.sha().calculateHash(data);
    }

    public static void main(String args[]) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(SHA256Bench.class.getSimpleName())
                .build();

        new Runner(opt).run();
    }
}
