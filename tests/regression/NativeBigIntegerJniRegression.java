package net.i2p.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

/** Loads the compiled production library and compares real JNI calls with BigInteger. */
public final class NativeBigIntegerJniRegression {
    private static int checks;
    private static Method pow, ct, inverse;
    private static final MessageDigest DIGEST;
    static { try { DIGEST = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    public static void main(String[] args) throws Exception {
        System.setProperty("jbigi.enable", "false");
        Class<?> type = Class.forName("net.i2p.util.NativeBigInteger");
        System.load(args[0]);
        pow = method(type, "nativeModPow", 3);
        ct = method(type, "nativeModPowCT", 3);
        inverse = method(type, "nativeModInverse", 2);
        Random random = new Random(20261115);
        for (int i = 0; i < 64; i++) {
            BigInteger base = new BigInteger(768, random);
            if ((i & 1) == 0) base = base.negate();
            BigInteger exponent = new BigInteger(80, random).add(BigInteger.ONE);
            BigInteger modulus = new BigInteger(512, random).setBit(511).setBit(0);
            same(base.modPow(exponent, modulus), call(pow, base.toByteArray(), exponent.toByteArray(), modulus.toByteArray()));
            same(base.modPow(exponent, modulus), call(ct, base.toByteArray(), exponent.toByteArray(), modulus.toByteArray()));
            if (base.gcd(modulus).equals(BigInteger.ONE))
                same(base.modInverse(modulus), call(inverse, base.toByteArray(), modulus.toByteArray()));
            else expect(ArithmeticException.class, inverse, base.toByteArray(), modulus.toByteArray());
        }
        for (int base = -4; base <= 4; base++) {
            for (int modulus : new int[] { 1, 3, 5, 11 }) {
                BigInteger b = BigInteger.valueOf(base), m = BigInteger.valueOf(modulus);
                for (int exponent : new int[] { 0, 1, 2, 31 }) {
                    BigInteger e = BigInteger.valueOf(exponent);
                    same(b.modPow(e, m), call(pow, b.toByteArray(), e.toByteArray(), m.toByteArray()));
                    if (exponent > 0) same(b.modPow(e, m), call(ct, b.toByteArray(), e.toByteArray(), m.toByteArray()));
                }
                if (b.gcd(m).equals(BigInteger.ONE)) same(b.modInverse(m), call(inverse, b.toByteArray(), m.toByteArray()));
                else expect(ArithmeticException.class, inverse, b.toByteArray(), m.toByteArray());
            }
        }
        byte[] one = { 1 }, two = { 2 }, zero = { 0 }, minusOne = { -1 };
        expect(ArithmeticException.class, pow, two, minusOne, one);
        expect(ArithmeticException.class, pow, two, one, zero);
        expect(ArithmeticException.class, ct, two, zero, one);
        expect(ArithmeticException.class, ct, two, one, two);
        expect(ArithmeticException.class, inverse, two, zero);
        System.out.println("NATIVE_VALID_SHA256=" + HexFormat.of().formatHex(DIGEST.digest()));
        boolean validOnly = args.length > 1 && args[1].equals("--valid-only");
        if (!validOnly) {
            for (Method operation : new Method[] { pow, ct, inverse }) {
                int count = operation.getParameterCount();
                for (int position = 0; position < count; position++) {
                    byte[][] values = count == 3 ? new byte[][] { two, one, one } : new byte[][] { two, one };
                    values[position] = null;
                    expect(NullPointerException.class, operation, values);
                    values[position] = new byte[0];
                    expect(NumberFormatException.class, operation, values);
                }
            }
        }
        System.out.println("NATIVE_JNI_CHECKS=" + checks + "; FAILURES=0");
    }
    private static Method method(Class<?> owner, String name, int count) throws Exception {
        Class<?>[] args = new Class<?>[count]; java.util.Arrays.fill(args, byte[].class);
        Method result = owner.getDeclaredMethod(name, args); result.setAccessible(true); return result;
    }
    private static BigInteger call(Method method, byte[]... values) throws Exception {
        return new BigInteger((byte[]) method.invoke(null, (Object[]) values));
    }
    private static void same(BigInteger expected, BigInteger actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("native mathematical result differs");
        byte[] bytes = actual.toByteArray();
        DIGEST.update((byte) (bytes.length >>> 8)); DIGEST.update((byte) bytes.length); DIGEST.update(bytes);
    }
    private static void expect(Class<? extends Throwable> expected, Method method, byte[]... values) throws Exception {
        checks++;
        try { method.invoke(null, (Object[]) values); }
        catch (InvocationTargetException e) { if (expected.isInstance(e.getCause())) return; throw e; }
        throw new AssertionError("expected native exception: " + expected.getSimpleName());
    }
}
