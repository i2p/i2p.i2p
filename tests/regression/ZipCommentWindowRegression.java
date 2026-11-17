package net.i2p.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Checks bounded tail reads from small ZIP files, including legacy prefixes. */
@SuppressWarnings("deprecation")
public final class ZipCommentWindowRegression {
    public static void main(String[] args) throws Exception {
        int checks = 0;
        int failures = 0;
        for (String comment : new String[] {"", "9.0.0", "caf\u00e9 \ud83d\ude00"}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream out = new ZipOutputStream(bytes)) {
                out.setComment(comment);
                out.putNextEntry(new ZipEntry("fixture.txt"));
                out.write(new byte[] {1, 2, 3});
                out.closeEntry();
            }
            for (int prefix : new int[] {0, 56}) {
                byte[] archive = bytes.toByteArray();
                byte[] wrapped = new byte[prefix + archive.length];
                System.arraycopy(archive, 0, wrapped, prefix, archive.length);
                Path file = Files.createTempFile("i2p-zip-window-", ".zip");
                try {
                    Files.write(file, wrapped);
                    for (int max : new int[] {comment.getBytes(StandardCharsets.UTF_8).length, 256, 65535}) {
                        checks++;
                        try {
                            String found = ZipFileComment.getComment(file.toFile(), max, prefix);
                            if (!comment.equals(found))
                                throw new AssertionError("ZIP comment changed");
                        } catch (Exception | AssertionError failure) {
                            failures++;
                            System.out.println("FAIL: prefix=" + prefix + ", max=" + max
                                               + " (" + failure.getClass().getSimpleName() + ")");
                        }
                    }
                } finally {
                    Files.delete(file);
                }
            }
        }
        System.out.println("CHECKS=" + checks + "; FAILURES=" + failures);
        if (failures != 0)
            System.exit(1);
    }
}
