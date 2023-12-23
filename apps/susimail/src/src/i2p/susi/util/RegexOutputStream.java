package i2p.susi.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Replace patterns with a simple regex on the fly.
 *
 * @since 0.9.62
 */
public class RegexOutputStream extends FilterOutputStream {
    
    private final String match, repl;
    /** parse in progress */
    private final StringBuilder buf;
    private final String noMatch;
    private final Log _log;
    private boolean found;
    int idx;

    /**
     *  @param out MUST be buffered because this writes one byte at a time
     *  @param pattern the only special char recognized is '*' and cannot be at the beginning or end
     *                 or have two in a row. ASCII-only, no UTF-8.
     *  @param replace ASCII-only, no UTF-8.
     *  @param onNoMatch force output of this at the end if no replacement made, or null
     */
    public RegexOutputStream(OutputStream out, String pattern, String replace, String onNoMatch) {
        super(out);
        if (pattern.length() == 0 || pattern.startsWith("*") || pattern.endsWith("*") || pattern.contains("**"))
            throw new IllegalArgumentException();
        match = pattern;
        repl = replace;
        noMatch = onNoMatch;
        buf = new StringBuilder(64);
	_log = I2PAppContext.getGlobalContext().logManager().getLog(RegexOutputStream.class);
        if (_log.shouldDebug())
            _log.debug("New regex replace '" + match + "' with '" + repl + "'");
    }
    
    @Override
    public void write(int val) throws IOException {
        char c = (char) val;
        char m = match.charAt(idx);
        if (c == '\r' || c == '\n') {
            flushit();
            out.write(val);
        } else if (m == '*') {
            pushit(c);
            char d = match.charAt(idx + 1);
            if (c == d) {
                idx += 2;
                if (idx == match.length()) {
                    replaceit();
                    found = true;
                }
            }
        } else if (m == c) {
            pushit(c);
            idx++;
            if (idx == match.length()) {
                replaceit();
                found = true;
            }
        } else {
            flushit();
            out.write(val);
        }
    }


    /**
     *  put in the pending parse buf
     */
    private void pushit(char c) {
        buf.append(c);
        //if (_log.shouldDebug())
        //    _log.debug("Push, buf now '" + buf + "'");
    }

    /**
     *  flush buf to out, start over
     */
    private void flushit() throws IOException {
        int len = buf.length();
        if (len > 0) {
            for (int i = 0; i < len; i++) {
                out.write(buf.charAt(i));
            }
            clearit();
        }
        //if (_log.shouldDebug())
        //    _log.debug("Flush");
    }
    /**
     *  Throw out inbuf, output replacement, start over
     */
    private void replaceit() throws IOException {
        int len = repl.length();
        if (len > 0) {
            for (int i = 0; i < len; i++) {
                out.write(repl.charAt(i));
            }
        }
        if (_log.shouldInfo())
            _log.info("Replaced '" + match + "' with '" + repl + "' buf: '" + buf + "'");
        clearit();
    }

    /**
     *  Start over
     */
    private void clearit() {
        buf.setLength(0);
        idx = 0;
    }

    @Override
    public void close() throws IOException {
        flushit();
        if (!found && noMatch != null) {
            if (_log.shouldInfo())
                _log.info("No match, appending '" + noMatch + "'");
            out.write(DataHelper.getASCII(noMatch));
        }
        super.close();
    }

/****
*/
    public static void main(String[] args) throws Exception {
        OutputStream out = new RegexOutputStream(System.out, args[0], args[1], null);
        net.i2p.data.DataHelper.copy(System.in, out);
        out.flush();
    }
/*
****/
}
