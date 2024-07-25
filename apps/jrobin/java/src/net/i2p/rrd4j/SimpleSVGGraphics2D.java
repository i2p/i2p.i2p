package net.i2p.rrd4j;

import java.text.AttributedCharacterIterator;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import java.awt.*;
import java.awt.font.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import static java.awt.geom.PathIterator.*;
import java.awt.image.*;
import java.awt.image.renderable.RenderableImage;

/**
 *  Very simple SVGGraphics2D, only enough for basic rrd4j use, without dependencies.
 *  Plus a few things that rrd4j doesn't use, but not much.
 *  Unsupported things will throw UnsupportedOperationExceptions.
 *
 *  Supports custom RenderingHints for id and class on top-level svg element.
 *  Supports custom RenderingHints for id, class, title, and arbitrary attributes
 *  on all drawn elements.
 *  Supports custom RenderingHints for inner SVG on all drawn elements except text.
 *
 *  No standard Java AWT hints are supported.
 *  Antialiasing is done automatically.
 *  Antialiasing hints have no effect.
 *
 *  License: Apache 2.0 (same as rrd4j)
 *
 *  @since 0.9.64
 *  @author zzz
 */
public class SimpleSVGGraphics2D extends Graphics2D {

    //// hints - all strings except for ATTMAP ////

    /**
     * On the top svg element.
     * Value is a string and will be XML-escaped when rendering.
     */
    public static final RenderingHints.Key KEY_SVG_ID = new RHKey(1);
    /**
     * On the top svg element.
     * Value is a string and will be XML-escaped when rendering.
     */
    public static final RenderingHints.Key KEY_SVG_CLASS = new RHKey(2);
    /**
     * On the top svg element.
     * Value is a string and will be XML-escaped when rendering.
     */
    public static final RenderingHints.Key KEY_SVG_TITLE = new RHKey(3);
    /**
     * On the next element drawn, one-shot, will be removed after rendering.
     * Value is a string and will be XML-escaped when rendering.
     */
    public static final RenderingHints.Key KEY_ELEMENT_ID = new RHKey(4);
    /**
     * On the next element drawn, one-shot, will be removed after rendering.
     * Value is a string and will be XML-escaped when rendering.
     */
    public static final RenderingHints.Key KEY_ELEMENT_CLASS = new RHKey(5);
    /**
     * Value is a Map of String to String of extra attributes on the next element drawn, one-shot, will be removed after rendering.
     * Map keys must be XML-escaped by caller if necessary.
     * Map values will be XML-escaped when rendering.
     */
    public static final RenderingHints.Key KEY_ELEMENT_ATTMAP = new RHKey(6);
    /**
     * On the next element drawn, one-shot, will be removed after rendering.
     * Value is a string and will be XML-escaped when rendering.
     */
    public static final RenderingHints.Key KEY_ELEMENT_TITLE = new RHKey(7);
    /**
     * Put "inside" the next element drawn, one-shot, will be removed after rendering.
     * Value is an XML string and must be XML-escaped by caller if necessary.
     */
    public static final RenderingHints.Key KEY_ELEMENT_INNERSVG = new RHKey(8);

    private final StringBuilder buf;
    private final SimpleSVGMaker svg;
    private final Map<Object,Object> hints = new HashMap<Object,Object>();
    private AffineTransform transform = new AffineTransform();
    private final FontRenderContext frctx = new FontRenderContext(transform, true, true);
    private final int width, height;
    private final Rectangle origclip;
    // null unless different from origclip
    private Rectangle clip;
    private String clipID;
    private Color bgcolor = Color.WHITE;
    private Paint paint = Color.BLACK;
    private Stroke stroke = new BasicStroke(1);
    private Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private boolean started;

    public SimpleSVGGraphics2D(int width, int height) {
        this.width = width;
        this.height = height;
        origclip = new Rectangle(0, 0, width, height);
        buf = new StringBuilder(16*1024);
        svg = new SimpleSVGMaker(buf);
    }

    public String getSVG() {
        stop();
        String rv = buf.toString();
        dispose();
        return rv;
    }

    private void start() {
        if (!started) {
            String id = (String) hints.remove(KEY_SVG_ID);
            String cl = (String) hints.remove(KEY_SVG_CLASS);
            svg.startSVG(width, height, bgcolor, id, cl);
            started = true;
        }
    }

    private void stop() {
        if (started) {
            if (!transform.isIdentity()) {
                svg.endGroup();
                transform = frctx.getTransform();
            }
            svg.endSVG();
            clip = null;
            clipID = null;
            started = false;
        }
    }

    public void dispose() { buf.setLength(0); }

    //// API bypass ////

    /**
     * Graphics2D API bypass, advanced use only
     */
    public SimpleSVGMaker getMaker() { start(); return svg; }

    /**
     * Graphics2D API bypass, advanced use only
     */
    public void append(String s) { start(); buf.append(s).append('\n'); }

    //// draws/fills used by rrd4j ////

    public void drawLine(int x1, int y1, int x2, int y2) {
        start();
        svg.drawLine(x1, y1, x2, y2, (Color) paint, (BasicStroke) stroke, clipID, hints);
    }

    public void drawPolyline(int[] xPoints, int[] yPoints, int nPoints) {
        start();
        svg.drawPolyline(xPoints, yPoints, nPoints, (Color) paint, (BasicStroke) stroke, clipID, hints);
    }

    public void fillPolygon(int[] xPoints, int[] yPoints, int nPoints) {
        start();
        String id = (String) hints.remove(KEY_ELEMENT_ID);
        String cl = (String) hints.remove(KEY_ELEMENT_CLASS);
        svg.fillPolygon(xPoints, yPoints, nPoints, (Color) paint, clipID, hints);
    }

    public void fillRect(int x, int y, int width, int height) {
        if (!started) {
            // rrd4j calls this first with the background color, it does not call setBackground()
            if (x == 0 && y == 0 && width == this.width && height == this.height) {
                // disable setting the background color, this is it
                bgcolor = null;
            }
            start();
        }
        svg.drawRect(x, y, width, height, null, (Color) paint, null, clipID, hints);
    }

    //// text ////

    public void drawString(String str, int x, int y) {
        start();
        svg.drawText(str, x, y, (Color) paint, font, clipID, hints);
    }

    public void drawString(String str, float x, float y) { drawString(str, (int) x, (int) y); }

    public FontRenderContext getFontRenderContext() { return frctx; }

    //// supported things not used by rrd4j ////

    /**
     * Circles only for now, must be width == height and arcAngle == 360
     * Otherwise throws UnsupportedOperationException
     * TODO
     */
    public void drawArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
        if (width != height || arcAngle != 360)
            throw new UnsupportedOperationException("circles only!");
        start();
        int r = width / 2;
        svg.drawCircle(x + r, y + r, r, (Color) paint, null, (BasicStroke) stroke, clipID, hints);
    }

    /**
     * Circles only for now, must be width == height and arcAngle == 360
     * Otherwise throws UnsupportedOperationException
     * TODO
     */
    public void fillArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
        if (width != height || arcAngle != 360)
            throw new UnsupportedOperationException("circles only!");
        start();
        int r = width / 2;
        svg.drawCircle(x + r, y + r, r, null, (Color) paint, (BasicStroke) stroke, clipID, hints);
    }

    public void clearRect(int x, int y, int width, int height) {
        boolean wasStarted = started;
        if (!wasStarted) {
            start();
        } else {
            // don't do it twice at the start
            String id = (String) hints.remove(KEY_ELEMENT_ID);
            String cl = (String) hints.remove(KEY_ELEMENT_CLASS);
            svg.drawRect(x, y, width, height, null, bgcolor, null, clipID, hints);
        }
    }

    public void draw(Shape s) {
        drawOrFill(s, true, false);
    }

    public void fill(Shape s) {
        drawOrFill(s, false, true);
    }

    /**
     * Lines only for now
     * Will draw a closed shape, open shapes will become closed.
     * Arcs will throw UnsupportedOperationException
     */
    private void drawOrFill(Shape s, boolean draw, boolean fill) {
        int[] x = new int[16];
        int[] y = new int[16];
        int i = 0;
        float[] coords = new float[6];
        for (PathIterator it = s.getPathIterator(frctx.getTransform()); !it.isDone(); it.next()) {
            int type = it.currentSegment(coords);
            switch(type) {
                case SEG_MOVETO:
                case SEG_LINETO:
                    if (i >= x.length) {
                        x = Arrays.copyOf(x, x.length * 2);
                        y = Arrays.copyOf(y, y.length * 2);
                    }
                    x[i] = (int) coords[0];
                    y[i++] = (int) coords[1];
                    break;

                case SEG_CLOSE:
                    break;

                case SEG_CUBICTO:
                case SEG_QUADTO:
                    throw new UnsupportedOperationException("Unsupported curved shape");

                default:
                    throw new UnsupportedOperationException("Unsupported type " + type);
            }
        }
        if (draw)
            drawPolyline(x, y, i);
        else
            fillPolygon(x, y, i);
    }

    //// clips ////

    public void setClip(int x, int y, int width, int height) {
        setClip(new Rectangle(x, y, width, height));
    }

    public void setClip(Shape clip) {
        if (clip.equals(this.clip))
            return;
        if (this.clip == null && clip.equals(origclip))
            return;
        Rectangle newclip;
        if (clip instanceof Rectangle)
            newclip = (Rectangle) clip;
        else
            newclip = clip.getBounds();
        if (clip.equals(origclip)) {
            this.clip = null;
            clipID = null;
            return;
        }
        // define new clip, save the Rectangle and ID
        clipID = svg.defineClipPath(newclip);
        this.clip = newclip;
    }

    //// transforms ////

    public void translate(int x, int y) { translate((double) x, (double) y); }

    public void translate(double tx, double ty) {
         AffineTransform ntx = (AffineTransform) transform.clone();
         ntx.translate(tx, ty);
         setTransform(ntx);
    }

    public void rotate(double theta) {
         AffineTransform ntx = (AffineTransform) transform.clone();
         ntx.rotate(theta);
         setTransform(ntx);
    }

    public void rotate(double theta, double x, double y) {
         AffineTransform ntx = (AffineTransform) transform.clone();
         ntx.rotate(theta, x, y);
         setTransform(ntx);
    }

    public void scale(double sx, double sy) {
         AffineTransform ntx = (AffineTransform) transform.clone();
         ntx.scale(sx, sy);
         setTransform(ntx);
    }

    public void shear(double shx, double shy) {
         AffineTransform ntx = (AffineTransform) transform.clone();
         ntx.shear(shx, shy);
         setTransform(ntx);
    }

    public void setTransform(AffineTransform tx) {
        // For each transform, we close the previous group if non-identity,
        // and start a new group with a transform containing the new combined transform.
        // We don't 'stack' groups with each individual transform.
        if (transform.equals(tx))
            return;
        if (!transform.isIdentity())
            svg.endGroup();
        if (!tx.isIdentity()) {
            String matrix = String.format(Locale.US, "matrix(%.3f %.3f %.3f %.3f %.3f %.3f)",
                                          tx.getScaleX(), tx.getShearY(),
                                          tx.getShearX(), tx.getScaleY(),
                                          tx.getTranslateX(), tx.getTranslateY());
            svg.startGroup(null, null, "transform", matrix);
        }
        transform = tx;
    }

    public AffineTransform getTransform() { return transform; }

    //// setters ////

    public void setFont(Font font) { this.font = font; }
    public void setPaint(Paint paint) { this.paint = paint; }
    public void setStroke(Stroke stroke) { this.stroke = stroke; }

    //// we support these but unused by rrd4j ////

    public void setBackground(Color color) { bgcolor = color; }
    public Color getBackground() { return bgcolor; }
    public Shape getClip() { return clip; }
    public Rectangle getClipBounds() { return clip; }
    public void setColor(Color color) { paint = color; }
    public Color getColor() { return (Color) paint; }
    public Font getFont() { return font; }
    public Paint getPaint() { return paint; }
    public Stroke getStroke() { return stroke; }

    //// Hints ////

    private static class RHKey extends RenderingHints.Key {
        public RHKey(int k) {
            super(k);
        }

        public boolean isCompatibleValue(Object o) {
            if (intKey() == 6)
                return o instanceof Map;
            return o instanceof String;
        }
    }

    public void addRenderingHints(Map<?,?> hints) { this.hints.putAll(hints); }
    public Object getRenderingHint(RenderingHints.Key hintKey) { return hints.get(hintKey); }
    public void setRenderingHint(RenderingHints.Key hintKey, Object hintValue) { hints.put(hintKey, hintValue); }
    public void setRenderingHints(Map<?,?> hints) { this.hints.clear(); addRenderingHints(hints); }

    //// unsupported things ////
    //// all do nothing or throw ////

    public void clipRect(int x, int y, int width, int height) { throw new UnsupportedOperationException(); }
    public void clip(Shape s) { throw new UnsupportedOperationException(); }
    public void copyArea(int x, int y, int width, int height, int dx, int dy) { throw new UnsupportedOperationException(); }
    public Graphics create() { throw new UnsupportedOperationException(); }
    public void drawGlyphVector(GlyphVector g, float x, float y) { throw new UnsupportedOperationException(); }
    public void drawImage(BufferedImage img, BufferedImageOp op, int x, int y) { throw new UnsupportedOperationException(); }
    public boolean drawImage(Image img, AffineTransform xform, ImageObserver obs) { throw new UnsupportedOperationException(); }
    public boolean drawImage(Image img, int x, int y, ImageObserver obs) { throw new UnsupportedOperationException(); }
    public boolean drawImage(Image img, int x, int y, Color bgcolor, ImageObserver obs) { throw new UnsupportedOperationException(); }
    public boolean drawImage(Image img, int x, int y, int width, int height, ImageObserver obs) { throw new UnsupportedOperationException(); }
    public boolean drawImage(Image img, int x, int y, int width, int height, Color bgcolor, ImageObserver obs) { throw new UnsupportedOperationException(); }
    public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, ImageObserver obs) { throw new UnsupportedOperationException(); }
    public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, Color bgcolor, ImageObserver obs) { throw new UnsupportedOperationException(); }
    public void drawOval(int x, int y, int width, int height) { throw new UnsupportedOperationException(); }
    public void drawPolygon(int[] xPoints, int[] yPoints, int nPoints) { throw new UnsupportedOperationException(); }
    public void drawRenderableImage(RenderableImage img, AffineTransform xform) { throw new UnsupportedOperationException(); }
    public void drawRenderedImage(RenderedImage img, AffineTransform xform) { throw new UnsupportedOperationException(); }
    public void drawRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) { throw new UnsupportedOperationException(); }
    public void drawString(AttributedCharacterIterator iterator, float x, float y) { throw new UnsupportedOperationException(); }
    public void drawString(AttributedCharacterIterator iterator, int x, int y) { throw new UnsupportedOperationException(); }
    public void fillOval(int x, int y, int width, int height) { throw new UnsupportedOperationException(); }
    public void fillPolyline(int[] xPoints, int[] yPoints, int nPoints) { throw new UnsupportedOperationException(); }
    public void fillRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) { throw new UnsupportedOperationException(); }
    public Composite getComposite() { return null; }
    public GraphicsConfiguration getDeviceConfiguration() { return null; }
    public FontMetrics getFontMetrics(Font f) { return null; }
    public RenderingHints getRenderingHints() { return null; }
    public boolean hit(Rectangle rect, Shape s, boolean onStroke) { return false; }
    public void setComposite(Composite comp) { throw new UnsupportedOperationException(); }
    public void setPaintMode() {}
    public void setXORMode(Color color) { throw new UnsupportedOperationException(); }
    public void transform(AffineTransform tx) {}
}
