package i2p.bote.web;

import javax.servlet.jsp.tagext.SimpleTagSupport;

public class CheckMailTag extends SimpleTagSupport {

    public void doTag() {
        JSPHelper.checkForMail();
    }
}