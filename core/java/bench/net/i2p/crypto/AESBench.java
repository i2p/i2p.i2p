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
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class AESBench {
    I2PAppContext ctx = I2PAppContext.getGlobalContext();
    SessionKey key;
    byte[] iv = new byte[16];
    byte[] origPT = new byte[1024];
    byte[] origCT = new byte[1024];
    byte[] encrypted = new byte[1024];
    byte[] decrypted = new byte[1024];

    @Param({"512", "768", "1024"})
    public int len;

    @Setup
    public void prepare() {
        key = ctx.keyGenerator().generateSessionKey();
        ctx.random().nextBytes(iv);
        ctx.random().nextBytes(origPT);
        ctx.aes().encrypt(origPT, 0, origCT, 0, key, iv, len);
    }

    @Benchmark
    public void encrypt() {
        ctx.aes().encrypt(origPT, 0, encrypted, 0, key, iv, len);
    }

    @Benchmark
    public void decrypt() {
        ctx.aes().decrypt(origCT, 0, decrypted, 0, key, iv, len);
    }

    public static void main(String args[]) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(AESBench.class.getSimpleName())
                .build();

        new Runner(opt).run();
    }
}
