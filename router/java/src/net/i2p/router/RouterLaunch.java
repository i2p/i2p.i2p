package net.i2p.router;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;

/**
 *  This is the class called by the runplain.sh script on linux
 *  and the i2p.exe launcher on Windows.
 *  (i.e. no wrapper)
 *
 *  If there is no -Dwrapper.log=/path/to/wrapper.log on the java command line
 *  to specify a log file, check for existence of wrapper.log in CWD,
 *  for backward compatibility in old installations (don't move it).
 *  Otherwise, use (system temp dir)/wrapper.log.
 *  Create if it doesn't exist, and append to it if it does.
 *  Put the location in the environment as an absolute path, so logs.jsp can find it.
 */
public class RouterLaunch {
    private static final String PROP_WRAPPER_LOG = "wrapper.logfile";
    private static final String DEFAULT_WRAPPER_LOG = "wrapper.log";

    public static void main(String args[]) {
        String path = System.getProperty(PROP_WRAPPER_LOG);
        File logfile;
        if (path != null) {
            logfile = new File(path);
        } else {
            logfile = new File(DEFAULT_WRAPPER_LOG);
            if (!logfile.exists())
                logfile = new File(System.getProperty("java.io.tmpdir"), DEFAULT_WRAPPER_LOG);
        }
        System.setProperty(PROP_WRAPPER_LOG, logfile.getAbsolutePath());
        try {
            System.setOut(new PrintStream(new FileOutputStream(logfile, true)));
        } catch (IOException ioe) {
            ioe.printStackTrace();
	}
	Router.main(args);
    }
}
