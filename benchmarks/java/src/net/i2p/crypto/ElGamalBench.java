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
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class ElGamalBench {
    I2PAppContext ctx = I2PAppContext.getGlobalContext();
    PublicKey pubkey;
    PrivateKey privkey;
    byte[] origPT;
    byte[] origCT;

    @Setup
    public void prepare() {
        Object pair[] = KeyGenerator.getInstance().generatePKIKeypair();
        pubkey = (PublicKey) pair[0];
        privkey = (PrivateKey) pair[1];
        origPT = new byte[222];
        ctx.random().nextBytes(origPT);
        origCT = ctx.elGamalEngine().encrypt(origPT, pubkey);
    }

    @Benchmark
    public Object[] keygen() {
        return KeyGenerator.getInstance().generatePKIKeypair();
    }

    @Benchmark
    public byte[] encrypt() {
        return ctx.elGamalEngine().encrypt(origPT, pubkey);
    }

    @Benchmark
    public byte[] decrypt() {
        return ctx.elGamalEngine().decrypt(origCT, privkey);
    }

    public static void main(String args[]) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(ElGamalBench.class.getSimpleName())
                .build();

        new Runner(opt).run();
    }
}
