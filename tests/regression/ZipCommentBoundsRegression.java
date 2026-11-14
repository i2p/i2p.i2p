package net.i2p.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.i2p.crypto.TrustedUpdate;

/** Exercises public ZIP-comment parsing and the legacy update error path. */
@SuppressWarnings("deprecation")
public final class ZipCommentBoundsRegression {
    private static int checks;
    private static int failures;
    private static final byte[] END_MAGIC = {0x50, 0x4b, 0x05, 0x06};

    private interface Case {
        void run() throws Exception;
    }

    private static void check(String label, Case test) {
        checks++;
        try {
            test.run();
        } catch (Exception | AssertionError failure) {
            failures++;
            System.out.println("FAIL: " + label + " (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private static byte[] zip(byte[] comment) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry("fixture.txt");
            entry.setTime(0);
            byte[] contents = new byte[512];
            CRC32 crc = new CRC32();
            crc.update(contents);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(contents.length);
            entry.setCrc(crc.getValue());
            out.putNextEntry(entry);
            out.write(contents);
            out.closeEntry();
        }
        byte[] data = bytes.toByteArray();
        int end = data.length;
        data = Arrays.copyOf(data, end + comment.length);
        data[end - 2] = (byte) comment.length;
        data[end - 1] = (byte) (comment.length >>> 8);
        System.arraycopy(comment, 0, data, end, comment.length);
        return data;
    }

    private static String read(byte[] data, int max, int skip) throws IOException {
        Path file = Files.createTempFile("i2p-zip-comment-", ".zip");
        try {
            Files.write(file, data);
            return ZipFileComment.getComment(file.toFile(), max, skip);
        } finally {
            Files.delete(file);
        }
    }

    private static void expectComment(byte[] data, int max, int skip, String expected) throws IOException {
        if (!expected.equals(read(data, max, skip)))
            throw new AssertionError("ZIP comment changed");
    }

    private static void expectInvalid(byte[] data, int max) throws IOException {
        try {
            read(data, max, 0);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("invalid comment accepted");
    }

    public static void main(String[] args) throws Exception {
        for (String comment : new String[] {"", "9.0.0", "caf\u00e9 \ud83d\ude00"}) {
            byte[] encoded = comment.getBytes(StandardCharsets.UTF_8);
            byte[] data = zip(encoded);
            check("ordinary comment", () -> expectComment(data, encoded.length, 0, comment));
            byte[] wrapped = new byte[TrustedUpdate.HEADER_BYTES + data.length];
            System.arraycopy(data, 0, wrapped, TrustedUpdate.HEADER_BYTES, data.length);
            check("prefixed ZIP", () -> expectComment(wrapped, 256, TrustedUpdate.HEADER_BYTES, comment));
        }

        byte[] longest = new byte[65535];
        Arrays.fill(longest, (byte) 'x');
        check("maximum ZIP comment", () -> expectComment(zip(longest), 65535, 0,
                                                        new String(longest, StandardCharsets.UTF_8)));
        byte[] tooLong = zip("version".getBytes(StandardCharsets.UTF_8));
        check("caller comment limit", () -> expectInvalid(tooLong, 3));

        // A ZIP signature inside the comment is data. Incomplete candidates
        // must never read a length field beyond the available bytes.
        for (int tail = 0; tail < 18; tail++) {
            byte[] comment = new byte[4 + tail];
            Arrays.fill(comment, (byte) 'x');
            System.arraycopy(END_MAGIC, 0, comment, 0, END_MAGIC.length);
            byte[] data = zip(comment);
            check("incomplete embedded record " + tail,
                  () -> expectComment(data, 256, 0, new String(comment, StandardCharsets.UTF_8)));
        }

        for (int declared : new int[] {0, 1, 255, 65535}) {
            byte[] comment = new byte[26];
            System.arraycopy(END_MAGIC, 0, comment, 0, END_MAGIC.length);
            comment[20] = (byte) declared;
            comment[21] = (byte) (declared >>> 8);
            Arrays.fill(comment, 22, comment.length, (byte) 'x');
            byte[] data = zip(comment);
            check("embedded record with wrong length " + declared,
                  () -> expectComment(data, 256, 0, new String(comment, StandardCharsets.UTF_8)));
        }

        for (int declared : new int[] {0, 2, 65535}) {
            byte[] data = zip(new byte[] {'9'});
            data[data.length - 3] = (byte) declared;
            data[data.length - 2] = (byte) (declared >>> 8);
            check("invalid terminal length " + declared, () -> expectInvalid(data, 256));
        }

        byte[] noEnd = zip(new byte[0]);
        noEnd[noEnd.length - 22] = 0;
        check("missing end record", () -> expectInvalid(noEnd, 256));

        check("legacy update handles malformed comment", () -> {
            byte[] data = zip(new byte[] {'9'});
            data[data.length - 3] = (byte) 255;
            data[data.length - 2] = (byte) 255;
            byte[] update = new byte[TrustedUpdate.HEADER_BYTES + data.length];
            update[40] = '9';
            System.arraycopy(data, 0, update, TrustedUpdate.HEADER_BYTES, data.length);
            Path source = Files.createTempFile("i2p-update-comment-", ".su2");
            Path output = source.resolveSibling(source.getFileName() + ".output");
            try {
                Files.write(source, update);
                String error = new TrustedUpdate().migrateVerified("0", source.toFile(), output.toFile());
                if (!"Update file invalid - signed version mismatch".equals(error) || Files.exists(output))
                    throw new AssertionError("malformed update did not use the existing rejection path");
            } finally {
                Files.deleteIfExists(source);
                Files.deleteIfExists(output);
            }
        });
        System.out.println("CHECKS=" + checks + "; FAILURES=" + failures);
        if (failures != 0)
            System.exit(1);
    }
}
