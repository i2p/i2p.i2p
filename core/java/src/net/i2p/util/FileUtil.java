package net.i2p.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// WARNING
// Some methods called from install.jar (Windows installer utils)
// or InstallUpdate (i2pupdate.zip installer),
// where most external classes are not available, including DataHelper!
// Use caution when adding dependencies.
import net.i2p.data.DataHelper;

// Pack200 now loaded dynamically in unpack() below
//
// For Sun, OpenJDK, IcedTea, etc, use this
//import java.util.jar.Pack200;
//
// For Apache Harmony or if you put its pack200.jar in your library directory use this
//import org.apache.harmony.unpack200.Archive;

/**
 * General helper methods for messing with files
 *
 * These are static methods that do NOT convert arguments
 * to absolute paths for a particular context and directory.
 *
 * Callers should ALWAYS provide absolute paths as arguments,
 * and should NEVER assume files are in the current working directory.
 *
 */
public class FileUtil {
    /**
     * Delete the path as well as any files or directories underneath it.
     * 
     * @param path path to the directory being deleted
     * @param failIfNotEmpty if true, do not delete anything if the directory
     *                       is not empty (and return false)
     * @return true if the path no longer exists (aka was removed), 
     *         false if it remains
     */
    public static final boolean rmdir(String path, boolean failIfNotEmpty) {
        return rmdir(new File(path), failIfNotEmpty);
    }
    /**
     * Delete the path as well as any files or directories underneath it.
     * 
     * @param target the file or directory being deleted
     * @param failIfNotEmpty if true, do not delete anything if the directory
     *                       is not empty (and return false)
     * @return true if the path no longer exists (aka was removed), 
     *         false if it remains
     */
    public static final boolean rmdir(File target, boolean failIfNotEmpty) {
        if (!target.exists()) {
            //System.out.println("info: target does not exist [" + target.getPath() + "]");
            return true;
        }
        if (!target.isDirectory()) {
            //System.out.println("info: target is not a directory [" + target.getPath() + "]");
            return target.delete();
        } else {
            File children[] = target.listFiles();
            if (children == null) {
                //System.out.println("info: target null children [" + target.getPath() + "]");
                return false;
            }
            if ( (failIfNotEmpty) && (children.length > 0) ) {
                //System.out.println("info: target is not emtpy[" + target.getPath() + "]");
                return false;
            }
            for (int i = 0; i < children.length; i++) {
                if (!rmdir(children[i], failIfNotEmpty))
                    return false;
                
                //System.out.println("info: target removed recursively [" + children[i].getPath() + "]");
            }
            return target.delete();
        }
    }
    
    /**
     *  As of release 0.7.12, any files inside the zip that have a .jar.pack or .war.pack suffix
     *  are transparently unpacked to a .jar or .war file using unpack200.
     *  Logs at WARN level to wrapper.log
     */
    public static boolean extractZip(File zipfile, File targetDir) {
        return extractZip(zipfile, targetDir, Log.WARN);
    }

    /**
      * Warning - do not call any new classes from here, or
      * update will crash the JVM.
      *
      * @param logLevel Log.WARN, etc.
      * @return true if it was copied successfully
      * @since 0.9.7
      */
    public static boolean extractZip(File zipfile, File targetDir, int logLevel) {
        int files = 0;
        ZipFile zip = null;
        try {
            final byte buf[] = new byte[8192];
            zip = new ZipFile(zipfile);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = (ZipEntry)entries.nextElement();
                if (entry.getName().contains("..")) {
                    System.err.println("ERROR: Refusing to extract a zip entry with '..' in it [" + entry.getName() + "]");
                    return false;
                }
                if (entry.getName().indexOf(0) >= 0) {
                    System.err.println("ERROR: Refusing to extract a zip entry with null in it [" + entry.getName() + "]");
                    return false;
                }
                File target = new File(targetDir, entry.getName());
                File parent = target.getParentFile();
                if ( (parent != null) && (!parent.exists()) ) {
                    boolean parentsOk = parent.mkdirs();
                    if (!parentsOk) {
                        if (logLevel <= Log.ERROR)
                            System.err.println("ERROR: Unable to create the parent dir for " + entry.getName() + ": [" + parent.getAbsolutePath() + "]");
                        return false;
                    }
                }
                if (entry.isDirectory()) {
                    if (!target.exists()) {
                        boolean created = target.mkdirs();
                        if (!created) {
                            if (logLevel <= Log.ERROR)
                                System.err.println("ERROR: Unable to create the directory [" + entry.getName() + "]");
                            return false;
                        } else if (logLevel <= Log.INFO) {
                            System.err.println("INFO: Creating directory [" + entry.getName() + "]");
                        }
                    }
                } else {
                    InputStream in = null;
                    FileOutputStream fos = null;
                    JarOutputStream jos = null;
                    try {
                        in = zip.getInputStream(entry);
                        if (entry.getName().endsWith(".jar.pack") || entry.getName().endsWith(".war.pack")) {
                            target = new File(targetDir, entry.getName().substring(0, entry.getName().length() - ".pack".length()));
                            jos = new JarOutputStream(new FileOutputStream(target));
                            unpack(in, jos);
                            if (logLevel <= Log.INFO)
                                System.err.println("INFO: File [" + entry.getName() + "] extracted and unpacked");
                        } else {
                            fos = new FileOutputStream(target);
                            // We do NOT use DataHelper.copy() because it loads new classes
                            // and causes the update to crash.
                            //DataHelper.copy(in, fos);
                            int read;
                            while ((read = in.read(buf)) != -1) {
                                   fos.write(buf, 0, read);
                            }   
                            if (logLevel <= Log.INFO)
                                System.err.println("INFO: File [" + entry.getName() + "] extracted");
                        }
                        files++;
                    } catch (IOException ioe) {
                        if (logLevel <= Log.ERROR) {
                            System.err.println("ERROR: Error extracting the zip entry (" + entry.getName() + ')');
                            if (ioe.getMessage() != null && ioe.getMessage().indexOf("CAFED00D") >= 0)
                                System.err.println("This may be caused by a packed library that requires Java 1.6, your Java version is: " +
                                                   System.getProperty("java.version"));
                            ioe.printStackTrace();
                        }
                        return false;
                    } catch (Exception e) {
                        // Oracle unpack() should throw an IOE but other problems can happen, e.g:
                        // java.lang.reflect.InvocationTargetException
                        // Caused by: java.util.zip.ZipException: duplicate entry: xxxxx
                        if (logLevel <= Log.ERROR) {
                            System.err.println("ERROR: Error extracting the zip entry (" + entry.getName() + ')');
                            e.printStackTrace();
                        }
                        return false;
                    } finally {
                        try { if (in != null) in.close(); } catch (IOException ioe) {}
                        try { if (fos != null) fos.close(); } catch (IOException ioe) {}
                        try { if (jos != null) jos.close(); } catch (IOException ioe) {}
                    }
                }
            }
            return true;
        } catch (IOException ioe) {
            if (logLevel <= Log.ERROR) {
                System.err.println("ERROR: Unable to extract the zip file");
                ioe.printStackTrace();
            }
            return false;
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (IOException ioe) {}
            }
            if (files > 0 && logLevel <= Log.WARN)
                System.err.println("INFO: " + files + " files extracted to " + targetDir);
        }
    }
    
    /**
     * Verify the integrity of a zipfile.
     * There doesn't seem to be any library function to do this,
     * so we basically go through all the motions of extractZip() above,
     * unzipping everything but throwing away the data.
     *
     * Todo: verify zip header? Although this would break the undocumented
     * practice of renaming the i2pupdate.sud file to i2pupdate.zip and
     * letting the unzip method skip over the leading 56 bytes of
     * "junk" (sig and version)
     *
     * @return true if ok
     */
    public static boolean verifyZip(File zipfile) {
        ZipFile zip = null;
        try {
            byte buf[] = new byte[16*1024];
            zip = new ZipFile(zipfile);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            boolean p200TestRequired = true;
            while (entries.hasMoreElements()) {
                ZipEntry entry = (ZipEntry)entries.nextElement();
                if (entry.getName().indexOf("..") != -1) {
                    //System.err.println("ERROR: Refusing to extract a zip entry with '..' in it [" + entry.getName() + "]");
                    return false;
                }
                if (entry.isDirectory()) {
                    // noop
                } else {
                    if (p200TestRequired &&
                        (entry.getName().endsWith(".jar.pack") || entry.getName().endsWith(".war.pack"))) {
                        if (!isPack200Supported()) {
                            System.err.println("ERROR: Zip verify failed, your JVM does not support unpack200");
                            return false;
                        }
                        p200TestRequired = false;
                    }
                    try {
                        InputStream in = zip.getInputStream(entry);
                        while ( (in.read(buf)) != -1) {
                            // throw the data away
                        }
                        //System.err.println("INFO: File [" + entry.getName() + "] extracted");
                        in.close();
                    } catch (IOException ioe) {
                        //System.err.println("ERROR: Error extracting the zip entry (" + entry.getName() + "]");
                        //ioe.printStackTrace();
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException ioe) {
            //System.err.println("ERROR: Unable to extract the zip file");
            //ioe.printStackTrace();
            return false;
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (IOException ioe) {}
            }
        }
    }
    
    /**
     * Public since 0.8.3
     * @since 0.8.1
     */
    public static boolean isPack200Supported() {
        try {
            Class.forName("java.util.jar.Pack200", false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (Exception e) {}
        try {
            Class.forName("org.apache.harmony.unpack200.Archive", false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (Exception e) {}
        return false;
    }

    private static boolean _failedOracle;
    private static boolean _failedApache;

    /**
     * Unpack using either Oracle or Apache's unpack200 library,
     * with the classes discovered at runtime so neither is required at compile time.
     *
     * Caller must close streams
     * @throws IOException on unpack error or if neither library is available.
     *         Will not throw ClassNotFoundException.
     * @throws org.apache.harmony.pack200.Pack200Exception which is not an IOException
     * @throws java.lang.reflect.InvocationTargetException on duplicate zip entries in the packed jar
     * @since 0.8.1
     */
    private static void unpack(InputStream in, JarOutputStream out) throws Exception {
        // For Sun, OpenJDK, IcedTea, etc, use this
        //Pack200.newUnpacker().unpack(in, out);
        if (!_failedOracle) {
            try {
                Class<?> p200 = Class.forName("java.util.jar.Pack200", true, ClassLoader.getSystemClassLoader());
                Method newUnpacker = p200.getMethod("newUnpacker");
                Object unpacker = newUnpacker.invoke(null,(Object[])  null);
                Method unpack = unpacker.getClass().getMethod("unpack", InputStream.class, JarOutputStream.class);
                // throws IOException
                unpack.invoke(unpacker, new Object[] {in, out});
                return;
            } catch (ClassNotFoundException e) {
                _failedOracle = true;
                //e.printStackTrace();
            } catch (NoSuchMethodException e) {
                _failedOracle = true;
                //e.printStackTrace();
            }
        }

        // ------------------
        // For Apache Harmony or if you put its pack200.jar in your library directory use this
        //(new Archive(in, out)).unpack();
        if (!_failedApache) {
            try {
                Class<?> p200 = Class.forName("org.apache.harmony.unpack200.Archive", true, ClassLoader.getSystemClassLoader());
                Constructor<?> newUnpacker = p200.getConstructor(InputStream.class, JarOutputStream.class);
                Object unpacker = newUnpacker.newInstance(in, out);
                Method unpack = unpacker.getClass().getMethod("unpack");
                // throws IOException or Pack200Exception
                unpack.invoke(unpacker, (Object[]) null);
                return;
            } catch (ClassNotFoundException e) {
                _failedApache = true;
                //e.printStackTrace();
            } catch (NoSuchMethodException e) {
                _failedApache = true;
                //e.printStackTrace();
            }
        }

        // ------------------
        // For gcj, gij, etc., use this
        throw new IOException("Unpack200 not supported");
    }

    /**
     * Read in the last few lines of a (newline delimited) textfile, or null if
     * the file doesn't exist.  
     *
     * Warning - this inefficiently allocates a StringBuilder of size maxNumLines*80,
     *           so don't make it too big.
     * Warning - converts \r\n to \n
     *
     * @param startAtBeginning if true, read the first maxNumLines, otherwise read
     *                         the last maxNumLines
     * @param maxNumLines max number of lines (or -1 for unlimited)
     * @return string or null; does not throw IOException.
     *
     */
    public static String readTextFile(String filename, int maxNumLines, boolean startAtBeginning) {
        File f = new File(filename);
        if (!f.exists()) return null;
        FileInputStream fis = null;
        BufferedReader in = null;
        try {
            fis = new FileInputStream(f);
            in = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            List<String> lines = new ArrayList<String>(maxNumLines > 0 ? maxNumLines : 64);
            String line = null;
            while ( (line = in.readLine()) != null) {
                lines.add(line);
                if ( (maxNumLines > 0) && (lines.size() >= maxNumLines) ) {
                    if (startAtBeginning)
                        break;
                    else
                        lines.remove(0);
                }
            }
            StringBuilder buf = new StringBuilder(lines.size() * 80);
            for (int i = 0; i < lines.size(); i++) {
                buf.append(lines.get(i)).append('\n');
            }
            return buf.toString();
        } catch (IOException ioe) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }
    
    /**
     * Dump the contents of the given path (relative to the root) to the output 
     * stream.  The path must not go above the root, either - if it does, it will
     * throw a FileNotFoundException
     *
     * Closes the OutputStream out on successful completion
     * but leaves it open when throwing IOE.
     */
    public static void readFile(String path, String root, OutputStream out) throws IOException {
        File rootDir = new File(root);
        while (path.startsWith("/") && (path.length() > 0) )
            path = path.substring(1);
        if (path.length() <= 0) throw new FileNotFoundException("Not serving up the root dir");
        File target = new File(rootDir, path);
        if (!target.exists()) throw new FileNotFoundException("Requested file does not exist: " + path);
        String targetStr = target.getCanonicalPath();
        String rootDirStr = rootDir.getCanonicalPath();
        if (!targetStr.startsWith(rootDirStr)) throw new FileNotFoundException("Requested file is outside the root dir: " + path);

        FileInputStream in = null;
        try {
            in = new FileInputStream(target);
            DataHelper.copy(in, out);
            try { out.close(); } catch (IOException ioe) {}
        } finally {
            if (in != null) 
                try { in.close(); } catch (IOException ioe) {}
        }
    }

    /**
      * @return true if it was copied successfully
      */
    public static boolean copy(String source, String dest, boolean overwriteExisting) {
        return copy(source, dest, overwriteExisting, false);
    }

    /**
      * @param quiet don't log fails to wrapper log if true
      * @return true if it was copied successfully
      */
    public static boolean copy(String source, String dest, boolean overwriteExisting, boolean quiet) {
        File src = new File(source);
        File dst = new File(dest);
        return copy(src, dst, overwriteExisting, quiet);
    }

    /**
      * @param quiet don't log fails to wrapper log if true
      * @return true if it was copied successfully
      * @since 0.8.8
      */
    public static boolean copy(File src, File dst, boolean overwriteExisting, boolean quiet) {
	if (dst.exists() && dst.isDirectory())
            dst = new File(dst, src.getName());
        
        if (!src.exists()) return false;
        if (dst.exists() && !overwriteExisting) return false;
        
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            // We do NOT use DataHelper.copy() because it's used in installer.jar
            // which does not contain DataHelper
            //DataHelper.copy(in, out);
            int read;
            byte buf[] = new byte[4096];
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return true;
        } catch (IOException ioe) {
            if (!quiet)
                ioe.printStackTrace();
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (IOException ioe) {}
            try { if (out != null) out.close(); } catch (IOException ioe) {}
        }
    }
    
    /**
     * Try to rename, if it doesn't work then copy and delete the old.
     * Always overwrites any existing "to" file.
     * Method moved from SingleFileNamingService.
     *
     * @return true if it was renamed / copied successfully
     * @since 0.8.8
     */
    public static boolean rename(File from, File to) {
        if (!from.exists())
            return false;
        boolean success = false;
        boolean isWindows = SystemVersion.isWindows();
        // overwrite fails on windows
        boolean exists = to.exists();
        if (!isWindows || !exists)
            success = from.renameTo(to);
        if (!success) {
            if (exists && to.delete())
                success = from.renameTo(to);
            if (!success) {
                // hard way
                success = copy(from, to, true, true);
                if (success)
                    from.delete();
            }
        }
        return success;
    }

    /**
     * Usage: FileUtil (delete path | copy source dest | rename from to | unzip path.zip)
     *
     */
    public static void main(String args[]) {
        if ( (args == null) || (args.length < 2) ) {
            System.err.println("Usage: delete path | copy source dest | rename from to | unzip path.zip");
            //testRmdir();
        } else if ("delete".equals(args[0])) {
            boolean deleted = FileUtil.rmdir(args[1], false);
            if (!deleted)
                System.err.println("Error deleting [" + args[1] + "]");
        } else if ("copy".equals(args[0])) {
            boolean copied = FileUtil.copy(args[1], args[2], false);
            if (!copied) 
                System.err.println("Error copying [" + args[1] + "] to [" + args[2] + "]");
        } else if ("unzip".equals(args[0])) {
            File f = new File(args[1]);
            File to = new File("tmp");
            to.mkdir();
            boolean copied = verifyZip(f);
            if (!copied) 
                System.err.println("Error verifying " + args[1]);
            copied = extractZip(f,  to);
            if (copied) 
                System.err.println("Unzipped [" + args[1] + "] to [" + to + "]");
            else
                System.err.println("Error unzipping [" + args[1] + "] to [" + to + "]");
        } else if ("rename".equals(args[0])) {
            boolean success = rename(new File(args[1]), new File(args[2]));
            if (!success) 
                System.err.println("Error renaming [" + args[1] + "] to [" + args[2] + "]");
        } else {
            System.err.println("Usage: delete path | copy source dest | rename from to | unzip path.zip");
        }
    }
    
  /*****
    private static void testRmdir() {
        File t = new File("rmdirTest/test/subdir/blah");
        boolean created = t.mkdirs();
        if (!t.exists()) throw new RuntimeException("Unable to create test");
        boolean deleted = FileUtil.rmdir("rmdirTest", false);
        if (!deleted) 
            System.err.println("FAIL: unable to delete rmdirTest");
        else
            System.out.println("PASS: rmdirTest deleted");
    }
   *****/
}
