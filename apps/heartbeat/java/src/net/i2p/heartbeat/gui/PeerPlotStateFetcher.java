package net.i2p.heartbeat.gui;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.StringTokenizer;

import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

class PeerPlotStateFetcher {
    private final static Log _log = new Log(PeerPlotStateFetcher.class);
    
    /**
     * Fetch and fill the specified state structure
     * @param receptor the 'receptor' (callbacks)
     * @param state the state
     */
    public static void fetchPeerPlotState(FetchStateReceptor receptor, PeerPlotState state) {
        I2PThread t = new I2PThread(new Fetcher(receptor, state));
        t.setDaemon(true);
        t.setName("Fetch state from " + state.getPlotConfig().getLocation());
        t.start();
    }
    
    /**
     * Callback stuff . . .
     */
    public interface FetchStateReceptor {
        /**
         * Called when a peer plot state is fetched
         * @param state state that was fetched
         */
        void peerPlotStateFetched(PeerPlotState state);
    }
    
    private static class Fetcher implements Runnable {
        private PeerPlotState _state;
        private FetchStateReceptor _receptor;

        /**
         * Creates a Fetcher thread
         * @param receptor the 'receptor' (callbacks)
         * @param state the state
         */
        public Fetcher(FetchStateReceptor receptor, PeerPlotState state) {
            _state = state;
            _receptor = receptor;
        }
        
        /* (non-Javadoc)
         * @see java.lang.Runnable#run()
         */
        public void run() {
            String loc = _state.getPlotConfig().getLocation();
            _log.debug("Load called [" + loc + "]");
            InputStream in = null;
            try {
                try {
                    URL location = new URL(loc);
                    in = location.openStream();
                } catch (MalformedURLException mue) {
                    _log.debug("Not a url [" + loc + "]");
                    in = null;
                }
                
                if (in == null)
                    in = new FileInputStream(loc);
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                String line = null;
                while ( (line = reader.readLine()) != null) {
                    handleLine(line);
                }
                
                if (valid())
                    _receptor.peerPlotStateFetched(_state);
            } catch (IOException ioe) {
                _log.error("Error retrieving from the location [" + loc + "]", ioe);
            } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
            }
        }
        
        /** 
         * check to make sure we've got everything we need 
         * @return true [always]
         */
        boolean valid() {
            return true;
        }
        
        /**
         * handle a line from the data set - these can be formatted in one of the
         * following ways.  <p />
         *
         * <pre>
         * peer            khWYqCETu9YtPUvGV92ocsbEW5DezhKlIG7ci8RLX3g=
         * local           u-9hlR1ik2hemXf0HvKMfeRgrS86CbNQh25e7XBhaQE=
         * peerDest        [base 64 of the full destination]
         * localDest       [base 64 of the full destination]
         * numTunnelHops   2
         * comment         Test with localhost sending 30KB every 20 seconds
         * sendFrequency   20
         * sendSize        30720
         * sessionStart    20040409.22:51:10.915
         * currentTime     20040409.23:31:39.607
         * numPending      2
         * lifetimeSent    118
         * lifetimeRecv    113
         * #averages       minutes sendMs  recvMs  numLost
         * periodAverage   1       1843    771     0
         * periodAverage   5       786     752     1
         * periodAverage   30      855     735     3
         * #action status  date and time sent      sendMs  replyMs
         * EVENT   OK      20040409.23:21:44.742   691     670
         * EVENT   OK      20040409.23:22:05.201   671     581
         * EVENT   OK      20040409.23:22:26.301   1182    1452
         * EVENT   OK      20040409.23:22:47.322   24304   1723
         * EVENT   OK      20040409.23:23:08.232   2293    1081
         * EVENT   OK      20040409.23:23:29.332   1392    641
         * EVENT   OK      20040409.23:23:50.262   641     761
         * EVENT   OK      20040409.23:24:11.102   651     701
         * EVENT   OK      20040409.23:24:31.401   841     621
         * EVENT   OK      20040409.23:24:52.061   651     681
         * EVENT   OK      20040409.23:25:12.480   701     1623
         * EVENT   OK      20040409.23:25:32.990   1442    1212
         * EVENT   OK      20040409.23:25:54.230   591     631
         * EVENT   OK      20040409.23:26:14.620   620     691
         * EVENT   OK      20040409.23:26:35.199   1793    1432
         * EVENT   OK      20040409.23:26:56.570   661     641
         * EVENT   OK      20040409.23:27:17.200   641     660
         * EVENT   OK      20040409.23:27:38.120   611     921
         * EVENT   OK      20040409.23:27:58.699   831     621
         * EVENT   OK      20040409.23:28:19.559   801     661
         * EVENT   OK      20040409.23:28:40.279   601     611
         * EVENT   OK      20040409.23:29:00.648   601     621
         * EVENT   OK      20040409.23:29:21.288   701     661
         * EVENT   LOST    20040409.23:29:41.828
         * EVENT   LOST    20040409.23:30:02.327
         * EVENT   LOST    20040409.23:30:22.656
         * EVENT   OK      20040409.23:31:24.305   1843    771
         * </pre>
         * 
         * @param line (see above)
         */
        private void handleLine(String line) {
            if (line.startsWith("peerDest"))
                handlePeerDest(line);
            else if (line.startsWith("localDest"))
                handleLocalDest(line);
            else if (line.startsWith("numTunnelHops"))
                handleNumTunnelHops(line);
            else if (line.startsWith("comment"))
                handleComment(line);
            else if (line.startsWith("sendFrequency"))
                handleSendFrequency(line);
            else if (line.startsWith("sendSize"))
                handleSendSize(line);
            else if (line.startsWith("periodAverage"))
                handlePeriodAverage(line);
            else if (line.startsWith("EVENT"))
                handleEvent(line);
            else if (line.startsWith("numPending"))
                handleNumPending(line);
            else if (line.startsWith("sessionStart"))
                handleSessionStart(line);
            else
                _log.debug("Not handled: " + line);
        }
        
        private void handlePeerDest(String line) {
            StringTokenizer tok = new StringTokenizer(line);
            tok.nextToken(); // ignore;
            String destKey = tok.nextToken();
            try {
                Destination d = new Destination();
                d.fromBase64(destKey);
                _state.getPlotConfig().getClientConfig().setPeer(d);
                _log.debug("Setting the peer to " + d.calculateHash().toBase64());
            } catch (DataFormatException dfe) {
                _log.error("Unable to parse the peerDest line: [" + line + "]", dfe);
            }
        }
        
        private void handleLocalDest(String line) {
            StringTokenizer tok = new StringTokenizer(line);
            tok.nextToken(); // ignore;
            String destKey = tok.nextToken();
            try {
                Destination d = new Destination();
                d.fromBase64(destKey);
                _state.getPlotConfig().getClientConfig().setUs(d);
            } catch (DataFormatException dfe) {
                _log.error("Unable to parse the localDest line: [" + line + "]", dfe);
            }
        }
        
        private void handleComment(String line) {
            StringTokenizer tok = new StringTokenizer(line);
            tok.nextToken(); // ignore;
            StringBuffer buf = new StringBuffer(line.length()-32);
            while (tok.hasMoreTokens())
                buf.append(tok.nextToken()).append(' ');
            _state.getPlotConfig().getClientConfig().setComment(buf.toString());
        }
        
        private void handleNumTunnelHops(String line) {
            StringTokenizer tok = new StringTokenizer(line);
            tok.nextToken(); // ignore;
            String num = tok.nextToken();
            try {
                int val = Integer.parseInt(num);
                _state.getPlotConfig().getClientConfig().setNumHops(val);
            } catch (NumberFormatException nfe) {
                _log.error("Unable to parse the numTunnelHops line: [" + line + "]", nfe);
            }
        }
        
        private void handleNumPending(String line) {
            StringTokenizer tok = new StringTokenizer(line);
            tok.nextToken(); // ignore;
            String num = tok.nextToken();
            try {
                int val = Integer.parseInt(num);
                _state.getCurrentData().setPendingCount(val);
            } catch (NumberFormatException nfe) {
                _log.error("Unable to parse the numPending line: [" + line + "]", nfe);
            }
        }
        
        private void handleSendFrequency(String line) {
            StringTokenizer tok = new StringTokenizer(line);
            tok.nextToken(); // ignore;
            String num = tok.nextToken();
            try {
                int val = Integer.parseInt(num);
                _state.getPlotConfig().getClientConfig().setSendFrequency(val);
            } catch (NumberFormatException nfe) {
                _log.error("Unable to parse the sendFrequency line: [" + line + "]", nfe);
            }
        }
        
        private void handleSendSize(String line) {
            StringTokenizer tok = new StringTokenizer(line);
            tok.nextToken(); // ignore;
            String num = tok.nextToken();
            try {
                int val = Integer.parseInt(num);
                _state.getPlotConfig().getClientConfig().setSendSize(val);
            } catch (NumberFormatException nfe) {
                _log.error("Unable to parse the sendSize line: [" + line + "]", nfe);
            }
        }
        
        private void handleSessionStart(String line) {
            StringTokenizer tok = new StringTokenizer(line);
            tok.nextToken(); // ignore;
            String date = tok.nextToken();
            try {
                long when = getDate(date);
                _state.getCurrentData().setSessionStart(when);
            } catch (NumberFormatException nfe) {
                _log.error("Unable to parse the sessionStart line: [" + line + "]", nfe);
            }
        }
        
        private void handlePeriodAverage(String line) {
            StringTokenizer tok = new StringTokenizer(line);
            tok.nextToken(); // ignore;
            try {
                // periodAverage minutes sendMs  recvMs  numLost
                int min = Integer.parseInt(tok.nextToken());
                int send = Integer.parseInt(tok.nextToken());
                int recv = Integer.parseInt(tok.nextToken());
                int lost = Integer.parseInt(tok.nextToken());
                _state.addAverage(min, send, recv, lost);
            } catch (NumberFormatException nfe) {
                _log.error("Unable to parse the sendSize line: [" + line + "]", nfe);
            }
        }
        
        private void handleEvent(String line) {
            StringTokenizer tok = new StringTokenizer(line);
            
            // * EVENT   OK      20040409.23:29:21.288   701     661
            // * EVENT   LOST    20040409.23:29:41.828
            tok.nextToken(); // ignore first two
            tok.nextToken();
            try {
                long when = getDate(tok.nextToken());
                if (when < 0) {
                    _log.error("Invalid EVENT line: [" + line + "]");
                    return;
                }
                if (tok.hasMoreTokens()) {
                    int sendMs = Integer.parseInt(tok.nextToken());
                    int recvMs = Integer.parseInt(tok.nextToken());
                    _state.addSuccess(when, sendMs, recvMs);
                } else {
                    _state.addLost(when);
                }
            } catch (NumberFormatException nfe) {
                _log.error("Unable to parse the EVENT line: [" + line + "]", nfe);
            }
        }
        
        private static final SimpleDateFormat _fmt = new SimpleDateFormat("yyyyMMdd.HH:mm:ss.SSS", Locale.UK);
        private long getDate(String date) {
            synchronized (_fmt) {
                try {
                    return _fmt.parse(date).getTime();
                } catch (ParseException pe) {
                    _log.error("Unable to parse the date [" + date + "]", pe);
                    return -1;
                }
            }
        }
        
        private void fakeRun() {
            try {
                Destination peer = new Destination();
                Destination us = new Destination();
                peer.fromBase64("3RPLOkQGlq8anNyNWhjbMyHxpAvUyUJKbiUejI80DnPR59T3blc7-XrBhQ2iPbf-BRAR~v1j34Kpba1eDyhPk2gevsE6ULO1irarJ3~C9WcQH2wAbNiVwfWqbh6onQ~YmkSpGNwGHD6ytwbvTyXeBJ" +
                                "cS8e6gmfNN-sYLn1aQu8UqWB3D6BmTfLtyS3eqWVk66Nrzmwy8E1Hvq5z~1lukYb~cyiDO1oZHAOLyUQtd9eN16yJY~2SRG8LiscpPMl9nSJUr6fmXMUubW-M7QGFH82Om-735PJUk6WMy1Hi9Vgh4Pxhdl7g" +
                                "fqGRWioFABdhcypb7p1Ca77p73uabLDFK-SjIYmdj7TwSdbNa6PCmzEvCEW~IZeZmnZC5B6pK30AdmD9vc641wUGce9xTJVfNRupf5L7pSsVIISix6FkKQk-FTW2RsZKLbuMCYMaPzLEx5gzODEqtI6Jf2teM" +
                                "d5xCz51RPayDJl~lJ-W0IWYfosnjM~KxYaqc4agviBuF5ZWeAAAA");
                us.fromBase64("W~JFpqSH8uopylox2V5hMbpcHSsb-dJkSKvdJ1vj~KQcUFJWXFyfbetBAukcGH5S559aK9oslU0qbVoMDlJITVC4OXfXSnVbJBP1IhsK8SvjSYicjmIi2fA~k4HvSh9Wxu~bg8yo~jgfHA8tjYpp" +
                              "K9QKc56BpkJb~hx0nNGy4Ny9eW~6A5AwAmHvwdt5NqcREYRMjRd63dMGm8BcEe-6FbOyMo3dnIFcETWAe8TCeoMxm~S1n~6Jlinw3ETxv-L6lQkhFFWnC5zyzQ~4JhVxxT3taTMYXg8td4CBGmrS078jcjW63" +
                              "rlSiQgZBlYfN3iEYmurhuIEV9NXRcmnMrBOQUAoXPpVuRIxJbaQNDL71FO2iv424n4YjKs84suAho34GGQKq7WoL5V5KQgihfcl0f~xne-qP3FtpoPFeyA9x-sA2JWDAsxoZlfvgkiP5eyOn23prT9TJK47HC" +
                              "VilHSV11uTVaC4Jc5YsjoBCZadWbgQnMCKlZ4jk-bLE1PSWLg7AAAA");
                _state.getPlotConfig().getClientConfig().setPeer(peer);
                _state.getPlotConfig().getClientConfig().setUs(us);
                _state.getPlotConfig().getClientConfig().setNumHops(2);
                _state.getPlotConfig().getClientConfig().setComment("we do stuff\nreally nifty stuff.  really");
                _state.getPlotConfig().getClientConfig().setAveragePeriods(new int[] { 1, 5, 30, 60 });
                int rnd = new java.util.Random().nextInt();
                if (rnd > 0) 
                    rnd = rnd % 10;
                else
                    rnd = (-rnd) % 10;
                _state.getPlotConfig().getClientConfig().setSendFrequency(rnd);
                _state.getPlotConfig().getClientConfig().setSendSize(16*1024);
                _state.getPlotConfig().getClientConfig().setStatDuration(10);
                _state.getPlotConfig().rebuildAverageSeriesConfigs();
                _state.setCurrentData(new StaticPeerData(_state.getPlotConfig().getClientConfig()));
                
                _receptor.peerPlotStateFetched(_state);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}