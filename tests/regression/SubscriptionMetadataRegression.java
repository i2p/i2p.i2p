package net.i2p.addressbook;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public final class SubscriptionMetadataRegression {
    private static int checks;
    private static int failures;
    private static void finish() {
        System.out.println("CHECKS=" + checks + "; FAILURES=" + failures);
        if (failures != 0) System.exit(1);
    }
    public static void main(String[] args) throws Exception {
        checkSubscriptionMetadata(Files.createTempDirectory("subscription-regression-")); finish();
    }
    private static void checkSubscriptionMetadata(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path config = directory.resolve("config.txt");
        ConfigParser.write(Collections.singletonMap("Mixed_Key", "value"), config.toFile());
        check("Addressbook keeps configuration keys case-insensitive",
              "value".equals(ConfigParser.parse(config.toFile()).get("mixed_key")),
              "ordinary configuration keys were not normalized");

        String url = "http://example.i2p/Hosts.txt";
        Path locations = directory.resolve("subscriptions.txt");
        Path etags = directory.resolve("etags");
        Path modified = directory.resolve("last-modified");
        Path fetched = directory.resolve("last-fetched");
        Files.writeString(locations, url + "\n", StandardCharsets.UTF_8);
        ConfigParser.write(Collections.singletonMap(url, "known-etag"), etags.toFile());
        ConfigParser.write(Collections.singletonMap(url, "Mon, 01 Jan 2024 00:00:00 GMT"), modified.toFile());
        ConfigParser.write(Collections.singletonMap(url, "1704067200000"), fetched.toFile());

        SubscriptionList subscriptions = new SubscriptionList(locations.toFile(), etags.toFile(),
                modified.toFile(), fetched.toFile(), 0, Collections.singletonList(url), "127.0.0.1", 4444);
        subscriptions.write();
        // Exercise the real load/save path without initiating a network fetch.
        check("Addressbook preserves an ETag for a mixed-case URL",
              Files.readString(etags).contains("known-etag"),
              "the saved ETag file is empty after a load/save round trip");
        check("Addressbook preserves the last-fetched timestamp for a mixed-case URL",
              Files.readString(fetched).contains("1704067200000"),
              "the last-fetched timestamp was reset to zero");
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
