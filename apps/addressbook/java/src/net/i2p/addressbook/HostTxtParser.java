package net.i2p.addressbook;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import net.i2p.client.naming.HostTxtEntry;
import net.i2p.data.DataHelper;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;

/**
 * Utility class providing methods to parse and write files in a hosts.txt file
 * format, and subscription file format.
 * 
 * @since 0.9.26 modified from ConfigParser, public since 0.9.55 for CLI
 */
public class HostTxtParser {

    private static final boolean isWindows = SystemVersion.isWindows();

    /**
     * Return a Map using the contents of BufferedReader input. input must have
     * a single key, value pair on each line, in the format: key=value. Lines
     * starting with '#' or ';' are considered comments, and ignored. Lines that
     * are obviously not in the format key=value are also ignored.
     * The key is converted to lower case.
     * 
     * Returned map will not contain null ("remove") entries.
     * 
     * @param input
     *            A BufferedReader with lines in key=value format to parse into
     *            a Map.
     * @return A Map containing the key, value pairs from input.
     * @throws IOException
     *             if the BufferedReader cannot be read.
     *  
     */
    private static Map<String, HostTxtEntry> parse(BufferedReader input) throws IOException {
        try {
            Map<String, HostTxtEntry> result = new HashMap<String, HostTxtEntry>();
            String inputLine;
            while ((inputLine = input.readLine()) != null) {
                HostTxtEntry he = parse(inputLine, false);
                if (he == null)
                    continue;
                result.put(he.getName(), he);
            }
            return result;
        } finally {
            try { input.close(); } catch (IOException ioe) {}
        }
    }

    /**
     * Return a HostTxtEntry from the contents of the inputLine.
     * 
     * @param inputLine key=value[#!k1=v1#k2=v2...]
     * @param allowCommandOnly if true, a line starting with #! will return
     *                         a HostTxtEntry with a null name and dest and non-null props.
     *                         If false, these lines will return null.
     * @return null if no entry found or on error
     */
    public static HostTxtEntry parse(String inputLine, boolean allowCommandOnly) {
        if (inputLine.startsWith(";"))
            return null;
        int comment = inputLine.indexOf('#');
        String kv;
        String sprops;
        if (comment >= 0) {
            int shebang = inputLine.indexOf(HostTxtEntry.PROPS_SEPARATOR);
            if (shebang == comment && shebang + 2 < inputLine.length()) {
                if (comment == 0 && !allowCommandOnly)
                    return null;
                sprops = inputLine.substring(shebang + 2);
            } else {
                if (comment == 0)
                    return null;
                sprops = null;
            }
            kv = inputLine.substring(0, comment);
        } else {
            sprops = null;
            kv = inputLine;
        }
        String name, dest;
        if (comment != 0) {
            // we have a name=dest
            String[] splitLine = DataHelper.split(kv, "=", 2);
            if (splitLine.length < 2)
                return null;
            name = splitLine[0].trim().toLowerCase(Locale.US);
            dest = splitLine[1].trim();
            if (name.length() == 0 || dest.length() == 0)
                return null;
        } else {
            // line starts with #!, rv will contain props only
            name = null;
            dest = null;
        }
        HostTxtEntry he;
        if (sprops != null) {
            try {
                he = new HostTxtEntry(name, dest, sprops);
            } catch (IllegalArgumentException iae) {
                return null;
            }
        } else {
            he = new HostTxtEntry(name, dest);
        }
        return he;
    }

    /**
     * Return a Map using the contents of the File file. See parse(BufferedReader)
     * for details of the input format.
     * 
     * Returned map will not contain null ("remove") entries.
     * 
     * @param file
     *            A File to parse.
     * @return A Map containing the key, value pairs from file.
     * @throws IOException
     *             if file cannot be read.
     */
    public static Map<String, HostTxtEntry> parse(File file) throws IOException {
        FileInputStream fileStream = null;
        try {
            fileStream = new FileInputStream(file);
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    fileStream, "UTF-8"));
            Map<String, HostTxtEntry> rv = parse(input);
            return rv;
        } finally {
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException ioe) {}
            }
        }
    }

    /**
     * Return a Map using the contents of the File file. If file cannot be read,
     * use map instead, and write the result to where file should have been.
     * 
     * Returned map will not contain null ("remove") entries.
     * 
     * @param file
     *            A File to attempt to parse.
     * @param map
     *            A Map containing values to use as defaults.
     * @return A Map containing the key, value pairs from file, or if file
     *         cannot be read, map.
     */
    public static Map<String, HostTxtEntry> parse(File file, Map<String, HostTxtEntry> map) {
        Map<String, HostTxtEntry> result;
        try {
            result = parse(file);
            for (Map.Entry<String, HostTxtEntry> entry : map.entrySet()) {
                if (!result.containsKey(entry.getKey()))
                    result.put(entry.getKey(), entry.getValue());
            }
        } catch (IOException exp) {
            result = map;
            try {
                write(result, file);
            } catch (IOException exp2) {
            }
        }
        return result;
    }

    /**
     * Write contents of Map map to BufferedWriter output. Output is written
     * with one key, value pair on each line, in the format: key=value.
     * 
     * @param map
     *            A Map to write to output.
     * @param output
     *            A BufferedWriter to write the Map to.
     * @throws IOException
     *             if the BufferedWriter cannot be written to.
     */
    private static void write(Map<String, HostTxtEntry> map, BufferedWriter output) throws IOException {
        try {
            for (Map.Entry<String, HostTxtEntry> entry : map.entrySet()) {
                entry.getValue().write(output);
            }
        } finally {
            try { output.close(); } catch (IOException ioe) {}
        }
    }

    /**
     * Write contents of Map map to the File file. Output is written
     * with one key, value pair on each line, in the format: key=value.
     * Write to a temp file in the same directory and then rename, to not corrupt
     * simultaneous accesses by the router. Except on Windows where renameTo()
     * will fail if the target exists.
     *
     * @param map
     *            A Map to write to file.
     * @param file
     *            A File to write the Map to.
     * @throws IOException
     *             if file cannot be written to.
     */
    public static void write(Map<String, HostTxtEntry> map, File file) throws IOException {
        boolean success = false;
        if (!isWindows) {
            File tmp = SecureFile.createTempFile("temp-", ".tmp", file.getAbsoluteFile().getParentFile());
            write(map, new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(tmp), "UTF-8")));
            success = tmp.renameTo(file);
            if (!success) {
                tmp.delete();
                //System.out.println("Warning: addressbook rename fail from " + tmp + " to " + file);
            }
        }
        if (!success) {
            // hmm, that didn't work, try it the old way
            write(map, new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(file), "UTF-8")));
        }
    }

    /**
     * Usage: HostTxtParser [-q] validate example.i2p=b64dest[#!key1=val1#key2=val2]
     */
    public static void main(String[] args) throws Exception {
        boolean quiet = false;
        if (args.length > 0 && args[0].equals("-q")) {
            quiet = true;
            args = java.util.Arrays.copyOfRange(args, 1, args.length);
        }
        if (args.length != 2 || !args[0].equals("validate")) {
            System.err.println("Usage: HostTxtParser validate example.i2p=b64dest[#!key1=val1#key2=val2]");
            System.exit(1);
        }
        HostTxtEntry e = parse(args[1].trim(), false);
        if (e == null) {
            if (!quiet)
                System.err.println("Bad format");
            System.exit(2);
        }
        if (!e.hasValidSig()) {
            if (!quiet)
                System.err.println("Bad signature");
            System.exit(3);
        }
        Properties p = e.getProps();
        if (p != null) {
            if (p.containsKey(HostTxtEntry.PROP_ACTION) ||
                p.containsKey(HostTxtEntry.PROP_OLDDEST) ||
                p.containsKey(HostTxtEntry.PROP_OLDNAME) ||
                p.containsKey(HostTxtEntry.PROP_OLDSIG)) {
                if (!e.hasValidSig()) {
                    if (!quiet)
                        System.err.println("Bad inner signature");
                    System.exit(4);
                }
            }
        }
        if (!quiet)
            System.err.println("Good signature for " + e.getName());
        System.exit(0);
    }

/****
    public static void test(String[] args) throws Exception {
        File f = new File("tmp-hosts.txt");
        Map<String, HostTxtEntry> map = parse(f);
        for (HostTxtEntry e : map.values()) {
            System.out.println("Host: " + e.getName() +
                               "\nDest: " + e.getDest() +
                               "\nAction: " + (e.getProps() != null ? e.getProps().getProperty("action") : "(none)") +
                               "\nValid Inner? " + e.hasValidInnerSig() +
                               "\nValid? " + e.hasValidSig() +
                               '\n');
        }
    }
****/
}
