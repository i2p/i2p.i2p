/*
 * HtmlPage.java
 *
 * Created on April 8, 2005, 8:22 PM
 */

package net.i2p.aum.http;

import java.util.*;

import net.i2p.aum.*;

/**
 * Framework for building up a page of HTML by method calls alone, breaking
 * every design rule by enmeshing content, presentation and logic
 */
public class HtmlPage {

    public String dtd = "<!DOCTYPE HTML PUBLIC "
        +"\"-//W3C//DTD HTML 4.01 Transitional//EN\" "
        +"\"http://www.w3.org/TR/html4/loose.dtd\">";

    public Tag page;
    public Tag head;
    public Tag body;
    DupHashtable cssSettings;
    
    /** Creates a new HtmlPage object */
    public HtmlPage() {
        page = new Tag("html");
        head = new Tag(page, "head");
        body = new Tag(page, "body");
        cssSettings = new DupHashtable();
    }

    /** renders out the whole page into a single string */
    public String toString() {

        // embed stylesheet, if non-empty
        if (cssSettings.size() > 0) {
            Tag t1 = head.nest("style type=\"text/css\"");
            t1.raw("<!--\n");
            Tag cssTag = t1.nest();
            t1.raw("-->\n");
            Enumeration elems = cssSettings.keys();
            while (elems.hasMoreElements()) {
                String name = (String)elems.nextElement();
                cssTag.raw(name + " { ");
                Enumeration items = cssSettings.get(name).elements();
                while (items.hasMoreElements()) {
                    String item = (String)items.nextElement();
                    cssTag.raw(item+";");
                }
                cssTag.raw(" }\n");
            }
        }

        // now render out the whole page
        return dtd + "\n" + page;
    }

    /** adds a setting to the page's embedded stylesheet */
    public HtmlPage css(String tag, String item, String val) {
        return css(tag, item+":"+val);
    }
    
    /** adds a setting to the page's embedded stylesheet */
    public HtmlPage css(String tag, String setting) {
        cssSettings.put(tag, setting);
        return this;
    }
}
