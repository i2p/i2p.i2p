package i2p.susi.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import net.i2p.data.DataHelper;

/**
 * Escape HTML on the fly.
 * Streaming version of DataHelper.escapeHTML(),
 * and we escape '-' too since we stick debugging stuff inside comments,
 * and '--' is disallowed inside comments.
 *
 * @since 0.9.34
 */
public class EscapeHTMLOutputStream extends FilterOutputStream {
    
    private static final byte[] AMP = DataHelper.getASCII("&amp;");
    private static final byte[] QUOT = DataHelper.getASCII("&quot;");
    private static final byte[] LT = DataHelper.getASCII("&lt;");
    private static final byte[] GT = DataHelper.getASCII("&gt;");
    private static final byte[] APOS = DataHelper.getASCII("&apos;");
    private static final byte[] MDASH = DataHelper.getASCII("&#45;");
    private static final byte[] BR = DataHelper.getASCII("<br>\n");


    public EscapeHTMLOutputStream(OutputStream out) {
        super(out);
    }
    
    @Override
    public void write(int val) throws IOException {
        switch (val) {
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
            case '\r':
                break;
            case '\n':
                out.write(BR);
                break;
            default:
                out.write(val);
        }
    }

    /**
     *  Does nothing. Does not close the underlying stream.
     */
    @Override
    public void close() {}
}
