package net.i2p.router.web.helpers;

import java.io.File;
import java.io.IOException;

import net.i2p.data.DataHelper;
import net.i2p.util.FileUtil;
import net.i2p.util.FileSuffixFilter;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.web.HelperBase;


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
    private static final String SAM_DIR = "sam";
    private static final String EEPSITE_DIR = "eepsite";

    public String getSummary() {
        File dir = new File(_context.getConfigDir(), DIR);
        try {
            _out.write("<h3>");
            _out.write(_t("Local SSL Certificates"));
            _out.write("</h3>\n");
            // console
            output(_t("Router Console"), new File(dir, CONSOLE));
            // I2CP
            output(_t("I2CP"), new File(dir, I2CP));

            // i2ptunnel clients
            File tunnelDir = new File(_context.getConfigDir(), I2PTUNNEL_DIR);
            boolean hasTunnels = false;
            File[] tunnels = tunnelDir.listFiles(new FileSuffixFilter("i2ptunnel-", ".local.crt"));
            if (tunnels != null) {
                for (int i = 0; i < tunnels.length; i++) {
                    File f = tunnels[i];
                    String name = f.getName();
                    String b32 = name.substring(10, name.length() - 10);
                    output(_t("I2PTunnel") + ' ' + b32, f);
                    hasTunnels = true;
                }
            }
            if (!hasTunnels)
                output(_t("I2PTunnel"), null);

            // SAM
            tunnelDir = new File(dir, SAM_DIR);
            hasTunnels = false;
            tunnels = tunnelDir.listFiles(new FileSuffixFilter("sam-", ".local.crt"));
            if (tunnels != null) {
                for (int i = 0; i < tunnels.length; i++) {
                    File f = tunnels[i];
                    output(_t("SAM"), f);
                    hasTunnels = true;
                }
            }
            if (!hasTunnels)
                output(_t("SAM"), null);

            // Eepsite
            tunnelDir = new File(dir, EEPSITE_DIR);
            hasTunnels = false;
            tunnels = tunnelDir.listFiles(new FileSuffixFilter(".crt"));
            if (tunnels != null) {
                for (int i = 0; i < tunnels.length; i++) {
                    File f = tunnels[i];
                    String name = f.getName();
                    output(_t("Website") + ' ' + name.substring(0, name.length() - 4), f);
                    hasTunnels = true;
                }
            }
            if (!hasTunnels)
                output(_t("Website"), null);

            // Family
            _out.write("<h3>");
            _out.write(_t("Local Router Family Certificate"));
            _out.write("</h3>\n");
            String family = _context.getProperty(FamilyKeyCrypto.PROP_FAMILY_NAME);
            if (family != null) {
                File f = new File(dir, "family");
                f = new File(f, family + ".crt");
                output(_t("Family") + ": " + DataHelper.escapeHTML(family), f);
            } else {
                _out.write("<p>");
                _out.write(_t("none"));
                _out.write("</p>\n");
            }

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
        _out.write(name);
        _out.write("</h4>");
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
            _out.write("<p>");
            _out.write(_t("none"));
            _out.write("</p>\n");
        }
    }
}
