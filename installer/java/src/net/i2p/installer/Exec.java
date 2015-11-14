package net.i2p.installer;

import java.io.File;
import java.io.IOException;

/**
 * <p>This class can be used by the installer to execute shell commands.</p>
 * Usage: <code>Exec dir command [args ...]</code><br>
 *
 * See also {@link net.i2p.util.ShellCommand}.
 * @since 0.4.1.4, moved to {@link net.i2p.installer} in 0.9.5
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
        } catch (IOException e) {
            e.printStackTrace();
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }
}
