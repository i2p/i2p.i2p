package net.i2p.util;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.i2p.CoreVersion;

/**
 * Simple command line access to various utilities.
 * Not a public API. Subject to change.
 * Apps and plugins should use specific classes.
 *
 * @since 0.9.25
 */
public class CommandLine {

    protected static final List<String> CLASSES = Arrays.asList(new String[] {
        "freenet.support.CPUInformation.CPUID",
        "net.i2p.CoreVersion",
        "net.i2p.crypto.CertUtil",
        "net.i2p.crypto.CryptoCheck",
        "net.i2p.crypto.KeyGenerator",
        "net.i2p.crypto.SelfSignedGenerator",
        "net.i2p.crypto.SU3File",
        "net.i2p.crypto.TrustedUpdate",
        "net.i2p.data.Base32",
        "net.i2p.data.Base64",
        "net.i2p.data.PrivateKeyFile",
        "net.i2p.time.BuildTime",
        "net.i2p.util.Addresses",
        "net.i2p.util.ConvertToHash",
        "net.i2p.util.DNSOverHTTPS",
        "net.i2p.util.EepGet",
        "net.i2p.util.EepHead",
        "net.i2p.util.FileUtil",
        "net.i2p.util.FortunaRandomSource",
        "net.i2p.util.NativeBigInteger",
        "net.i2p.util.PartialEepGet",
        "net.i2p.util.RFC822Date",
        "net.i2p.util.ShellCommand",
        "net.i2p.util.SSLEepGet",
        "net.i2p.util.SystemVersion",
        "net.i2p.util.TranslateReader",
        "net.i2p.util.ZipFileComment"
    });

    protected CommandLine() {}

    public static void main(String args[]) {
        if (args.length > 0) {
            exec(args, CLASSES);
        }
        usage();
        System.exit(1);
    }

    /** will only return if command not found */
    protected static void exec(String args[], List<String> classes) {
        String cmd = args[0].toLowerCase(Locale.US);
        for (String cls : classes) {
            String ccmd = cls.substring(cls.lastIndexOf('.') + 1).toLowerCase(Locale.US);
            if (cmd.equals(ccmd)) {
                try {
                    String[] cargs = new String[args.length - 1];
                    System.arraycopy(args, 1, cargs, 0, args.length - 1);
                    Class<?> c = Class.forName(cls, true, ClassLoader.getSystemClassLoader());
                    Method main = c.getMethod("main", String[].class);
                    main.invoke(null, (Object) cargs);
                    System.exit(0);
                } catch (Exception e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            }
        }
    }

    private static void usage() {
        System.err.println("I2P Core version " + CoreVersion.VERSION + '\n' +
                           "USAGE: java -jar /path/to/i2p.jar command [args]");
        printCommands(CLASSES);
    }

    protected static void printCommands(List<String> classes) {
        System.err.println("Available commands:");
        List<String> cmds = new ArrayList<String>(classes.size());
        for (String cls : classes) {
            String ccmd = cls.substring(cls.lastIndexOf('.') + 1).toLowerCase(Locale.US);
            cmds.add(ccmd);
        }
        Collections.sort(cmds);
        for (String cmd : cmds) {
            System.err.println("    " + cmd);
        }
        System.err.println("Enter command for detailed help.");
    }
}
