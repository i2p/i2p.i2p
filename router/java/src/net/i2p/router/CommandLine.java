package net.i2p.router;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Simple command line access to various utilities.
 * Not a public API. Subject to change.
 * Apps and plugins should use specific classes.
 *
 * @since 0.9.25
 */
public class CommandLine extends net.i2p.util.CommandLine {

    protected static final List<String> RCLASSES = Arrays.asList(new String[] {
        "com.maxmind.geoip2.DatabaseReader",
        "net.i2p.data.router.RouterInfo",
        "net.i2p.data.router.RouterKeyGenerator",
        "net.i2p.router.MultiRouter",
        "net.i2p.router.Router",
        "net.i2p.router.RouterLaunch",
        "net.i2p.router.RouterVersion",
        "net.i2p.router.crypto.FamilyKeyCrypto",
        "net.i2p.router.naming.BlockfileNamingService",
        "net.i2p.router.peermanager.ProfileOrganizer",
        "net.i2p.router.tasks.CryptoChecker",
        "net.i2p.router.time.NtpClient",
        "net.i2p.router.transport.GeoIPv6",
        "net.i2p.router.transport.udp.MTU",
        "net.i2p.router.transport.UPnP"
    });

    protected CommandLine() {}

    public static void main(String args[]) {
        List<String> classes = new ArrayList<String>(RCLASSES.size() + CLASSES.size());
        classes.addAll(RCLASSES);
        classes.addAll(CLASSES);
        if (args.length > 0) {
            exec(args, classes);
        }
        usage(classes);
        System.exit(1);
    }

    private static void usage(List<String> classes) {
        System.err.println("I2P Router version " + RouterVersion.FULL_VERSION + '\n' +
                           "USAGE: java -jar /path/to/router.jar command [args]");
        printCommands(classes);
    }
}
