package org.klomp.snark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.i2p.CoreVersion;

/**
 * Simple command line access to various utilities.
 * Not a public API. Subject to change.
 * Apps and plugins should use specific classes.
 *
 * @since 0.9.26
 */
public class CommandLine extends net.i2p.util.CommandLine {

    protected static final List<String> SCLASSES = Arrays.asList(new String[] {
        "org.klomp.snark.MetaInfo",
        //"org.klomp.snark.Snark",
        //"org.klomp.snark.StaticSnark",
        "org.klomp.snark.Storage",
        "org.klomp.snark.bencode.BDecoder",
        //"org.klomp.snark.web.RunStandalone",
    });

    protected CommandLine() {}

    public static void main(String args[]) {
        List<String> classes = new ArrayList<String>(SCLASSES.size() + CLASSES.size());
        classes.addAll(SCLASSES);
        classes.addAll(CLASSES);
        if (args.length > 0) {
            exec(args, classes);
        }
        usage(classes);
        System.exit(1);
    }

    private static void usage(List<String> classes) {
        System.err.println("I2PSnark version " + CoreVersion.VERSION + '\n' +
                           "USAGE: java -jar /path/to/i2psnark.jar command [args]");
        printCommands(classes);
    }
}
