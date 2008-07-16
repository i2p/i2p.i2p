/*
 * HtmlTag.java
 *
 * Created on April 8, 2005, 8:22 PM
 */

package net.i2p.aum.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Vector;

/**
 * Base class for building up quick-n-dirty HTML by code alone;
 * Breaks every design rule by enmeshing content, presentation and logic together
 * into java statements.
 */
public class Tag {
    
    static Vector nlOnOpen = new Vector();
    static {
        nlOnOpen.addElement("html");
        nlOnOpen.addElement("html");
        nlOnOpen.addElement("head");
        nlOnOpen.addElement("body");
        nlOnOpen.addElement("frameset");
        nlOnOpen.addElement("frame");
        nlOnOpen.addElement("script");
        nlOnOpen.addElement("blockquote");
        nlOnOpen.addElement("div");
        nlOnOpen.addElement("hr");
        nlOnOpen.addElement("ul");
        nlOnOpen.addElement("ol");
        nlOnOpen.addElement("table");
        nlOnOpen.addElement("caption");
        nlOnOpen.addElement("col");
        nlOnOpen.addElement("thead");
        nlOnOpen.addElement("tfoot");
        nlOnOpen.addElement("tbody");
        nlOnOpen.addElement("tr");
        nlOnOpen.addElement("form");
        nlOnOpen.addElement("applet");
        nlOnOpen.addElement("br");
        nlOnOpen.addElement("style");
        };

    static Vector nlOnClose = new Vector();
    static {
        nlOnClose.addElement("h1");
        nlOnClose.addElement("h2");
        nlOnClose.addElement("h3");
        nlOnClose.addElement("h4");
        nlOnClose.addElement("h5");
        nlOnClose.addElement("h6");
        nlOnClose.addElement("p");
        nlOnClose.addElement("pre");
        nlOnClose.addElement("li");
        nlOnClose.addElement("td");
        nlOnClose.addElement("th");
        nlOnClose.addElement("button");
        nlOnClose.addElement("input");
        nlOnClose.addElement("label");
        nlOnClose.addElement("select");
        nlOnClose.addElement("option");
        nlOnClose.addElement("textarea");
        nlOnClose.addElement("font");
        nlOnClose.addElement("iframe");
        nlOnClose.addElement("img");
        nlOnClose.addElement("br");
    }

    String open;
    String close;
    Vector attribs;
    Vector styles;
    Vector content;
    boolean breakBefore, breakAfter;
    public Tag parent = null;
    public Tag end = null;

    // -----------------------------------------------------
    // CONSTRUCTORS
    // -----------------------------------------------------

    /** Creates a new empty container tag */
    public Tag() {
        this((String)null);
    }

    /** Creates a new empty container tag, embedded in a parent tag */
    public Tag(Tag parent) {
        this(parent, null);
    }

    /**
     * Creates a new HtmlTag instance, adds to a parent
     */
    public Tag(Tag parent, String opentag) {
        this(opentag);
        parent.add(this);
        this.end = this.parent = parent;
    }

    /** Creates a new instance of HtmlTag */
    public Tag(String opentag) {

        content = new Vector();
        attribs = new Vector();
        styles = new Vector();

        if (opentag == null) {
            return;
        }

        String [] tagBits = opentag.split("\\s+", 2);
        open = tagBits[0];

        if (open.endsWith("/")) {
            open = open.substring(0, open.length()-1);
            close = "";
        }
        else {
            close = "</"+open+">";
        }

        if (tagBits.length > 1) {
            attribs.addElement(tagBits[1]);
        }

        breakBefore = nlOnOpen.contains(open);
        breakAfter = breakBefore || nlOnClose.contains(open);
    }

    // -----------------------------------------------------
    // METHODS FOR ADDING SPECIFIC HTML TAGS
    // -----------------------------------------------------
    
    /** insert a &lt;br&gt; on the fly */
    public Tag br() {
        return add("br/");
    }

    /** insert a &lt;hr&gt; on the fly */
    public Tag hr() {
        return add("hr/");
    }

    public Tag center() {
        return nest("center");
    }

    public Tag center(String attr) {
        return nest("center "+attr);
    }

    public Tag big() {
        return nest("big");
    }

    public Tag big(String attr) {
        return nest("big "+attr);
    }

    public Tag small() {
        return nest("small");
    }

    public Tag small(String attr) {
        return nest("small "+attr);
    }

    public Tag i() {
        return nest("i");
    }

    public Tag i(String attr) {
        return nest("i "+attr);
    }

    public Tag strong() {
        return nest("strong");
    }

    public Tag strong(String attr) {
        return nest("big "+attr);
    }

    public Tag table() {
        return nest("table");
    }

    public Tag table(String attr) {
        return nest("table "+attr);
    }

    public Tag tr() {
        return nest("tr");
    }

    public Tag tr(String attr) {
        return nest("tr "+attr);
    }

    public Tag td() {
        return nest("td");
    }

    public Tag td(String attr) {
        return nest("td "+attr);
    }

    public Tag form() {
        return nest("form");
    }

    public Tag form(String attr) {
        return nest("form "+attr);
    }

    // -----------------------------------------------------
    // METHODS FOR ADDING GENERAL CONTENT
    // -----------------------------------------------------

    /** create a new tag, embed it into this one, return this tag */
    public Tag add(String s) {
        Tag t = new Tag(s);
        content.addElement(t);
        return this;
    }

    /** add a tag to this one, returning this tag */
    public Tag add(Tag t) {
        content.addElement(t);
        return this;
    }

    /** create a new tag, nest it into this one, return the new tag */
    public Tag nest(String opentag) {
        Tag t = new Tag(this, opentag);
        t.parent = this;
        return t;
    }
    public Tag nest() {
        Tag t = new Tag(this);
        t.parent = this;
        return t;
    }
    
    /** insert object into this tag, return this tag */
    public Tag raw(Object o) {
        content.addElement(o);
        return this;
    }

    /** set an attribute of this tag, return this tag */
    public Tag set(String name, String val) {
        return set(name + "=\"" + val + "\"");
    }

    /** set an attribute of this tag, return this tag */
    public Tag set(String setting) {
        attribs.addElement(setting);
        return this;
    }

    public Tag style(String name, String val) {
        return style(name+":"+val);
    }
    
    public Tag style(String setting) {
        styles.addElement(setting);
        return this;
    }

    // -----------------------------------------------------
    // METHODS FOR RENDERING
    // -----------------------------------------------------

    public void render(OutputStream out) throws IOException {
        
        //System.out.print("{render:"+open+"}");
        //System.out.flush();

        if (open != null) {
            out.write("<".getBytes());
            out.write(open.getBytes());

            // add in attributes, if any
            for (int i=0; i<attribs.size(); i++) {
                String attr = null;
                try {
                    attr = (String)attribs.get(i);
                    //buf.append(" "+attr[0]+"="+attr[1]);
                    out.write((" "+attr).getBytes());
                } catch (ClassCastException e) {
                    e.printStackTrace();
                    //System.out.println("attr='"+attribs.get(i)+"'");
                    System.out.println("attribs='"+attribs+"'");
                    //System.out.println("content='"+content+"'");
                }
            }

            // add in styles, if any
            if (styles.size() > 0) {
                out.write((" style=\"").getBytes());
                Enumeration elems = styles.elements();
                while (elems.hasMoreElements()) {
                    String s = (String)elems.nextElement()+";";
                    out.write(s.getBytes());
                }
                out.write("\"".getBytes());
            }
            
            if (close.equals("")) {
                out.write("/".getBytes());
            }
            out.write(">".getBytes());

            if (breakBefore) {
                out.write("\n".getBytes());
            }
        }

        for (int i=0; i < content.size(); i++) {
            Object item = content.get(i);
            if (item.getClass().isAssignableFrom(Tag.class)) {
                ((Tag)item).render(out);
            } else {
                out.write(item.toString().getBytes());
            }
        }

        if (open != null) {
            out.write(close.getBytes());
            //buf.append(close);

            if (breakAfter) {
                out.write("\n".getBytes());
            }
        }
    }

    public String render() {
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        try {
            render(s);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return s.toString();
    }

    public String toString() {
        return render();
    }
}

