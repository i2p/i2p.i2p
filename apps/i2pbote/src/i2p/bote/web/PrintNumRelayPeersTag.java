package i2p.bote.web;

import i2p.bote.I2PBote;

import java.io.IOException;

import javax.servlet.jsp.JspWriter;
import javax.servlet.jsp.PageContext;
import javax.servlet.jsp.tagext.SimpleTagSupport;

import net.i2p.util.Log;

public class PrintNumRelayPeersTag extends SimpleTagSupport {
    private Log log = new Log(PrintNumRelayPeersTag.class);

    public void doTag() {
        PageContext pageContext = (PageContext) getJspContext();
        JspWriter out = pageContext.getOut();
        
        try {
            out.println(I2PBote.getInstance().getNumRelayPeers());
        } catch (IOException e) {
            log.error("Can't write output to HTML page", e);
        }
    }
}