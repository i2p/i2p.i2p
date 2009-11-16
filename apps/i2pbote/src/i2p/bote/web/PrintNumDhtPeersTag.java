package i2p.bote.web;

import i2p.bote.I2PBote;

import java.io.IOException;

import javax.servlet.jsp.JspWriter;
import javax.servlet.jsp.PageContext;
import javax.servlet.jsp.tagext.SimpleTagSupport;

import net.i2p.util.Log;

public class PrintNumDhtPeersTag extends SimpleTagSupport {
    private Log log = new Log(PrintNumDhtPeersTag.class);

    public void doTag() {
        PageContext pageContext = (PageContext) getJspContext();
        JspWriter out = pageContext.getOut();
        
        try {
            out.println(I2PBote.getInstance().getNumDhtPeers());
        } catch (IOException e) {
            log.error("Can't write output to HTML page", e);
        }
    }
}