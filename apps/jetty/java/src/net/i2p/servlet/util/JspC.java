package net.i2p.servlet.util;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import net.i2p.util.FileSuffixFilter;
import net.i2p.util.VersionComparator;

/**
 *  Simply call org.apache.jasper.JspC, then exit.
 *
 *  As of Tomcat 8.5.33, forking their JspC won't complete,
 *  because the JspC compilation is now threaded and the thread pool workers aren't daemons.
 *  Will fixed in a 8.5.35, but we don't know what version distros may have.
 *
 *  Additionally, if the system property build.reproducible is "true",
 *  attempts to generate a reproducible build by compiling the
 *  jsps in order, for a consistent web.xml file.
 *
 *  https://tomcat.apache.org/tomcat-8.5-doc/changelog.html
 *  https://bz.apache.org/bugzilla/show_bug.cgi?id=53492
 *  http://trac.i2p2.i2p/ticket/2307
 *  https://bugs.debian.org/cgi-bin/bugreport.cgi?bug=908884
 *  https://bz.apache.org/bugzilla/show_bug.cgi?id=62674
 *
 *  We could set fork=false in build.xml, but then the paths are all wrong.
 *  Only for use in build scripts, obviously not a public API.
 *  See apps/routerconsole/java/build.xml for more information.
 *
 *  @since 0.9.37
 */
public class JspC {
    // First Tomcat version to support multiple threads and -threadCount arg
    private static final String THREADS_VERSION_8 = "8.5.33";
    private static final String THREADS_VERSION_9 = "9.0.11";
    // if true, try to make web.xml reproducible
    private static final boolean REPRODUCIBLE = Boolean.parseBoolean(System.getProperty("build.reproducible"));
    // if true, we must get the Tomcat version out of the jasper jar's manifest
    private static final boolean SYSTEM_TOMCAT = Boolean.parseBoolean(System.getProperty("with-libtomcat8-java")) ||
                                                 Boolean.parseBoolean(System.getProperty("with-libtomcat9-java"));
    // path to the jasper jar
    private static final String JASPER_JAR = System.getProperty("jasper.jar");

    /**
     *  @throws IllegalArgumentException
     */
    public static void main(String args[]) {
       if (REPRODUCIBLE)
           args = fixupArgs(args);
       try {
           String cls = "org.apache.jasper.JspC";
           Class<?> c = Class.forName(cls, true, ClassLoader.getSystemClassLoader());
           Method main = c.getMethod("main", String[].class);
           main.invoke(null, (Object) args);
           System.exit(0);
       } catch (Exception e) {
           e.printStackTrace();
           System.exit(1);
       }
    }

    /**
     *  Only call this if we want reproducible builds.
     *
     *  Convert "-webapp dir/" arguments in the args to
     *  a sorted list of files, for reproducible builds.
     */
    private static String[] fixupArgs(String[] args) {
        List<String> largs = new ArrayList<String>(32);

        // change the webapp arg to uriroot, save the location
        String sdir = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-webapp")) {
                i++;
                if (i >= args.length)
                    throw new IllegalArgumentException("no value for -webapp");
                if (sdir != null)
                    throw new IllegalArgumentException("multiple -webapp args");
                sdir = args[i];
                largs.add("-uriroot");
                largs.add(sdir);
            } else {
                largs.add(a);
            }
        }
        if (sdir == null)
            return args;
        File dir = new File(sdir);
        if (!dir.exists())
            throw new IllegalArgumentException("webapp dir does not exist: " + sdir);
        if (!dir.isDirectory())
            throw new IllegalArgumentException("not a directory: " + sdir);

        // If JspC supports the -threadCount argument, add it to force one thread.
        boolean supportsThreads = false;
        if (SYSTEM_TOMCAT) {
            if (JASPER_JAR != null) {
                // The JASPER_JAR property is a symlink to /usr/share/java/tomcat8-jasper.jar,
                // pull the version out of its manifest.
                Attributes atts = attributes(JASPER_JAR);
                if (atts != null) {
                    String ver = atts.getValue("Implementation-Version");
                    if (ver != null) {
                        if (ver.startsWith("8.")) {
                            supportsThreads = VersionComparator.comp(ver, THREADS_VERSION_8) >= 0;
                        } else {
                            supportsThreads = VersionComparator.comp(ver, THREADS_VERSION_9) >= 0;
                        }
                        System.out.println("Found JspC version: " + ver + ", supports threads? " + supportsThreads);
                    }
                }
            }
        } else {
            // We bundle 8.5.34+
            supportsThreads = true;
        }
        if (supportsThreads) {
            largs.add("-threadCount");
            largs.add("1");
        }

        // add all the files as individual args
        File[] files = dir.listFiles(new FileSuffixFilter(".jsp"));
        if (files == null || files.length == 0)
            throw new IllegalArgumentException("no jsp files in webapp dir: " + sdir);
        Arrays.sort(files);
        for (int i = 0; i < files.length; i++) {
            largs.add(files[i].getName());
        }
        System.out.println("JspC arguments for reproducible build: " + largs);
        String[] rv = new String[largs.size()];
        rv = largs.toArray(rv);
        return rv;
    }

    /**
     * jar manifest attributes
     * @return null if not found
     */
    private static Attributes attributes(String f) {
        InputStream in = null;
        try {
            in = (new URL("jar:file:" + f + "!/META-INF/MANIFEST.MF")).openStream();
            Manifest man = new Manifest(in);
            return man.getMainAttributes();
        } catch (IOException ioe) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException e) {}
        }
    }
}
