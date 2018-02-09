package i2p.susi.util;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.Writer;

import net.i2p.data.DataHelper;

/**
 * Escape HTML on the fly.
 * Streaming version of DataHelper.escapeHTML(),
 * and we escape '-' too since we stick debugging stuff inside comments,
 * and '--' is disallowed inside comments.
 *
 * @since 0.9.34
 */
public class EscapeHTMLWriter extends FilterWriter {
    
    private static final String AMP = "&amp;";
    private static final String QUOT = "&quot;";
    private static final String LT = "&lt;";
    private static final String GT = "&gt;";
    private static final String APOS = "&apos;";
    private static final String MDASH = "&#45;";
    private static final String BR = "<br>\n";
    private static final String EMSP = "&emsp;";
    private static final String SP4 = "&nbsp;&nbsp;&nbsp; ";


    public EscapeHTMLWriter(Writer out) {
        super(out);
    }
    
    @Override
    public void write(int c) throws IOException {
        switch (c) {
            case '&':
                out.write(AMP);
                break;
            case '"':
                out.write(QUOT);
                break;
            case '<':
                out.write(LT);
                break;
            case '>':
                out.write(GT);
                break;
            case '\'':
                out.write(APOS);
                break;
            case '-':
                out.write(MDASH);
                break;
            case ' ':
                // this should be breaking but non-collapsing
                out.write(EMSP);
                break;
            case '\t':
                // this should be breaking but non-collapsing
                out.write(SP4);
                break;
            case '\r':
                break;
            case '\n':
                out.write(BR);
                break;
            default:
                out.write(c);
        }
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        for (int i = off; i < off + len; i++) {
            write(cbuf[i]);
        }
    }

    @Override
    public void write(String str, int off, int len) throws IOException {
        for (int i = off; i < off + len; i++) {
            write(str.charAt(i));
        }
    }

    /**
     *  Does nothing. Does not close the underlying writer.
     */
    @Override
    public void close() {}
}
