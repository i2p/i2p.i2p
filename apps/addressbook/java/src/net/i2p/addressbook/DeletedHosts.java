package net.i2p.addressbook;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;

/**
 *  Retrieve / store deleted hostnames and regexes
 *
 *  @since 0.9.70
 */
public class DeletedHosts {

    private final I2PAppContext _context;
    private final Log _log;
    private final File _file;
    // one big pattern for all entries
    private Pattern _pattern;

    private static final String BANFILE = "deleted.txt";
    private static final String HELP = "# Deleted hosts and ban regexes, one per line\n" +
                                       "# Regex spec is at https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/regex/Pattern.html\n" +
                                       "# Except: Do not use boundary matchers ^ and $ -- any entry implies ^entry$\n" +
                                       "# Except: Dots are literal, do not escape with backslashes\n" +
                                       "# Except: * matches anything, not previous character\n" +
                                       "# All entries must end with .i2p -- use lower case only.\n" +
                                       "# Examples: example.i2p *.example.i2p *chan*.i2p\n" +
                                       "# Stop and restart susidns on /configwebapps in the console after editing this file manually\n";


    /**
     *  @param dir ~/.i2p/addressbook/ or similar
     */
    public DeletedHosts(File dir) {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(DeletedHosts.class);
        _file = new File(dir, BANFILE);
        load();
    }

    public boolean contains(String s) {
        synchronized(DeletedHosts.class) {
            if (_pattern == null)
                return false;
            return _pattern.matcher(s).matches();
        }
    }

    /**
     *  Sets _pattern
     */
    private void load() {
        synchronized(DeletedHosts.class) {
            if (!_file.exists()) {
                store(Collections.emptyList());
                return;
            }
            load(false);
        }
    }

    /**
     *  s to "(^s$)"
     *  adds backslash to dots
     *  converts * to [a-zA-Z0-9-]*
     *
     *  @param returnEntries if true, return file entries for a subsequent store()
     */
    private static String toPattern(String s) throws IllegalArgumentException {
        StringBuilder buf = new StringBuilder();
        buf.append("(^");
        char prev = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '^' || c == '$')
                continue;
            // dots are literal
            if (c == '.' && prev != '\\')
                buf.append('\\');
            // wildcard is for anything, not for previous char
            else if (c == '*' && prev != ']' && prev != ')')
                buf.append("[a-zA-Z0-9.-]");
            buf.append(c);
            prev = c;
        }
        buf.append("$)");
        String rv = buf.toString();
        // test compile the pattern by itself, throws IAE
        Pattern.compile(rv);
        return rv;
    }

    /**
     *  Sets _pattern
     *
     *  @param returnEntries if true, return file entries for a subsequent store()
     */
    private List<String> load(boolean returnEntries) {
        int count = 0;
        BufferedReader br = null;
        StringBuilder buf = new StringBuilder();
        List<String> rv = returnEntries ? new ArrayList<String>() : null;
        synchronized(DeletedHosts.class) {
            try {
                br = new BufferedReader(new InputStreamReader(
                		new FileInputStream(_file), "UTF-8"));
                String line = null;
                while ( (line = br.readLine()) != null) {
                    if (line.startsWith("#"))
                        continue;
                    line = line.trim();
                    if (line.length() <= 0)
                        continue;
                    try {
                        String s = toPattern(line);
                        if (buf.length() > 0)
                            buf.append('|');
                        buf.append(s);
                        if (returnEntries)
                            rv.add(line);
                        count++;
                    } catch (IllegalArgumentException iae) {
                        _log.error("Error reading entry", iae);
                    }
                }
            } catch (IOException ioe) {
                if (_log.shouldWarn() && _file.exists())
                    _log.warn("Error reading the file", ioe);
            } finally {
                if (br != null) try { br.close(); } catch (IOException ioe) {}
            }
            if (count > 0) {
                try {
                    _pattern = Pattern.compile(buf.toString());
                } catch (IllegalArgumentException iae) {
                    _pattern = null;
                    _log.error("Failed to compile pattern \"" + buf + '"', iae);
                }
            } else {
                _pattern = null;
            }
        }
        if (_log.shouldInfo()) {
            _log.info("Loaded " + count + " entries from " + _file);
            _log.info("Pattern is: " + buf);
        }
        return rv;
    }

    /**
     *  @return success
     */
    public boolean add(List<String> entries) {
        synchronized(DeletedHosts.class) {
            List<String> list = load(true);
            String pattern = _pattern != null ? _pattern.pattern() : "";
            StringBuilder buf = new StringBuilder(pattern);
            for (String e : entries) {
                try {
                    // test compile the pattern by itself, throws IAE
                    String s = toPattern(e);
                    if (buf.length() > 0)
                        buf.append('|');
                    buf.append(s);
                    list.add(e);
                } catch (IllegalArgumentException iae) {
                    _log.error("Failed adding  \"" + e + '"', iae);
                }
            }
            store(list);
            if (buf.length() > 0) {
                try {
                    _pattern = Pattern.compile(buf.toString());
                } catch (IllegalArgumentException iae) {
                    _pattern = null;
                    _log.error("Failed to compile pattern \"" + buf + '"', iae);
                }
            } else {
                _pattern = null;
            }
        }
        return true;
    }

    /**
     *  @return success
     */
    private boolean store(List<String> entries) {
        synchronized(DeletedHosts.class) {
            PrintWriter out = null;
            try {
                out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(_file), "UTF-8")));
                out.println("# Last saved: " + DataHelper.formatTime(System.currentTimeMillis()));
                out.print(HELP);
                for (String s : entries) {
                     out.println(s);
                }
                if (out.checkError())
                    throw new IOException("Failed write to " + _file);
            } catch (IOException ioe) {
                _log.error("Error writing the file", ioe);
                return false;
            } finally {
                if (out != null) out.close();
            }
        }
        if (_log.shouldInfo())
            _log.info("Stored " + entries.size() + " entries to " + _file);
        return true;
    }

/*
    public static void main(String[] args) {
        File dir = new File(".");
        DeletedHosts dh = new DeletedHosts(dir);
        dh.load();
        for (String s : args) {
            if (dh.contains(s))
                System.out.println(s + " is IN the banlist");
            else
                System.out.println(s + " is NOT in the banlist");
        }
    }
*/
}
