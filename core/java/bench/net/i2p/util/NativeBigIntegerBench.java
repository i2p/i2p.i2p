package net.i2p.util;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
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
import net.i2p.crypto.CryptoConstants;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class NativeBigIntegerBench {

    @State(Scope.Benchmark)
    public static class BaseState {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        NativeBigInteger g;
        NativeBigInteger p;
        NativeBigInteger k;

        @Setup
        public void prepare() {
            g = CryptoConstants.elgg;
            p = CryptoConstants.elgp;
        }
    }

    public static class PowState extends BaseState {
        @Setup(Level.Iteration)
        public void randomise() {
            k = new NativeBigInteger(2048, ctx.random());
        }
    }

    public static class InverseState extends BaseState {
        @Setup(Level.Iteration)
        public void randomise() {
            // 0 is not coprime with anything
            BigInteger bi;
            do {
                // Our ElG prime P is 1061 bits, so make K smaller so there's
                // no chance of it being equal to or a multiple of P, i.e. not
                // coprime, so the modInverse test won't fail
                bi = new BigInteger(1060, ctx.random());
            } while (bi.signum() == 0);
            k = new NativeBigInteger(1, bi.toByteArray());
        }
    }

    @Benchmark
    public BigInteger modPow(PowState s) {
        return s.g.modPow(s.k, s.p);
    }

    @Benchmark
    public BigInteger modInverse(InverseState s) {
        return s.k.modInverse(s.p);
    }

    public static void main(String args[]) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(NativeBigIntegerBench.class.getSimpleName())
                .build();

        new Runner(opt).run();
    }
}
