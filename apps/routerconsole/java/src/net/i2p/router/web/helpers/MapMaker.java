package net.i2p.router.web.helpers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.rrd4j.SimpleSVGGraphics2D;
import static net.i2p.rrd4j.SimpleSVGGraphics2D.*;
import net.i2p.rrd4j.SimpleSVGMaker;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.router.web.ContextHelper;
import net.i2p.router.web.Messages;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounterUnsafe;
import net.i2p.util.Translate;

/**
 * Generate a transparent image to overlay the world map in a Web Mercator format.
 *
 * Also contains commented-out code to generate the mercator.txt file.
 *
 * @since 0.9.xx
 */
public class MapMaker {
    private final RouterContext _context;
    private final Log _log;
    private final Map<Object, Object> hints = new HashMap<Object, Object>(4);

    private static final Map<String, Mercator> _mercator = new HashMap<String, Mercator>(256);

    static {
        readMercatorFile();
    }

    private static final String MERCATOR_DEFAULT = "mercator.txt";
    private static final int WIDTH = 1600;
    private static final int HEIGHT = 1600;
    private static final int MAP_HEIGHT = 828;
    // offsets from mercator to image.
    // left side at 171.9 degrees (rotated 34 pixels)
    // tweak to make it line up, eyeball Taiwan
    private static final int IMG_X_OFF = -34;
    // We crop the top from 85 degrees down to about 75 degrees (283 pixels)
    // We crop the bottom from 85 degrees down to about 57 degrees (489 pixels)
    private static final int IMG_Y_OFF = -283;
    private static final Color TEXT_COLOR = new Color(20, 20, 20);
    private static final String FONT_NAME = "Dialog";
    private static final int FONT_STYLE = Font.BOLD;
    private static final int FONT_SIZE = 16;
    // center text on the spot
    private static final int TEXT_Y_OFF = (FONT_SIZE / 2) - 2;
    private static final Color CIRCLE_BORDER_COLOR = new Color(192, 0, 0, 192);
    private static final Color CIRCLE_COLOR = new Color(160, 0, 0, 128);
    private static final double CIRCLE_SIZE_FACTOR = 2.75;
    private static final int MIN_CIRCLE_SIZE = 7;
    private static final Color SQUARE_BORDER_COLOR = new Color(0, 0, 0);
    private static final Color SQUARE_COLOR = new Color(255, 50, 255, 160);
    private static final Color CLIENT_COLOR = new Color(255, 100, 0);
    private static final Color EXPL_COLOR = new Color(255, 160, 160);
    private static final Color PART_COLOR = new Color(255, 0, 100);
    private static final Color ANIMATE_COLOR = new Color(100, 0, 255);
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);
    private static final BasicStroke STROKE = new BasicStroke(1);
    private static final BasicStroke STROKE2 = new BasicStroke(3);
    // multiply by 16 for CGI 'f' param
    private static final int MODE_ROUTERS = 1;
    private static final int MODE_EXPL = 2;
    private static final int MODE_CLIENT = 4;
    private static final int MODE_PART = 8;
    private static final int MODE_ANIM = 16;
    private static final int MODE_FF = 32;
    // put a dot on every country /?f=1024
    private static final int MODE_TEST = 64;
    private static final int MODE_DEFAULT = MODE_ROUTERS | MODE_EXPL | MODE_CLIENT | MODE_ANIM;

    private int tunnelCount;

    /**
     *
     */
    public MapMaker() {
        this(ContextHelper.getContext(null));
    }

    /**
     *
     */
    public MapMaker(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(MapMaker.class);
    }

    private static class Mercator {
        public final int x, y;
        public Mercator(int x, int y) {
            this.x = x; this.y = y;
        }
        @Override
        public int hashCode() {
            return x + y;
        }
        @Override
        public boolean equals(Object o) {
            Mercator m = (Mercator) o;
            return x == m.x && y == m.y;
        }
    }

    /**
     * @param mode bitmask, 0 for default, see MODE definitions above
     */
    public boolean render(int mode, OutputStream out) throws IOException {
        if (_mercator.isEmpty()) {
            _log.warn("mercator file not found");
            return false;
        }
        out.write(render(mode).getBytes("UTF-8"));
        out.flush();
        return true;
    }

    /**
     * @param mode see above
     */
    public String render(int mode) throws IOException {
        if (mode == 0)
            mode = MODE_DEFAULT;
        ObjectCounterUnsafe<String> countries = new ObjectCounterUnsafe<String>();
        if ((mode & (MODE_ROUTERS | MODE_FF)) != 0) {
            boolean ff = (mode & MODE_FF) != 0;
            for (RouterInfo ri : _context.netDb().getRouters()) {
                if (ff && ri.getCapabilities().indexOf('f') < 0)
                    continue;
                Hash key = ri.getIdentity().getHash();
                String country = _context.commSystem().getCountry(key);
                if (country != null)
                    countries.increment(country);
            }
        }
        return render(mode, countries);
    }

    /**
     * @param mode see above
     */
    public String render(int mode, ObjectCounterUnsafe<String> countries) throws IOException {
        // only for string widths
        Graphics2D gg = new SimpleSVGGraphics2D(1, 1);
        StringBuilder buf = new StringBuilder(32768);
        SimpleSVGMaker g = new SimpleSVGMaker(buf);
        g.startSVG(WIDTH, MAP_HEIGHT, TRANSPARENT, "mapoverlaysvg", null);
        Font large = new Font(FONT_NAME, FONT_STYLE, FONT_SIZE);

        buf.append("<a href=\"/netdb?f=16\">");
        g.drawText(_t("Routers"), 25, 700, TEXT_COLOR, large, null, hints);
        buf.append("</a>\n");
        buf.append("<a href=\"/netdb?f=512\">");
        g.drawText(_t("Floodfills"), 25, 725, TEXT_COLOR, large, null, hints);
        buf.append("</a>\n");
        buf.append("<a href=\"/netdb?f=32\">");
        g.drawText(_t("Exploratory Tunnels"), 25, 750, TEXT_COLOR, large, null, hints);
        buf.append("</a>\n");
        buf.append("<a href=\"/netdb?f=64\">");
        g.drawText(_t("Client Tunnels"), 25, 775, TEXT_COLOR, large, null, hints);
        buf.append("</a>\n");
        buf.append("<a href=\"/netdb?f=128\">");
        g.drawText(_t("Participating Tunnels"), 25, 800, TEXT_COLOR, large, null, hints);
        buf.append("</a>\n");

        boolean test = (mode & MODE_TEST) != 0;
        if (test)
            g.drawText("TEST MODE", 25, 825, TEXT_COLOR, large, null, hints);

        if ((mode & (MODE_ROUTERS | MODE_FF | MODE_TEST)) != 0) {
            Set<String> cset;
            if (test) {
                cset = _context.commSystem().getCountries().keySet();
            } else {
                cset = countries.objects();
            }
            for (String c : cset) {
                Mercator m = _mercator.get(c);
                if (m == null)
                    continue;
                int count = test ? 1 : countries.count(c);
                String title = getTranslatedCountry(c);
                if (!test)
                    title += ": " + ngettext("{0} router", "{0} routers", count);

                hints.put(KEY_ELEMENT_ID, "mapoverlaytext-" + c);
                hints.put(KEY_ELEMENT_CLASS, "mapoverlaytext dynamic");
                //hints.put(KEY_ELEMENT_TITLE, title);
                c = c.toUpperCase(Locale.US);
                double width = getStringWidth(c, large, gg);
                int xoff = (int) (width / 2);
                g.drawText(c.toUpperCase(Locale.US), rotate(m.x) - xoff, m.y + IMG_Y_OFF + TEXT_Y_OFF, TEXT_COLOR, large, null, hints);
                hints.clear();

                // put the circle on top of the text so it captures the title
                int sz = Math.max(MIN_CIRCLE_SIZE, (int) (CIRCLE_SIZE_FACTOR * Math.sqrt(count)));
                // add count to ID so it will be replaced on change by ajaxchanges
                hints.put(KEY_ELEMENT_ID, "mapoverlaycircle-" + c + '-' + count);
                hints.put(KEY_ELEMENT_CLASS, "mapoverlaycircle dynamic");
                hints.put(KEY_ELEMENT_TITLE, title);
                drawCircle(g, rotate(m.x), m.y + IMG_Y_OFF, sz);
                hints.clear();
            }
        }

        String us = _context.commSystem().getOurCountry();
        if (!test && us != null) {
            Mercator mus = _mercator.get(us);
            if (mus != null) {
                hints.put(KEY_ELEMENT_ID, "mapoverlaysquare-me");
                hints.put(KEY_ELEMENT_CLASS, "mapoverlaysquare");
                hints.put(KEY_ELEMENT_TITLE, _t("My router"));
                drawSquare(g, rotate(mus.x), mus.y + IMG_Y_OFF, 24);
                if ((mode & (MODE_EXPL | MODE_CLIENT)) != 0) {
                    TunnelManagerFacade tm = _context.tunnelManager();
                    tunnelCount = 0;
                    if ((mode & MODE_EXPL) != 0) {
                        renderPool(mode, g, mus, tm.getInboundExploratoryPool(), EXPL_COLOR);
                        renderPool(mode, g, mus, tm.getOutboundExploratoryPool(), EXPL_COLOR);
                    }
                    if ((mode & MODE_CLIENT) != 0) {
                        Map<Hash, TunnelPool> pools = tm.getInboundClientPools();
                        for (TunnelPool tp : pools.values()) {
                            if (tp.getSettings().getAliasOf() != null)
                                continue;
                            renderPool(mode, g, mus, tp, CLIENT_COLOR);
                        }
                        pools = tm.getOutboundClientPools();
                        for (TunnelPool tp : pools.values()) {
                            if (tp.getSettings().getAliasOf() != null)
                                continue;
                            renderPool(mode, g, mus, tp, CLIENT_COLOR);
                        }
                    }
                }
                if ((mode & MODE_PART) != 0) {
                    ObjectCounterUnsafe<String> tunnels = new ObjectCounterUnsafe<String>();
                    List<HopConfig> participating = _context.tunnelDispatcher().listParticipatingTunnels();
                    for (int i = 0; i < participating.size(); i++) {
                        HopConfig cfg = participating.get(i);
                        Hash from = cfg.getReceiveFrom();
                        Hash to = cfg.getSendTo();
                        String c = null;
                        if (from != null) {
                            c = _context.commSystem().getCountry(from);
                            if (c != null)
                                tunnels.increment(c);
                        }
                        if (to != null) {
                            String d = _context.commSystem().getCountry(to);
                            // only count once if to and from same country
                            if (d != null && !d.equals(c))
                                tunnels.increment(d);
                        }
                    }
                    renderParticipating(g,  mus, tunnels);
                }
            }
        }
        g.endSVG();
        return buf.toString();
    }

   /**
    * Draw circle centered on x,y with a radius given
    */
    private void drawCircle(SimpleSVGMaker g, int x, int y, int radius) {
        g.drawCircle(x, y, radius, CIRCLE_BORDER_COLOR, CIRCLE_COLOR, STROKE, null, hints);
    }

   /**
    * Draw square centered on x,y with a width/height given
    */
    private void drawSquare(SimpleSVGMaker g, int x, int y, int sz) {
        g.drawSquare(x, y, sz, SQUARE_BORDER_COLOR, SQUARE_COLOR, STROKE, null, hints);
    }

    /*
     * @param mode see above
     */
    private void renderPool(int mode, SimpleSVGMaker g, Mercator mus, TunnelPool tp, Color color) {
        boolean isInbound = tp.getSettings().isInbound();
        boolean isExpl = tp.getSettings().isExploratory();
        // shift to 4 corners of box
        int off = 12;
        if (isExpl) {
            if (isInbound)
                mus = new Mercator(mus.x - off, mus.y - off);
            else
                mus = new Mercator(mus.x + off, mus.y - off);
        } else {
            if (isInbound)
                mus = new Mercator(mus.x - off, mus.y + off);
            else
                mus = new Mercator(mus.x + off, mus.y + off);
        }
        List<TunnelInfo> tunnels = tp.listTunnels();
        List<Mercator> hops = new ArrayList<Mercator>(8);
        String nick = isExpl ? null : tp.getSettings().getDestinationNickname();
        int[] x = new int[8];
        int[] y = new int[8];
        for (TunnelInfo info : tunnels) {
            int length = info.getLength();
            if (length < 2)
                continue;
            StringBuilder cbuf = new StringBuilder(16);
            if (!isInbound)
                cbuf.append("(me)");
            // gateway first
            for (int j = 0; j < length; j++) {
                Mercator m;
                if (isInbound && j == length - 1) {
                    m = mus;
                } else if (!isInbound && j == 0) {
                    m = mus;
                } else {
                    if (cbuf.length() > 0)
                        cbuf.append("->");  // SVGMaker will escape
                    Hash peer = info.getPeer(j);
                    String country = _context.commSystem().getCountry(peer);
                    if (country == null) {
                        cbuf.append('?');
                        continue;
                    }
                    cbuf.append(country.toUpperCase(Locale.US));
                    Mercator mc = _mercator.get(country);
                    if (mc == null)
                        continue;
                    m = mc;
                }
                if (hops.isEmpty() || !m.equals(hops.get(hops.size() - 1))) {
                    hops.add(m);
                }
            }
            if (isInbound)
                cbuf.append("->(me)");  // SVGMaker will escape
            int sz = hops.size();
            if (sz > 1) {
                for (int i = 0; i < sz; i++) {
                    Mercator m = hops.get(i);
                    x[i] = rotate(m.x);
                    y[i] = m.y + IMG_Y_OFF;
                }
                long tid = isInbound ? info.getReceiveTunnelId(length - 1).getTunnelId()
                                     : info.getSendTunnelId(0).getTunnelId();
                String svgid = "mapoverlaytunnel-" + tid;
                String title;
                if (isInbound) {
                    if (isExpl)
                        title = _t("Inbound exploratory tunnel");
                    else
                        title = _t("Inbound client tunnel");
                } else {
                    if (isExpl)
                        title = _t("Outbound exploratory tunnel");
                    else
                        title = _t("Outbound client tunnel");
                }
                if (nick != null)
                    title += " (" + nick + ')';
                title += " " + tid + " " + cbuf;
                hints.put(KEY_ELEMENT_ID, svgid);
                hints.put(KEY_ELEMENT_CLASS, "mapoverlaytunnel dynamic");
                hints.put(KEY_ELEMENT_TITLE, title);
                g.drawPolyline(x, y, sz, color, STROKE2, null, hints);
                hints.clear();
                if ((mode & MODE_ANIM) != 0) {
                    hints.put(KEY_ELEMENT_ID, "mapoverlayanim-" + tid);
                    hints.put(KEY_ELEMENT_CLASS, "mapoverlayanim dynamic");
                    // 3 hops is 10 sec
                    String anim = "<animateMotion dur=\"" + String.format(Locale.US, "%.1f", (sz - 1) * 3.3f) + "s\" repeatCount=\"2\" " +
                              "onbegin=\"beginCircleAnim('mapoverlayanim-" + tid + "')\" " +
                              "onend=\"endCircleAnim('mapoverlayanim-" + tid + "')\" " +
                              // wait for line drawing animation to stop,
                              // and spread out the start times
                              "begin=\"" + (5000 + (250 * (++tunnelCount))) + "ms\" " +
                              " >\n" +
                              "    <mpath href=\"#" + svgid + "\" />\n" +
                              "  </animateMotion>";
                    hints.put(KEY_ELEMENT_INNERSVG, anim);
                    // place them off-screen until animation starts
                    // the js will reset to (0,0) on begin, and remove them on end
                    drawCircle(g, -5, 0, 5);
                    hints.clear();
                }
            } else {
                if (_log.shouldDebug())
                    _log.debug("Can't draw tunnel path " + cbuf);
            }
            hops.clear();
        }
    }

    /*
     * One line to each country, width = number of tunnels
     */
    private void renderParticipating(SimpleSVGMaker g, Mercator mus, ObjectCounterUnsafe<String> tunnels) {
        int usx = rotate(mus.x);
        int usy = mus.y + IMG_Y_OFF;
        int off = 12;
        for (String c : tunnels.objects()) {
            Mercator m = _mercator.get(c);
            if (m == null)
                continue;
            if (m.equals(mus))
                continue;
            int count = tunnels.count(c);
            hints.put(KEY_ELEMENT_ID, "part-" + c + '-' + count);
            hints.put(KEY_ELEMENT_CLASS, "mapoverlaytunnel dynamic");
            String title = getTranslatedCountry(c) + ": " + ngettext("{0} participating tunnel", "{0} participating tunnels", count);
            hints.put(KEY_ELEMENT_TITLE, title);
            // shift to 4 corners of box
            int mx, my;
            int tx = rotate(m.x);
            int ty = m.y + IMG_Y_OFF;
            if (tx > usx) {
                tx -= off;
                mx =  usx + off;
            } else {
                tx += off;
                mx =  usx - off;
            }
            if (ty > usy) {
                ty -= off;
                my =  usy + off;
            } else {
                ty += off;
                my =  usy - off;
            }
            g.drawLine(tx, ty, mx, my, PART_COLOR,
                       new BasicStroke(Math.max(3, Math.min(30, 3 * count / 2))),
                       null, hints);
            hints.clear();
        }
    }

    private static double getStringWidth(String text, Font font, Graphics2D g) {
        return font.getStringBounds(text, 0, text.length(), g.getFontRenderContext()).getBounds().getWidth();
    }

    private static int rotate(int x) {
        x += IMG_X_OFF;
        if (x < 0)
            x += WIDTH;
        return x;
    }
    
    /**
     * Countries now in a separate bundle
     * @param code two-letter country code
     */
    private String getTranslatedCountry(String code) {
        String name = _context.commSystem().getCountryName(code);
        return Translate.getString(name, _context, Messages.COUNTRY_BUNDLE_NAME);
    }

    /** translate a string */
    private String _t(String s) {
        return Messages.getString(s, _context);
    }

   /**
    * Read in and parse the mercator country file.
    * The file need not be sorted.
    * This file was created from the lat/long data at
    * https://developers.google.com/public-data/docs/canonical/countries_csv
    * using the convertLatLongFile() method below.
    */
    private static void readMercatorFile() {
        InputStream is = MapMaker.class.getResourceAsStream("/net/i2p/router/web/resources/" + MERCATOR_DEFAULT);
        if (is == null) {
            System.out.println("Country file not found");
            return;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line = null;
            while ( (line = br.readLine()) != null) {
                try {
                    if (line.charAt(0) == '#')
                        continue;
                    String[] s = DataHelper.split(line, ",", 3);
                    if (s.length < 3)
                        continue;
                    int x = Integer.parseInt(s[1]);
                    int y = Integer.parseInt(s[2]);
                    _mercator.put(s[0], new Mercator(x, y));
                } catch (NumberFormatException nfe) {
                    System.out.println("Bad line " + nfe);
                }
            }
        } catch (IOException ioe) {
            System.out.println("Error reading the Country File " + ioe);
        } finally {
            if (is != null) try { is.close(); } catch (IOException ioe) {}
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }
    }

    /** translate a string */
    private String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p, _context);
    }

    // Following is code to convert the latlong.csv file from Google
    // to our mercator.txt file which is bundled in the war.

/****
    private static final String LATLONG_DEFAULT = "latlong.csv";

    private static class LatLong {
        public final float lat, lon;
        public LatLong(float lat, float lon) {
            this.lat = lat; this.lon = lon;
        }
    }
****/

    /**
     * Read in and parse the lat/long file.
     * The file need not be sorted.
     * Convert the lat/long data from
     * https://developers.google.com/public-data/docs/canonical/countries_csv
     * to a 1600x1600 web mercator (85 degree) format.
     * latlong.csv input format: XX,lat,long,countryname (lat and long are signed floats)
     * mercator.txt output format: xx,x,y (x and y are integers 0-1200, not adjusted for a cropped projection)
     * Output is sorted by country code.
     */
/***
    private static void convertLatLongFile() {
        Map<String, LatLong> latlong = new HashMap<String, LatLong>();
        InputStream is = null;
        BufferedReader br = null;
        try {
            is = new FileInputStream(LATLONG_DEFAULT);
            br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line = null;
            while ( (line = br.readLine()) != null) {
                try {
                    if (line.charAt(0) == '#')
                        continue;
                    String[] s = DataHelper.split(line, ",", 4);
                    if (s.length < 3)
                        continue;
                    String lc = s[0].toLowerCase(Locale.US);
                    float lat = Float.parseFloat(s[1]);
                    float lon = Float.parseFloat(s[2]);
                    latlong.put(lc, new LatLong(lat, lon));
                } catch (NumberFormatException nfe) {
                    System.out.println("Bad line " + nfe);
                }
            }
        } catch (IOException ioe) {
            System.out.println("Error reading the Country File " + ioe);
        } finally {
            if (is != null) try { is.close(); } catch (IOException ioe) {}
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }
        Map<String, Mercator> mercator = new TreeMap<String, Mercator>();
        for (Map.Entry<String, LatLong> e : latlong.entrySet()) {
            String c = e.getKey();
            LatLong ll = e.getValue();
            mercator.put(c, convert(ll));
        }
        for (Map.Entry<String, Mercator> e : mercator.entrySet()) {
            String c = e.getKey();
            Mercator m = e.getValue();
            System.out.println(c + ',' + m.x + ',' + m.y);
        }
    }
****/

    /**
     *  https://stackoverflow.com/questions/57322997/convert-geolocation-to-pixels-on-a-mercator-projection-image
     */
/****
    private static Mercator convert(LatLong latlong) {
        double rad = latlong.lat * Math.PI / 180;
        double mercn = Math.log(Math.tan((Math.PI / 4) + (rad / 2)));
        double x = (latlong.lon + 180d) * (WIDTH / 360d);
        double y = (HEIGHT / 2d) - ((WIDTH * mercn) / (2 * Math.PI));
        return new Mercator((int) Math.round(x), (int) Math.round(y));
    }

    public static void main(String args[]) {
        convertLatLongFile();
    }
****/
}
