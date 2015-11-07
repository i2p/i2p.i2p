package net.i2p.router.web;

import java.io.File;
import java.io.IOException;

import net.i2p.util.FileUtil;


/**
 *  Dump out our local SSL certs, if any
 *
 *  @since 0.9.23
 */
public class CertHelper extends HelperBase {
    
    private static final String DIR = "certificates";
    private static final String I2CP = "i2cp/i2cp.local.crt";
    private static final String CONSOLE = "console/console.local.crt";
    private static final String I2PTUNNEL_DIR = "i2ptunnel";

    public String getSummary() {
        File dir = new File(_context.getConfigDir(), DIR);
        try {
            _out.write("<h3>");
            _out.write(_t("Local SSL Certificates"));
            _out.write("</h3>\n");
            // console
            output("Console", new File(dir, CONSOLE));
            // I2CP
            output("I2CP", new File(dir, I2CP));
            // i2ptunnel clients
            File tunnelDir = new File(_context.getConfigDir(), I2PTUNNEL_DIR);
            boolean hasTunnels = false;
            File[] tunnels = tunnelDir.listFiles();
            if (tunnels != null) {
                for (int i = 0; i < tunnels.length; i++) {
                    File f = tunnels[i];
                    if (!f.isFile())
                        continue;
                    String name = f.getName();
                    if (!name.endsWith(".local.crt"))
                        continue;
                    if (!name.startsWith("i2ptunnel-"))
                        continue;
                    String b32 = name.substring(10, name.length() - 10);
                    output(_t("I2PTunnel") + ' ' + b32, f);
                    hasTunnels = true;
                }
            }
            if (!hasTunnels)
                output(_t("I2PTunnel"), null);
            // anything else? plugins?
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return ""; 
    }

    /**
     *  @param file may be null
     */
    private void output(String name, File file) throws IOException {
        _out.write("<p><h4>");
        _out.write("</h4>");
        _out.write(name);
        if (file != null && file.exists()) {
            String cert = FileUtil.readTextFile(file.toString(), -1, true);
            if (cert != null) {
                _out.write("\n<textarea readonly=\"readonly\">\n");
                _out.write(cert);
                _out.write("</textarea>\n");
            } else {
                _out.write(": read failure");
            }
        } else {
            _out.write(": ");
            _out.write(_t("none"));
        }
        _out.write("</p>\n");
    }
}
