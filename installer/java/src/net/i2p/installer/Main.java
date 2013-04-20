package net.i2p.installer;

/**
 * <p>
 * Execute one of the other classes in this package.
 * Workaround for izpack bug #162 / our bug #912
 * http://jira.codehaus.org/browse/IZPACK-162
 * http://trac.i2p2.i2p/ticket/912
 * </p>
 * Usage: <code>copy|delete|exec|fixwinpaths args...</code><br>
 *
 * @since 0.9.6
 */
public class Main {

    private static final String USAGE = "Usage: {copy|delete|exec|fixwinpaths} [args...]";

    public static void main(String args[]) {
        if (args.length == 0)
            throw new IllegalArgumentException(USAGE);
        String cmd = args[0];
        String[] shift = new String[args.length - 1];
        if (shift.length > 0)
            System.arraycopy(args, 1, shift, 0, shift.length);
        if (cmd.equals("copy"))
            Copy.main(shift);
        else if (cmd.equals("delete"))
            Delete.main(shift);
        else if (cmd.equals("exec"))
            Exec.main(shift);
        else if (cmd.equals("fixwinpaths"))
            FixWinPaths.main(shift);
        else
            throw new IllegalArgumentException(USAGE);
    }
}
