package net.i2p.router.news;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.i2p.router.RouterVersion;

/**
 * Simple command line access to various utilities.
 * Not a public API. Subject to change.
 * Apps and plugins should use specific classes.
 *
 * @since 0.9.28
 */
public class CommandLine extends net.i2p.router.CommandLine {

    protected static final List<String> NCLASSES = Arrays.asList(new String[] {
        "com.vuze.plugins.mlab.MLabRunner",
        "net.i2p.router.news.BlocklistEntries",
        "net.i2p.router.news.NewsXMLParser",
        "net.i2p.router.update.NewsHandler"
    });

    protected CommandLine() {}

    public static void main(String args[]) {
        List<String> classes = new ArrayList<String>(NCLASSES.size() + RCLASSES.size() + CLASSES.size());
        classes.addAll(NCLASSES);
        classes.addAll(RCLASSES);
        classes.addAll(CLASSES);
        if (args.length > 0) {
            exec(args, classes);
        }
        usage(classes);
        System.exit(1);
    }

    private static void usage(List<String> classes) {
        System.err.println("I2P Router Console version " + RouterVersion.FULL_VERSION + '\n' +
                           "USAGE: java -jar /path/to/routerconsole.jar command [args]");
        printCommands(classes);
    }
}
