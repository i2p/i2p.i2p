package org.klomp.snark.bencode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Exercises the real decoder on malformed input and valid streamed values. */
public final class BDecoderRegression {
    private static int checks;

    public static void main(String[] args) throws Throwable {
        String test = args[0];
        if ("valid".equals(test)) validValues();
        else if ("lengths".equals(test)) stringLengths();
        else if ("numbers".equals(test)) numberLengths();
        else if ("truncation".equals(test)) truncatedContainers();
        else if ("dictionaries".equals(test)) dictionaries();
        else if ("nesting".equals(test)) nesting();
        else if ("hashes".equals(test)) infoHashes();
        else throw new IllegalArgumentException(test);
        System.out.println(test + ": CHECKS=" + checks);
    }

    private static void validValues() throws Throwable {
        check(decode("") == null, "empty stream");
        check(decode("0:").getBytes().length == 0, "empty byte string");
        check(decode("le").getList().isEmpty(), "empty list");
        check(decode("de").getMap().isEmpty(), "empty dictionary");
        for (String value : new String[] {"0", "1", "-1", "2147483647", "-2147483648",
                "9223372036854775807", "-9223372036854775808", "9223372036854775808"}) {
            check(decode("i" + value + "e").getNumber().toString().equals(value), "integer " + value);
        }
        List<BEValue> list = decode("li1e3:food1:ai2eee").getList();
        check(list.size() == 3 && list.get(0).getInt() == 1
                && "foo".equals(list.get(1).getString())
                && list.get(2).getMap().get("a").getInt() == 2, "mixed nested values");
        BDecoder decoder = decoder("i1e0:lei2e");
        check(decoder.bdecode().getInt() == 1, "first sequential value");
        check(decoder.bdecode().getBytes().length == 0, "second sequential value");
        check(decoder.bdecode().getList().isEmpty(), "third sequential value");
        check(decoder.bdecode().getInt() == 2 && decoder.bdecode() == null, "sequential EOF");

        byte[] payload = new byte[32768];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        BEncoder.bencode(payload, encoded);
        InputStream fragmented = new ByteArrayInputStream(encoded.toByteArray()) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 3));
            }
        };
        check(Arrays.equals(new BDecoder(fragmented).bdecode().getBytes(), payload), "fragmented binary data");
    }

    private static void stringLengths() throws Throwable {
        for (String length : new String[] {"16777217", "2147483647", "2147483648", "4294967296", "4294967297",
                "18446744073709551616", repeat('9', 1000)}) {
            expect(InvalidBEncodingException.class, length + ":a");
        }
        expect(InvalidBEncodingException.class, "1x:a");
        expect(EOFException.class, "1");
        expect(EOFException.class, "2:a");
        check("abc".equals(decode("3:abc").getString()), "valid length");
        expect(InvalidBEncodingException.class, repeat('0', 1000) + ":");
        check(decode("0000000000:").getBytes().length == 0, "bounded legacy leading zero length");
        check(bounded(bytes("3:abc"), 3).bdecode().getBytes().length == 3,
                "explicit string limit accepts exact bound");
        try {
            bounded(bytes("4:abcd"), 3).bdecode();
            throw new AssertionError("explicit string bound exceeded");
        } catch (InvalidBEncodingException expected) { checks++; }
        check(bounded(bytes("0:"), 0).bdecode().getBytes().length == 0,
                "zero string limit accepts empty data");
        try {
            bounded(new byte[0], -1);
            throw new AssertionError("negative string limit accepted");
        } catch (IllegalArgumentException expected) { checks++; }
    }

    private static void numberLengths() throws Throwable {
        for (String number : new String[] {repeat('9', 256), "-" + repeat('9', 255)}) {
            check(decode("i" + number + "e").getNumber().equals(new BigInteger(number)), "maximum integer length");
        }
        expect(InvalidBEncodingException.class, "i" + repeat('9', 257) + "e");
        expect(InvalidBEncodingException.class, "i-" + repeat('9', 256) + "e");
        for (String number : new String[] {"i00e", "i01e", "i-0e", "ie", "i-e", "i1xe"}) {
            expect(InvalidBEncodingException.class, number);
        }
        InputStream endlessInteger = new InputStream() {
            private int read;

            @Override
            public int read() {
                if (++read > 300) throw new AssertionError("integer limit checked after consuming excess input");
                return read == 1 ? 'i' : '9';
            }
        };
        try {
            new BDecoder(endlessInteger).bdecode();
            throw new AssertionError("unterminated oversized integer accepted");
        } catch (InvalidBEncodingException expected) {
            checks++;
        }
    }

    private static void truncatedContainers() throws Throwable {
        for (String input : new String[] {"l", "li1e", "ll", "lli1ee", "d", "d1:a", "d1:ai1e",
                "d1:ad", "d1:al", "ld1:ai1ee"}) {
            expect(EOFException.class, input);
        }
        for (String input : new String[] {"l3:abce", "d1:ali1eee"}) {
            for (int end = 1; end < input.length(); end++) {
                expect(EOFException.class, input.substring(0, end));
            }
            check(decode(input) != null, "complete container after truncation checks");
        }
        try {
            decoder("l").bdecodeList();
            throw new AssertionError("direct list decoding accepted EOF");
        } catch (EOFException expected) {
            checks++;
        }
        try {
            decoder("d1:a").bdecodeMap();
            throw new AssertionError("direct dictionary decoding accepted missing value");
        } catch (EOFException expected) {
            checks++;
        }
    }

    private static void dictionaries() throws Throwable {
        for (String input : new String[] {"di1ei2ee", "dlei2ee", "ddei2ee", "d1:ae", "d1:ai1e1:ai2ee"}) {
            expect(InvalidBEncodingException.class, input);
        }
        Map<String, BEValue> map = decode("d0:i1e1:bi2e1:ai3ee").getMap();
        check(map.size() == 3 && map.get("").getInt() == 1 && map.get("a").getInt() == 3,
                "empty keys and unordered dictionaries remain supported");
        for (String input : new String[] {"\0i1e", "l\0i1ee", "d\01:ai1ee", "d1:a\0i1ee"}) {
            expect(InvalidBEncodingException.class, input);
        }
        check(decode("1:\0").getBytes()[0] == 0, "NUL remains valid inside byte strings");
    }

    private static void nesting() throws Throwable {
        for (boolean maps : new boolean[] {false, true}) {
            String maximum = nested(100, maps);
            check(decode(maximum) != null, "maximum container depth");
            expect(InvalidBEncodingException.class, nested(101, maps));
            expect(InvalidBEncodingException.class, nested(10000, maps));
            BDecoder decoder = decoder(maximum + maximum);
            check(decoder.bdecode() != null && decoder.bdecode() != null && decoder.bdecode() == null,
                    "depth resets between values");
        }
        check(decoder(nested(100, false)).bdecodeList() != null, "direct list depth");
        check(decoder(nested(100, true)).bdecodeMap() != null, "direct dictionary depth");
    }

    private static void infoHashes() throws Throwable {
        for (String info : new String[] {"d1:zli2e3:xyze1:ai1ee", "d4:infod1:xi2ee1:yi3ee"}) {
            byte[] encoded = bytes("d4:info" + info + "7:comment4:teste");
            BDecoder decoder = new BDecoder(new ByteArrayInputStream(encoded));
            check(decoder.bdecodeMap().getMap().containsKey("info"), "info dictionary decoded");
            check(Arrays.equals(decoder.get_special_map_digest(),
                    MessageDigest.getInstance("SHA-1").digest(bytes(info))), "original info bytes hashed");
        }
        BDecoder decoder = decoder("d1:ai1ee");
        decoder.bdecodeMap();
        check(decoder.get_special_map_digest() == null, "no info dictionary");
    }

    private static BDecoder bounded(byte[] input, int limit) throws Throwable {
        try {
            return BDecoder.class.getConstructor(InputStream.class, int.class)
                    .newInstance(new ByteArrayInputStream(input), Integer.valueOf(limit));
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static BEValue decode(String input) throws Throwable {
        return decoder(input).bdecode();
    }

    private static BDecoder decoder(String input) {
        return new BDecoder(new ByteArrayInputStream(bytes(input)));
    }

    private static byte[] bytes(String input) {
        return input.getBytes(StandardCharsets.UTF_8);
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        Arrays.fill(result, value);
        return new String(result);
    }

    private static String nested(int depth, boolean maps) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < depth; i++) result.append(maps ? "d1:k" : "l");
        return result.append("i1e").append(repeat('e', depth)).toString();
    }

    private static void expect(Class<? extends Throwable> type, String input) throws Throwable {
        try {
            decode(input);
        } catch (Throwable failure) {
            if (!type.isInstance(failure)) throw failure;
            checks++;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName() + " for " + input);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
