/*
 * MiniHttpRequestPage.java
 *
 * Created on April 13, 2005, 11:24 AM
 */

package net.i2p.aum.http;

/**
 *
 * @author  david
 */
public class MiniHttpRequestPage extends MiniHttpRequestHandler {
    
    /** HtmlPage object into which we can write response */
    protected HtmlPage page;

    /** the 'head' portion of our response page */
    protected Tag head;

    /** the 'body' portion of our response page */
    protected Tag body;

    public MiniHttpRequestPage(MiniHttpServer server, Object socket) throws Exception {
        super(server, socket, null);
        this.page = new HtmlPage();
        head = page.head;
        body = page.body;
    }

    /** Creates a new instance of MiniHttpRequestPage */
    public MiniHttpRequestPage(MiniHttpServer server, Object socket, Object arg) throws Exception {
        super(server, socket, arg);
        this.page = new HtmlPage();
        head = page.head;
        body = page.body;
    }
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {

    }
    
    public void on_GET() throws Exception {
    }
    
    public void on_POST() throws Exception {
    }
    
    public void on_RPC() throws Exception {
    }
    
    /** adds a string of text, or an Tag object, to the
     * body of the output page
     */
    public Tag add(String item) {
        return body.add(item);
    }
    public Tag add(Tag item) {
        return body.add(item);
    }

    /** sets up standard page's embedded stylesheet */
    public void addCss() {

        css("body", "font-family", "helvetica, arial, sans-serif");
        css("body", "color", "green");
        css("td", "vertical-align", "top");
        css("code", "font-family: courier, monospace; font-weight: bold");
        css(".border1, .pane1", "border-style: solid; border-width: 1px; border-color: blue");
        css(".pane1", "background-color: #f0f0ff");
        
    }

    /** adds a single CSS setting */
    public HtmlPage css(String tag, String item, String val) {
        return css(tag, item+":"+val);
    }

    /** adds a single CSS setting */
    public HtmlPage css(String tag, String setting) {
        page.css(tag, setting);
        return page;
    }

    /** renders this page to full html document */
    public String toString() {
        return page.toString();
    }

}
