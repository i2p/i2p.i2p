package net.i2p.util;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.i2p.I2PAppContext;

/**
 *  Translate.
 *
 *  Strings are tagged with _("translateme")
 *  or _("translate {0} me", "foo")
 *
 *  Max two parameters.
 *  String and parameters must be double-quoted (no ngettext, no tagged parameters).
 *  Escape quotes inside quote with \".
 *  Commas and spaces between args are optional.
 *  Entire tag (from '_' to ')') must be on one line.
 *  Multiple tags allowed on one line.
 *
 *  Also will extract strings to a dummy java file for postprocessing by xgettext - see main().
 *
 *  @since 0.9.8
 */
public class TranslateReader extends FilterReader {

    /** all states may transition to START */
    private enum S {
        START,
        /** next state LPAREN */
        UNDER,
        /** next state QUOTE */
        LPAREN,
        /** next state LPAREN or BACK */
        QUOTE,
        /** next state QUOTE */
        BACK
    }

    private final String _bundle;
    private final I2PAppContext _ctx;
    /** parse in progress */
    private final StringBuilder _inBuf;
    /** parsed and translated */
    private final StringBuilder _outBuf;
    /** pending string or parameter for translation */
    private final StringBuilder _argBuf;
    /** parsed string and parameters */
    private final List<String> _args;
    private S _state = S.START;
    private TagHook _hook;

    private static final int MAX_ARGS = 9;

    /**
     *  @param bundle may be null for tagging only
     *  @param in UTF-8
     */
    public TranslateReader(I2PAppContext ctx, String bundle, InputStream in) throws IOException {
        this(ctx, bundle, new BufferedReader(new InputStreamReader(in, "UTF-8")));
    }

    /**
     *  @param bundle may be null for tagging only
     *  @since 0.9.34
     */
    public TranslateReader(I2PAppContext ctx, String bundle, Reader in) throws IOException {
        super(in);
        _ctx = ctx;
        _bundle = bundle;
        _args = new ArrayList<String>(4);
        _inBuf = new StringBuilder(64);
        _outBuf = new StringBuilder(64);
        _argBuf = new StringBuilder(64);
    }

    @Override
    public int read() throws IOException {
        int rv = popit();
        if (rv > 0)
            return rv;
        return parse();
    }

    private int parse() throws IOException {
        while (true) {
            int c = in.read();
            if (c >= 0)
                pushit((char) c);
            //System.err.println("State: " + _state + " char: '" + ((char)c) + "'");
        
            switch (c) {
                case -1:
                case '\r':
                case '\n':
                    return flushit();

                case '_':
                    switch (_state) {
                        case START:
                            _state = S.UNDER;
                            break;
                        case BACK:
                            _state = S.QUOTE;
                            // fall thru
                        case QUOTE:
                            _argBuf.append((char) c);
                            break;
                        default:
                            return flushit();
                    }
                    break;

                case '(':
                    switch (_state) {
                        case UNDER:
                            _args.clear();
                            _state = S.LPAREN;
                            break;
                        case BACK:
                            _state = S.QUOTE;
                            // fall thru
                        case QUOTE:
                            _argBuf.append((char) c);
                            break;
                        default:
                            return flushit();
                    }
                    break;

                case '"':
                    switch (_state) {
                        case LPAREN:
                            // got an opening quote for a parameter
                            if (_args.size() >= MAX_ARGS)
                                return flushit();
                            _argBuf.setLength(0);
                            _state = S.QUOTE;
                            break;
                        case BACK:
                            _argBuf.append((char) c);
                            _state = S.QUOTE;
                            break;
                        case QUOTE:
                            // got a closing quote for a parameter
                            _args.add(_argBuf.toString());
                            _state = S.LPAREN;
                            break;
                        default:
                            return flushit();
                    }
                    break;

                case '\\':
                    switch (_state) {
                        case QUOTE:
                            _state = S.BACK;
                            break;
                        case BACK:
                            _argBuf.append((char) c);
                            _state = S.QUOTE;
                            break;
                        default:
                            return flushit();
                    }
                    break;

                case ' ':
                case '\t':
                case ',':
                    switch (_state) {
                        case BACK:
                            _state = S.QUOTE;
                            // fall thru
                        case QUOTE:
                            _argBuf.append((char) c);
                            break;
                        case LPAREN:
                            // ignore whitespace and commas between args
                            break;
                        default:
                            return flushit();
                    }
                    break;

                case ')':
                    switch (_state) {
                        case BACK:
                            _state = S.QUOTE;
                            // fall thru
                        case QUOTE:
                            _argBuf.append((char) c);
                            break;
                        case LPAREN:
                            // Finally, we have something to translate!
                            translate();
                            return popit();
                        default:
                            return flushit();
                    }
                    break;

                default:
                    switch (_state) {
                        case BACK:
                            _state = S.QUOTE;
                            // fall thru
                        case QUOTE:
                            _argBuf.append((char) c);
                            break;
                        default:
                            return flushit();
                    }
                    break;
            }
        }
    }

    @Override
    public int read(char cbuf[], int off, int len) throws IOException {
        for (int i = 0; i < len; i++) {
            int c = read();
            if (c < 0) {
                if (i == 0)
                    return -1;
                return i;
            }
            cbuf[off + i] = (char) c;
        }
        return len;
    }

    @Override
    public long skip(long n) throws IOException {
        for (long i = 0; i < n; i++) {
            int c = read();
            if (c < 0) {
                if (i == 0)
                    return -1;
                return i;
            }
        }
        return n;
    }

    @Override
    public boolean ready() throws IOException {
        return _outBuf.length() > 0 || _inBuf.length() > 0 ||in.ready();
    }

    @Override
    public void close() throws IOException {
        _inBuf.setLength(0);
        _outBuf.setLength(0);
        _state = S.START;
        in.close();
    }

    @Override
    public void mark(int readLimit) {}

    @Override
    public void reset() throws IOException {
        throw new IOException();
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    /**
     *  put in the pending parse buf
     */
    private void pushit(char c) {
        _inBuf.append(c);
    }

    /**
     *  flush _inBuf to _outBuf,
     *  reset state,
     *  and return next char or -1
     */
    private int flushit() {
        _state = S.START;
        if (_inBuf.length() > 0) {
            _outBuf.append(_inBuf);
            _inBuf.setLength(0);
        }
        return popit();
    }

    /**
     *  return next char from _outBuf or -1
     */
    private int popit() {
        if (_outBuf.length() > 0) {
            int rv = _outBuf.charAt(0) & 0xffff;
            _outBuf.deleteCharAt(0);
            return rv;
        }
        return -1;
    }

    /**
     *  clear _inBuf, translate _args to _outBuf,
     *  reset state
     */
    private void translate() {
        //System.err.println("Translating: " + _args.toString());
        int argCount = _args.size();
        if (argCount <= 0 || argCount > MAX_ARGS) {
            flushit();
            return;
        }
        _state = S.START;
        _inBuf.setLength(0);
        if (_hook != null) {
            _hook.tag(_args);
            return;
        }
        String tx = null;
        if (argCount == 1)
            tx = Translate.getString(_args.get(0), _ctx, _bundle);
        else
            tx = Translate.getString(_args.get(0), _ctx, _bundle, _args.subList(1, _args.size()).toArray());
        _outBuf.append(tx);
    }

    private interface TagHook extends Closeable {
        public void tag(List<String> args);
    }

    private static class Tagger implements TagHook {
        private final PrintStream _out;
        private final String _name;
        private int _count;

        public Tagger(String file) throws IOException {
            _name = file;
            _out = new PrintStream(file, "UTF-8");
            _out.println("// Automatically generated, do not edit");
            _out.println("package dummy;");
            _out.println("class Dummy {");
            _out.println("    void dummy() {");
        }

        public void tag(List<String> args) {
            if (args.size() <= 0)
                return;
            _out.print("\t_t(");
            for (int i = 0; i < args.size(); i++) {
                if (i > 0)
                    _out.print(", ");
                _out.print('"');
                _out.print(args.get(i).replace("\"", "\\\""));
                _out.print('"');
            }
            _out.println(");");
            _count++;
        }

        public void close() throws IOException {
            _out.println("    }");
            _out.println("}");
            if (_out.checkError())
                throw new IOException();
            _out.close();
            System.out.println(_count + " strings written to " + _name);
        }
    }

    /**
     *  Do not comment out, used to extract tags as a part of the build process.
     */
    public static void main(String[] args) {
        try {
            if (args.length >= 2 && args[0].equals("test"))
                test(args[1]);
            else if (args.length >= 2 && args[0].equals("tag"))
                tag(args);
            else
                System.err.println("Usage:\n" +
                                   "\ttest file (output to stdout)\n" +
                                   "\ttag file (output to file.java)\n" +
                                   "\ttag dir outfile\n" +
                                   "\ttag file1 [file2...] outfile");
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    private static void test(String file) throws IOException {
        TranslateReader r = null;
        try {
            r = new TranslateReader(I2PAppContext.getGlobalContext(),
                                    "net.i2p.router.web.messages",
                                    new FileInputStream(file));
            int c;
            while ((c = r.read()) >= 0) {
                System.out.print((char)c);
            }
            System.out.flush();
        } finally {
            if (r != null) try { r.close(); } catch (IOException ioe) {}
        }
    }

    /** @param files ignore 0 */
    private static void tag(String[] files) throws IOException {
        char[] buf = new char[256];
        String outfile;
        List<String> filelist;
        if (files.length == 2) {
            outfile = files[1] + ".java";
            filelist = Collections.singletonList(files[1]);
        } else if (files.length == 3 && (new File(files[1])).isDirectory()) {
            outfile = files[2];
            File dir = new File(files[1]);
            File[] listing = dir.listFiles();
            if (listing == null)
                throw new IOException();
            filelist = new ArrayList<String>(listing.length);
            for (int i = 0; i < listing.length; i++) {
                File f = listing[i];
                if (!f.isDirectory())
                    filelist.add(f.getAbsolutePath());
            }
        } else {
            outfile = files[files.length - 1];
            filelist = Arrays.asList(files).subList(1, files.length - 1);
        }
        TagHook tagger = null;
        try {
            tagger = new Tagger(outfile);
            for (String file : filelist) {
                TranslateReader r = null;
                try {
                    r = new TranslateReader(I2PAppContext.getGlobalContext(),
                                            null,
                                            new FileInputStream(file));
                    r._hook = tagger;
                    while (r.read(buf, 0, buf.length) >= 0) {
                        // throw away output
                    }
                } finally {
                    if (r != null) r.close();
                }
            }
        } finally {
            if (tagger != null) tagger.close();
        }
    }
}
