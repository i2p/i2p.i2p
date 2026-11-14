package net.i2p.sam;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class UTF8ReaderRegression {
    private static int checks;
    private static int failures;
    private static void finish() {
        System.out.println("CHECKS=" + checks + "; FAILURES=" + failures);
        if (failures != 0) System.exit(1);
    }
    public static void main(String[] args) throws Exception { checkUTF8(); finish(); }
    private static void checkUTF8() throws Exception {
        String header = "VALUE=\"\ud83d\ude00\"\n";
        byte[] payload = { 0, (byte) 0xff, 42 };
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(header.getBytes(StandardCharsets.UTF_8));
        bytes.write(payload);
        ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray());
        String actual = readLine(new UTF8Reader(input));
        check("SAM preserves supplementary Unicode", header.equals(actual),
              "expected " + codePoints(header) + "; got " + codePoints(actual));
        check("SAM leaves the binary payload untouched", Arrays.equals(payload, input.readAllBytes()),
              "reading the header consumed payload bytes");

        String bmp = "ASCII \u00e9 \u20ac\n";
        check("SAM preserves ASCII and BMP Unicode",
              bmp.equals(readLine(new UTF8Reader(new ByteArrayInputStream(bmp.getBytes(StandardCharsets.UTF_8))))),
              "ASCII or BMP decoding changed");
    }
    private static String readLine(UTF8Reader reader) throws Exception {
        StringBuilder result = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            result.append((char) c);
            if (c == '\n')
                break;
        }
        return result.toString();
    }
    private static String codePoints(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(c -> result.append(String.format("U+%04X ", c)));
        return result.toString().trim();
    }
    private static void check(String name, boolean success, String detail) {
        checks++;
        if (success) {
            System.out.println("PASS: " + name);
        } else {
            failures++;
            System.out.println("FAIL: " + name + ": " + detail);
        }
    }
}
