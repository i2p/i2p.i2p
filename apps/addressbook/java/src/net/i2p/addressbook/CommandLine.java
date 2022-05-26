package net.i2p.addressbook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.i2p.CoreVersion;

/**
 * Simple command line access to various utilities.
 * Not a public API. Subject to change.
 * Apps and plugins should use specific classes.
 *
 * @since 0.9.55
 */
public class CommandLine extends net.i2p.util.CommandLine {

    protected static final List<String> ACLASSES = Arrays.asList(new String[] {
        "net.i2p.addressbook.HostTxtParser",
        "net.i2p.router.naming.BlockfileNamingService",
        "net.metanotion.io.block.BlockFile",
    });

    protected CommandLine() {}

    public static void main(String args[]) {
        List<String> classes = new ArrayList<String>(ACLASSES.size() + CLASSES.size());
        classes.addAll(ACLASSES);
        classes.addAll(CLASSES);
        if (args.length > 0) {
            exec(args, classes);
        }
        usage(classes);
        System.exit(1);
    }

    private static void usage(List<String> classes) {
        System.err.println("I2P Address book version " + CoreVersion.VERSION + '\n' +
                           "USAGE: java -jar /path/to/addressbook.jar command [args]");
        printCommands(classes);
    }
}
