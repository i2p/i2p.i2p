package net.i2p.util;

import java.io.File;

/**
 * Usage: Exec dir command [args ...]
 *
 * @deprecated only for use by installer, to be removed from i2p.jar, use ShellCommand
 */
public class Exec {
    public static void main(String args[]) {
        try {
            String cmd[] = new String[args.length - 1];
            System.arraycopy(args, 1, cmd, 0, cmd.length);
            Process proc = Runtime.getRuntime().exec(cmd, (String[])null, new File(args[0]));

            // ugly hack, because it seems we'll block otherwise!
            // http://cephas.net/blog/2004/03/23/external_applications_javas_runtimeexec.html
            try { proc.exitValue(); } catch (Throwable t) { }
	    Runtime.getRuntime().halt(0);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
