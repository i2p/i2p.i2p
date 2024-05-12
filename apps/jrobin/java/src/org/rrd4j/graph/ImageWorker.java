package org.rrd4j.graph;

import java.awt.*;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * An abstract class, that allows to use custom {@link Graphics2D}. To use it the construct should build it and call
 * {@link #setG2d(Graphics2D)} when finished.
 */
public abstract class ImageWorker {

    private static final String DUMMY_TEXT = "Dummy";
    private static final int IMG_BUFFER_CAPACITY = 10000; // bytes
    private Graphics2D g2d;

    protected void setG2d(Graphics2D g2d) {
        if (g2d != null) {
            dispose();
        }
        this.g2d = g2d;
    }

    protected abstract void resize(int width, int height);

    protected void clip(int x, int y, int width, int height) {
        g2d.setClip(x, y, width, height);
    }

    protected void transform(int x, int y, double angle) {
        g2d.translate(x, y);
        g2d.rotate(angle);
    }

     protected void reset() {
        reset(g2d);
    }

    /**
     * reset the dimensions of the {@link Graphics2D}
     */
    protected abstract void reset(Graphics2D g2d);

    protected void fillRect(int x, int y, int width, int height, Paint paint) {
        g2d.setPaint(paint);
        g2d.fillRect(x, y, width, height);
    }

    protected void fillPolygon(double[] x, double yBottom, double[] yTop, Paint paint) {
        g2d.setPaint(paint);
        PathIterator path = new PathIterator(yTop);
        for (int[] pos = path.getNextPath(); pos != null; pos = path.getNextPath()) {
            int start = pos[0], end = pos[1], n = end - start;
            int[] xDev = new int[n + 2], yDev = new int[n + 2];
            int c = 0;
            for (int i = start; i < end; i++) {
                int cx = (int) x[i];
                int cy = (int) yTop[i];
                if (c == 0 || cx != xDev[c - 1] || cy != yDev[c - 1]) {
                    if (c >= 2 && cy == yDev[c - 1] && cy == yDev[c - 2]) {
                        // collapse horizontal lines
                        xDev[c - 1] = cx;
                    } else {
                        xDev[c] = cx;
                        yDev[c++] = cy;
                    }
                }
            }
            xDev[c] = xDev[c - 1];
            xDev[c + 1] = xDev[0];
            yDev[c] = yDev[c + 1] = (int) yBottom;
            g2d.fillPolygon(xDev, yDev, c + 2);
        }
    }

    protected void fillPolygon(double[] x, double[] yBottom, double[] yTop, Paint paint) {
        g2d.setPaint(paint);
        PathIterator path = new PathIterator(yTop);
        for (int[] pos = path.getNextPath(); pos != null; pos = path.getNextPath()) {
            int start = pos[0], end = pos[1], n = end - start;
            int[] xDev = new int[n * 2], yDev = new int[n * 2];
            int c = 0;
            for (int i = start; i < end; i++) {
                int cx = (int) x[i];
                int cy = (int) yTop[i];
                if (c == 0 || cx != xDev[c - 1] || cy != yDev[c - 1]) {
                    if (c >= 2 && cy == yDev[c - 1] && cy == yDev[c - 2]) {
                        // collapse horizontal lines
                        xDev[c - 1] = cx;
                    } else {
                        xDev[c] = cx;
                        yDev[c++] = cy;
                    }
                }
            }
            for (int i = end - 1; i >= start; i--) {
                int cx = (int) x[i];
                int cy = (int) yBottom[i];
                if (c == 0 || cx != xDev[c - 1] || cy != yDev[c - 1]) {
                    if (c >= 2 && cy == yDev[c - 1] && cy == yDev[c - 2]) {
                        // collapse horizontal lines
                        xDev[c - 1] = cx;
                    } else {
                        xDev[c] = cx;
                        yDev[c++] = cy;
                    }
                }
            }
            g2d.fillPolygon(xDev, yDev, c);
        }
    }

    protected void drawLine(int x1, int y1, int x2, int y2, Paint paint, Stroke stroke) {
        g2d.setStroke(stroke);
        g2d.setPaint(paint);
        g2d.drawLine(x1, y1, x2, y2);
    }

    protected void drawPolyline(double[] x, double[] y, Paint paint, Stroke stroke) {
        g2d.setPaint(paint);
        g2d.setStroke(stroke);
        PathIterator path = new PathIterator(y);
        for (int[] pos = path.getNextPath(); pos != null; pos = path.getNextPath()) {
            int start = pos[0], end = pos[1];
            int[] xDev = new int[end - start], yDev = new int[end - start];
            int c = 0;
            for (int i = start; i < end; i++) {
                int cx = (int) x[i];
                int cy = (int) y[i];
                if (c == 0 || cx != xDev[c - 1] || cy != yDev[c - 1]) {
                    if (c >= 2 && cy == yDev[c - 1] && cy == yDev[c - 2]) {
                        // collapse horizontal lines
                        xDev[c - 1] = cx;
                    } else {
                        xDev[c] = cx;
                        yDev[c++] = cy;
                    }
                }
            }
            g2d.drawPolyline(xDev, yDev, c);
        }
    }

    protected void drawString(String text, int x, int y, Font font, Paint paint) {
        g2d.setFont(font);
        g2d.setPaint(paint);
        g2d.drawString(text, x, y);
    }

    protected double getFontAscent(Font font) {
        LineMetrics lm = font.getLineMetrics(DUMMY_TEXT, g2d.getFontRenderContext());
        return lm.getAscent();
    }

    protected double getFontHeight(Font font) {
        LineMetrics lm = font.getLineMetrics(DUMMY_TEXT, g2d.getFontRenderContext());
        return lm.getAscent() + lm.getDescent();
    }

    protected double getStringWidth(String text, Font font) {
        return font.getStringBounds(text, 0, text.length(), g2d.getFontRenderContext()).getBounds().getWidth();
    }

    protected void setAntiAliasing(boolean enable) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                enable ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
    }

    protected void setTextAntiAliasing(boolean enable) {
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                enable ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    }

    protected void loadImage(RrdGraphDef.ImageSource imageSource, int x, int y, int w, int h) throws IOException {
        BufferedImage wpImage = imageSource.apply(w, h).getSubimage(0, 0, w, h);
        g2d.drawImage(wpImage, new AffineTransform(1f, 0f, 0f, 1f, x, y), null);
    }

    protected void dispose() {
        if (g2d != null) {
            g2d.dispose();
        }
    }

    protected void makeImage(Path path) throws IOException {
        try (OutputStream os = Files.newOutputStream(path)) {
            makeImage(os);
        }
    }

    protected abstract void makeImage(OutputStream os) throws IOException ;

    protected void saveImage(String path) throws IOException {
        makeImage(Paths.get(path));
    }

    protected byte[] getImageBytes() throws IOException {
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream(IMG_BUFFER_CAPACITY)){
            makeImage(stream);
            return stream.toByteArray();
        }
    }

}
