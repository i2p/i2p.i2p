package net.i2p.rrd4j;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;

import net.i2p.data.DataHelper;
import static net.i2p.rrd4j.SimpleSVGGraphics2D.*;

/**
 *  Create full or partial SVG images, without dependencies.
 *  Does not extend or use Graphics2D or ImageWriter.
 *
 *  Each drawn element can be passed an optional CSS ID and/or classes,
 *  for easy styling and manipulation via CSS or js.
 *  All parameters are set as attributes, not as inline style,
 *  so a separate CSS style may easily override them.
 *  If inline style is desired, add it with the KEY_ELEMENT_ATTMAP hint.
 *
 *  Unlike in Graphics2D, the border and fill for an object may be drawn in
 *  the same call, with separate colors.
 *
 *  There is no state here other than the StringBuffer;
 *  there is no concept of current Color or Stroke or Font;
 *  caller must keep track of current Colors, Stroke, and Font, and pass them in
 *  on every draw() call, and/or overridden via CSS.
 *
 *  License: Apache 2.0 (same as rrd4j)
 *
 *  @since 0.9.64
 *  @author zzz
 */
public class SimpleSVGMaker {

    private final StringBuilder buf;
    private int clipid;

    public SimpleSVGMaker(StringBuilder buf) {
        this.buf = buf;
    }

   /**
     * Start svg tag
     * @param bgcolor null for none
     * @param id CSS id or null for none
     * @param clz CSS class or null for none
     */
    public void startSVG(int width, int height, Color bgcolor, String id, String clz) {
        buf.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n" +
                   // "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">\n" +
                   "<svg version=\"1.1\" xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" ");
        addIDClass(id, clz);	
        addString("viewBox", "0 0 " + width + ' ' + height);
        addInt("width", width);
        addInt("height", height);
        buf.append(">\n");
        if (bgcolor != null && bgcolor.getAlpha() > 0)
            drawRect(0, 0, width, height, null, bgcolor, null, null, Collections.emptyMap());
    }

    /**
     * End svg tag
     */
    public void endSVG() {
        buf.append("</svg>\n");
    }

    /**
     * Start group
     * @param id CSS id or null for none
     * @param clz CSS class or null for none
     * @param att an attribute to add att=val, e.g. "transform", or null for none
     * @param val an attribute to add att=val, or null for none
     */
    public void startGroup(String id, String clz, String att, String val) {
        buf.append("<g ");
        addIDClass(id, clz);	
        if (att != null && val != null)
            addString(att, val);
        buf.append(">\n");
    }

    /**
     * End group
     */
    public void endGroup() {
        buf.append("</g>\n");
    }

    /**
     * Define clip path
     * @return a unique ID to pass to draw() calls
     */
    public String defineClipPath(Rectangle clip) {
        buf.append("<clipPath ");
        String rv = "clip-" + hashCode() + '-' + (clipid++);
        addIDClass(rv, null);   
        buf.append("><rect ");
        addInt("x", (int) clip.getX());
        addInt("y", (int) clip.getY());
        addInt("width", (int) clip.getWidth());
        addInt("height", (int) clip.getHeight());
        buf.append("/></clipPath>\n");
        return rv;
    }

    /**
     * Draw circle centered on x,y with a radius given
     * @param border null for none
     * @param fill null for none
     * @param clipid as returned from defineClipID() or null for none
     * @param hints non-null
     */
    public void drawCircle(int x, int y, int radius, Color border, Color fill, BasicStroke stroke, String clipid, Map<Object, Object> hints) {
        buf.append("<circle ");
        addAttributes(hints);	
        addClipPath(clipid);
        addInt("cx", x);
        addInt("cy", y);
        addInt("r", radius);
        addStroke("fill", fill, null);
        addStroke("stroke", border, stroke);
        String title = (String) hints.remove(KEY_ELEMENT_TITLE);
        String inner = (String) hints.remove(KEY_ELEMENT_INNERSVG);
        if (title != null || inner != null) {
            buf.append(">\n");
            addInner(title, inner);
            buf.append("</circle>\n");
        } else {
            buf.append("/>\n");
        }
    }

    /**
     * Draw square centered on x,y with a width/height given
     * @param border null for none
     * @param fill null for none
     * @param clipid as returned from defineClipID() or null for none
     * @param hints non-null
     */
    public void drawSquare(int x, int y, int sz, Color border, Color fill, BasicStroke stroke, String clipid, Map<Object, Object> hints) {
        drawRect(x - (sz/2), y - (sz/2), sz, sz, border, fill, stroke, clipid, hints);
    }

    /**
     * Draw rect
     * @param border null for none
     * @param fill null for none
     * @param hints non-null
     */
    public void drawRect(int x, int y, int width, int height, Color border, Color fill, BasicStroke stroke, String clipid, Map<Object, Object> hints) {
        buf.append("<rect ");
        addAttributes(hints);	
        addClipPath(clipid);
        addInt("x", x);
        addInt("y", y);
        addInt("width", width);
        addInt("height", height);
        addStroke("fill", fill, null);
        addStroke("stroke", border, stroke);
        buf.append("shape-rendering=\"crispEdges\" ");
        String title = (String) hints.remove(KEY_ELEMENT_TITLE);
        String inner = (String) hints.remove(KEY_ELEMENT_INNERSVG);
        if (title != null || inner != null) {
            buf.append(">\n");
            addInner(title, inner);
            buf.append("</rect>\n");
        } else {
            buf.append("/>\n");
        }
    }

    /**
     * Draw line
     * @param color null to let CSS do it
     * @param clipid as returned from defineClipID() or null for none
     * @param hints non-null
     */
    public void drawLine(int x1, int y1, int x2, int y2, Color color, BasicStroke stroke, String clipid, Map<Object, Object> hints) {
        buf.append("<line ");
        addAttributes(hints);	
        addClipPath(clipid);
        addInt("x1", x1);
        addInt("y1", y1);
        addInt("x2", x2);
        addInt("y2", y2);
        addStroke("stroke", color, stroke);
        // don't do this for diagonal lines, it kills antialiasing
        if (x1 == x2 || y1 == y2)
            buf.append("shape-rendering=\"crispEdges\" ");
        String title = (String) hints.remove(KEY_ELEMENT_TITLE);
        String inner = (String) hints.remove(KEY_ELEMENT_INNERSVG);
        if (title != null || inner != null) {
            buf.append(">\n");
            addInner(title, inner);
            buf.append("</line>\n");
        } else {
            buf.append("/>\n");
        }
    }

    /**
     * Draw polyline
     * @param color null to let CSS do it
     * @param clipid as returned from defineClipID() or null for none
     * @param hints non-null
     */
    public void drawPolyline(int[] x, int[] y, int sz, Color color, BasicStroke stroke, String clipid, Map<Object, Object> hints) {
        if (sz < 2)
            return;
        buf.append("<path ");
        addAttributes(hints);	
        addClipPath(clipid);
        buf.append("d=\"M");
        buf.append(x[0]).append(',').append(y[0]);
        for (int i = 1; i < sz; i++) {
            // use relative coords to save a little space
            int dx = x[i] - x[i-1];
            int dy = y[i] - y[i-1];
            if (dx == 0) {
                buf.append('v').append(dy);
            } else if (dy == 0) {
                buf.append('h').append(dx);
            } else {
                buf.append('l').append(dx).append(',').append(dy);
            }
        }
        buf.append("\" ");
        addStroke("stroke", color, stroke);
        buf.append("fill=\"none\" ");
        // this is good for the horizontal/vertical paths drawn by rrd4j,
        // but not so great for diagonal path segments
        // Take our cue from the first segment
        if (x[0] == x[1] || y[0] == y[1])
            buf.append("shape-rendering=\"crispEdges\" ");
        String title = (String) hints.remove(KEY_ELEMENT_TITLE);
        String inner = (String) hints.remove(KEY_ELEMENT_INNERSVG);
        if (title != null || inner != null) {
            buf.append(">\n");
            addInner(title, inner);
            buf.append("</path>\n");
        } else {
            buf.append("/>\n");
        }
    }

    /**
     * Fill polygon
     * @param color null to let CSS do it
     * @param clipid as returned from defineClipID() or null for none
     * @param hints non-null
     */
    public void fillPolygon(int[] x, int[] y, int sz, Color color, String clipid, Map<Object, Object> hints) {
        if (sz < 2)
            return;
        buf.append("<path ");
        addAttributes(hints);	
        addClipPath(clipid);
        buf.append("d=\"M");
        buf.append(x[0]).append(',').append(y[0]);
        for (int i = 1; i < sz; i++) {
            // use relative coords to save a little space
            int dx = x[i] - x[i-1];
            int dy = y[i] - y[i-1];
            if (dx == 0) {
                buf.append('v').append(dy);
            } else if (dy == 0) {
                buf.append('h').append(dx);
            } else {
                buf.append('l').append(dx).append(',').append(dy);
            }
        }
        buf.append("Z\" ");
        addStroke("fill", color, null);
        buf.append("stroke=\"none\" ");
        // see above
        if (x[0] == x[1] || y[0] == y[1])
            buf.append("shape-rendering=\"crispEdges\" ");
        String title = (String) hints.remove(KEY_ELEMENT_TITLE);
        String inner = (String) hints.remove(KEY_ELEMENT_INNERSVG);
        if (title != null || inner != null) {
            buf.append(">\n");
            addInner(title, inner);
            buf.append("</path>\n");
        } else {
            buf.append("/>\n");
        }
    }

    /**
     * Draw text
     * @param color null to let CSS do it
     * @param font null to let CSS do it
     * @param clipid as returned from defineClipID() or null for none
     * @param hints non-null
     */
    public void drawText(String text, int x, int y, Color color, Font font, String clipid, Map<Object, Object> hints) {
        buf.append("<text ");
        addAttributes(hints);	
        addClipPath(clipid);
        addInt("x", x);
        addInt("y", y);
        addStroke("fill", color, null);
        if (font != null) {
            addString("font-family", font.getFamily());
            buf.append("font-size=\"").append(font.getSize()).append("px\" ");
            if (font.isBold())
                buf.append("font-weight=\"bold\" ");
            if (font.isItalic())
                buf.append("font-style=\"italic\" ");
        }
        buf.append("text-rendering=\"optimizeLegibility\">").append(DataHelper.escapeHTML(text));
        String title = (String) hints.remove(KEY_ELEMENT_TITLE);
        if (title != null)
            addInner(title, null);
        buf.append("</text>\n");
    }

    private void addInt(String key, int val) {
        buf.append(key).append("=\"").append(val).append("\" ");
    }

    private void addString(String key, String val) {
        buf.append(key).append("=\"").append(DataHelper.escapeHTML(val)).append("\" ");
    }

    /**
     * @param id CSS id or null for none
     * @param clz CSS class or null for none
     */
    private void addIDClass(String id, String clz) {
        if (id != null)
            addString("id", id);
        if (clz != null)
            addString("class", clz);
    }

    private void addAttributes(Map<Object, Object> hints) {
        String id = (String) hints.remove(KEY_ELEMENT_ID);
        if (id != null)
            addString("id", id);
        String clz = (String) hints.remove(KEY_ELEMENT_CLASS);
        if (clz != null)
            addString("class", clz);
        Map<?,?> atts = (Map) hints.remove(KEY_ELEMENT_ATTMAP);
        if (atts != null) {
            for (Map.Entry e : atts.entrySet()) {
                addString((String) e.getKey(), (String) e.getValue());
            }
        }
    }

    /**
     * @param type "fill" or "stroke"
     * @param color null to let CSS do it
     * @param stroke null to omit for fill
     */
    private void addStroke(String type, Color color, BasicStroke stroke) {
        buf.append(type);
        if (color != null) {
            // Output RGB or RGBA. getRGB() is ARGB.
            buf.append("=\"#").append(String.format(Locale.US, "%06x", color.getRGB() & 0xffffff));
            int alpha = color.getAlpha();
            if (alpha < 255)
                buf.append(String.format(Locale.US, "%02x", alpha));
            buf.append("\" ");
        } else {
            // default is black opaque, so fixup for none
            buf.append("=\"none\" ");
        }
        if (stroke != null) {
            int width = (int) stroke.getLineWidth();
            if (width > 0) {
                if (width != 1)
                    buf.append(type).append("-width=\"").append(width).append("\" ");
                float[] dash = stroke.getDashArray();
                if (dash != null && dash.length > 1) {
                    buf.append("stroke-dasharray=\"");
                    for (int i = 0; i < dash.length; i++) {
                        buf.append((int) dash[i]);
                        if (i != dash.length - 1)
                            buf.append(' ');
                    }
                    buf.append("\" ");
                }
            }
        }
    }

    /**
     * @param clipid as received from defineClipPath() or null for none
     */
    private void addClipPath(String clipid) {
        if (clipid != null)
            buf.append("clip-path='url(#").append(clipid).append(")' ");
    }

    /**
     * @param title, will be XML escaped here, or null
     * @param other full elements, must be XML escaped, or null
     */
    private void addInner(String title, String inner) {
        if (title != null)
            buf.append("  <title>").append(DataHelper.escapeHTML(title)).append("</title>\n");
        if (inner != null)
            buf.append("  ").append(inner).append("\n");
    }

/*
    public void main(String[] args) {
        StringBuilder buf = new StringBuilder(2048);
        SimpleSVGMaker g = new SimpleSVGMaker(buf);
        Font f = new Font("Dialog", Font.BOLD, 24);
        Color c = new Color(255, 128, 128);
        g.startSVG(190, 200, c, "id", "class");
        g.startGroup("gid", "class", "transform", "matrix");
        c = new Color(255, 0, 0);
        BasicStroke s = new BasicStroke(4);
        Map<Object,Object> hints = new java.util.HashMap<Object,Object>();
        g.drawSquare(100, 36, 17, null, c, s, null, hints);
        c = new Color(33, 33, 33, 128);
        s = new BasicStroke(8);
        g.drawCircle(75, 56, 27, c, null, s, null, hints);
        g.drawCircle(100, 100, 110, c, null, s, null, hints);
        c = new Color(0, 255, 0);
        s = new BasicStroke(2);
        g.drawLine(55, 96, 97, 178, c, s, null, hints);
        int[] xx = { 10, 20, 30, 40, 150 };
        int[] yy = { 81, 92, 113, 184, 29 };
        c = new Color(0, 0, 255);
        s = new BasicStroke(2);
        g.drawPolyline(xx, yy, 5, c, s, null, hints);
        Color cc = new Color(128, 128, 0, 128);
        Color ccc = new Color(128, 0, 192, 128);
        g.drawRect(100, 80, 40, 20, cc, ccc, s, null, hints);
        c = new Color(0, 128, 128);
        g.drawText("foo", 135, 156, c, f, null, hints);
        c = new Color(128, 128, 0);
        f = new Font(Font.SANS_SERIF, Font.ITALIC, 20);
        g.drawText("bar", 115, 136, c, f, null, hints);
        f = new Font(Font.SANS_SERIF, Font.PLAIN, 16);
        g.drawText("baz", 115, 176, c, f, null, hints);
        g.endGroup();
        g.endSVG();
        System.out.print(buf.toString());
    }
*/
}
