/*
 * $Id $
 * Copyright (c) 2003 mihi
 * Licensed under the GNU Public License (GPL) as published by the
 * Free Software Foundation, using version 2 or later of the GPL.  You
 * should have recieved the GPL with this source code, otherwise see
 * http://www.fsf.org/copyleft/
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;

public class FetchSeeds {

    /**
     * Fetch seednodes.
     * 
     * @param destination the dir to store the seednodes to
     * @param sourceURL the URL to fetch the seednode from - must end
     * with a slash
     * @return whether new seed nodes could be fetched
     */
    public static boolean fetchSeeds(File destination, String sourceURL) {
        InputStream in = null;
        try {
            URL source = new URL(sourceURL);
            URLConnection con = source.openConnection();
            in = con.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line = br.readLine()) != null) {
                int pos = line.indexOf(" <a href=\"routerInfo-");
                if (pos == -1) continue;
                line = line.substring(pos + 10);
                pos = line.indexOf("\"");
                if (pos == -1) continue;
                line = line.substring(0, pos);
                fetchFile(new File(destination, line), sourceURL + line);
                System.out.println(line);
            }
            br.close();
            return true;
        } catch (IOException ex) {
            System.err.println("Unable to fetch seeds from " + sourceURL + ": " + ex.getMessage());
            //ex.printStackTrace();
            return false;
        } finally {
            if (in != null) try {
                in.close();
            } catch (IOException ioe) {
            }
        }
    }

    public static void fetchFile(File destFile, String fileURL) throws IOException {
        URL url = new URL(fileURL);
        InputStream in = url.openStream();
        OutputStream out = new FileOutputStream(destFile);
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        in.close();
        out.flush();
        out.close();
    }

    /**
     * test main method.
     */
    public static void main(String[] args) {
        switch (args.length) {
        case 1:
            fetchSeeds(new File(args[0]), "http://i2p.dnsalias.net/i2pdb/");
            return;
        case 2:
            fetchSeeds(new File(args[0]), args[1]);
            return;
        default:
            System.out.println("Usage: FetchSeeds <outDir>");
            System.out.println("    or FetchSeeds <outDir> <seedURL>");
            System.out.println("The default seedURL is http://i2p.dnsalias.net/i2pdb/");
            return;
        }
    }
}