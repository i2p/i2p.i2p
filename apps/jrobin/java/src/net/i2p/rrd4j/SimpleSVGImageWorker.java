package net.i2p.rrd4j;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.io.OutputStream;

import org.rrd4j.graph.ImageWorker;

/**
 *  rrd4j adapter for SimpleSVGGraphics2D
 *
 *  Requires: rrd4j 3.10 or higher
 *  Ref: https://github.com/rrd4j/rrd4j/issues/165
 *
 *  Usage:
 *      No ImageIO/BufferedImage/ImageWriter required!
 *
 *<pre>
 *      RRDGraph graph = new RrdGraph(graphdef, new SimpleSVGImageWorker(width, height));
 *      outputstream.write(graph.getRrdGraphInfo().getBytes());
 *</pre>
 *
 *  License: Apache 2.0 (same as rrd4j)
 *
 *  @since 0.9.64
 *  @author zzz
 */
public class SimpleSVGImageWorker extends ImageWorker {
    private SimpleSVGGraphics2D g2d;
    private AffineTransform initialAffineTransform;
    private int imgWidth;
    private int imgHeight;

    public SimpleSVGImageWorker(int width, int height) {
        resize(width, height);
    }

    protected void resize(int width, int height) {
        imgWidth = width;
        imgHeight = height;
        g2d = new SimpleSVGGraphics2D(imgWidth, imgHeight);
        initialAffineTransform = g2d.getTransform();
        setG2d(g2d);
    }

    protected void reset(Graphics2D g2d) {
        g2d.setTransform(initialAffineTransform);
        g2d.setClip(0, 0, imgWidth, imgHeight);
    }

    protected void makeImage(OutputStream os) throws IOException {
        os.write(g2d.getSVG().getBytes("UTF-8"));
    }

    /**
     *  Overridden because the SVG format essentially strips leading/trailing spaces,
     *  causing alignment issues in ValueAxis with the %x.y number formatting.
     *  Consecutive spaces within text are also probably collapsed, that is not addressed here.
     */
    @Override
    protected void drawString(String text, int x, int y, Font font, Paint paint) {
        super.drawString(text.trim(), x, y, font, paint);
    }

    /**
     *  Overridden because the SVG format essentially strips leading/trailing spaces,
     *  causing alignment issues in ValueAxis with the %x.y number formatting.
     *  Consecutive spaces within text are also probably collapsed, that is not addressed here.
     */
    @Override
    protected double getStringWidth(String text, Font font) {
        return super.getStringWidth(text.trim(), font);
    }
}
