package net.i2p.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.zip.GZIPOutputStream;

/**
 * Simple helper class to submit data via HTTP Post
 *
 * @deprecated unused
 */
public class HTTPSendData {
    private final static Log _log = new Log(HTTPSendData.class);

    /**
     * Submit an HTTP post to the given url, sending the data specified
     *
     * @param url url to post to
     * @param length number of bytes in the dataToSend to, er, send
     * @param dataToSend stream of bytes to be sent
     * @return true if the data was posted successfully
     */
    public static boolean postData(String url, long length, InputStream dataToSend) {
        try {
            URL rurl = new URL(url);
            return postData(rurl, length, dataToSend);
        } catch (MalformedURLException mue) {
            return false;
        }
    }

    /**
     * Submit an HTTP post to the given url, sending the data specified
     *
     * @param url url to post to
     * @param length number of bytes in the dataToSend to, er, send
     * @param dataToSend stream of bytes to be sent
     */
    public static boolean postData(URL url, long length, InputStream dataToSend) {
        try {
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setDoInput(true);
            con.setDoOutput(true);
            con.setUseCaches(false);
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-length", "" + length);

            OutputStream out = con.getOutputStream();
            byte buf[] = new byte[1 * 1024];
            int read;
            long sent = 0;
            GZIPOutputStream zipOut = new GZIPOutputStream(out);
            while ((read = dataToSend.read(buf)) != -1) {
                zipOut.write(buf, 0, read);
                sent += read;
                if (sent >= length) break;
            }

            zipOut.flush();
            zipOut.finish();
            zipOut.close();
            out.close();

            int rv = con.getResponseCode();
            _log.debug("Posted " + sent + " bytes: " + rv);
            return length == sent;
        } catch (IOException ioe) {
            _log.error("Error posting the data", ioe);
            return false;
        }
    }

/****
    public static void main(String args[]) {
        byte data[] = new byte[4096];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte) ((i % 26) + 'a');

        boolean sent = HTTPSendData.postData("http://dev.i2p.net/cgi-bin/submitMessageHistory", data.length,
                                             new ByteArrayInputStream(data));
        _log.debug("Sent? " + sent);
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ie) { // nop
        }
    }
****/
}
