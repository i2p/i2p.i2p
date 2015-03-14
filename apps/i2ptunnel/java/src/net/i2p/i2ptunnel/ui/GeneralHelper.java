package net.i2p.i2ptunnel.ui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.i2ptunnel.I2PTunnelClientBase;
import net.i2p.i2ptunnel.SSLClientUtil;
import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;
import net.i2p.i2ptunnel.web.Messages;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;

/**
 * General helper functions used by all UIs.
 *
 * @since 0.9.19
 */
public class GeneralHelper {
    private static final String OPT = TunnelController.PFX_OPTION;

    public static TunnelController getController(TunnelControllerGroup tcg, int tunnel) {
        if (tunnel < 0) return null;
        if (tcg == null) return null;
        List<TunnelController> controllers = tcg.getControllers();
        if (controllers.size() > tunnel)
            return controllers.get(tunnel); 
        else
            return null;
    }

    public static List<String> saveTunnel(
            I2PAppContext context, TunnelControllerGroup tcg, int tunnel, TunnelConfig config) {
        List<String> msgs = updateTunnelConfig(tcg, tunnel, config);
        msgs.addAll(saveConfig(context, tcg));
        return msgs;
    }

    protected static List<String> updateTunnelConfig(TunnelControllerGroup tcg, int tunnel, TunnelConfig config) {
        // Get current tunnel controller
        TunnelController cur = getController(tcg, tunnel);

        Properties props = config.getConfig();

        List<String> msgs = new ArrayList<String>();
        String type = props.getProperty(TunnelController.PROP_TYPE);
        if (TunnelController.TYPE_STD_CLIENT.equals(type) || TunnelController.TYPE_IRC_CLIENT.equals(type)) {
            //
            // If we switch to SSL, create the keystore here, so we can store the new properties.
            // Down in I2PTunnelClientBase it's very hard to save the config.
            //
            if (Boolean.parseBoolean(props.getProperty(OPT + I2PTunnelClientBase.PROP_USE_SSL))) {
                try {
                    boolean created = SSLClientUtil.verifyKeyStore(props, OPT);
                    if (created) {
                        // config now contains new keystore props
                        msgs.add("Created new self-signed certificate for tunnel " + getTunnelName(tcg, tunnel));
                    }        
                } catch (IOException ioe) {       
                    msgs.add("Failed to create new self-signed certificate for tunnel " +
                            getTunnelName(tcg, tunnel) + ", check logs: " + ioe);
                }        
            }        
        }        
        if (cur == null) {
            // creating new
            cur = new TunnelController(props, "", true);
            tcg.addController(cur);
            if (cur.getStartOnLoad())
                cur.startTunnelBackground();
        } else {
            cur.setConfig(props, "");
        }
        // Only modify other shared tunnels
        // if the current tunnel is shared, and of supported type
        if (Boolean.parseBoolean(cur.getSharedClient()) && TunnelController.isClient(cur.getType())) {
            // all clients use the same I2CP session, and as such, use the same I2CP options
            List<TunnelController> controllers = tcg.getControllers();

            for (int i = 0; i < controllers.size(); i++) {
                TunnelController c = controllers.get(i);

                // Current tunnel modified by user, skip
                if (c == cur) continue;

                // Only modify this non-current tunnel
                // if it belongs to a shared destination, and is of supported type
                if (Boolean.parseBoolean(c.getSharedClient()) && TunnelController.isClient(c.getType())) {
                    Properties cOpt = c.getConfig("");
                    config.updateTunnelQuantities(cOpt);
                    cOpt.setProperty("option.inbound.nickname", TunnelConfig.SHARED_CLIENT_NICKNAME);
                    cOpt.setProperty("option.outbound.nickname", TunnelConfig.SHARED_CLIENT_NICKNAME);

                    c.setConfig(cOpt, "");
                }
            }
        }

        return msgs;
    }

    protected static List<String> saveConfig(I2PAppContext context, TunnelControllerGroup tcg) { 
        List<String> rv = tcg.clearAllMessages();
        try {
            tcg.saveConfig();
            rv.add(0, _("Configuration changes saved", context));
        } catch (IOException ioe) {
            Log log = context.logManager().getLog(GeneralHelper.class);
            log.error("Failed to save config file", ioe);
            rv.add(0, _("Failed to save configuration", context) + ": " + ioe.toString());
        }
        return rv;
    }

    /**
     *  Stop the tunnel, delete from config,
     *  rename the private key file if in the default directory
     */
    public static List<String> deleteTunnel(
            I2PAppContext context, TunnelControllerGroup tcg,int tunnel, TunnelConfig config) {
        List<String> msgs;
        TunnelController cur = getController(tcg, tunnel);
        if (cur == null) {
            msgs = new ArrayList<>();
            msgs.add("Invalid tunnel number");
            return msgs;
        }

        msgs = tcg.removeController(cur);
        msgs.addAll(saveConfig(context, tcg));

        // Rename private key file if it was a default name in
        // the default directory, so it doesn't get reused when a new
        // tunnel is created.
        // Use configured file name if available, not the one from the form.
        String pk = cur.getPrivKeyFile();
        if (pk == null)
            pk = config.getPrivKeyFile();
        if (pk != null && pk.startsWith("i2ptunnel") && pk.endsWith("-privKeys.dat") &&
            ((!TunnelController.isClient(cur.getType())) || cur.getPersistentClientKey())) {
            File pkf = new File(context.getConfigDir(), pk);
            if (pkf.exists()) {
                String name = cur.getName();
                if (name == null) {
                    name = cur.getDescription();
                    if (name == null) {
                        name = cur.getType();
                        if (name == null)
                            name = Long.toString(context.clock().now());
                    }
                }
                name = name.replace(' ', '_').replace(':', '_').replace("..", "_").replace('/', '_').replace('\\', '_');
                name = "i2ptunnel-deleted-" + name + '-' + context.clock().now() + "-privkeys.dat";
                File backupDir = new SecureFile(context.getConfigDir(), TunnelController.KEY_BACKUP_DIR);
                File to;
                if (backupDir.isDirectory() || backupDir.mkdir())
                    to = new File(backupDir, name);
                else
                    to = new File(context.getConfigDir(), name);
                boolean success = FileUtil.rename(pkf, to);
                if (success)
                    msgs.add("Private key file " + pkf.getAbsolutePath() +
                             " renamed to " + to.getAbsolutePath());
            }
        }
        return msgs;
    }

    public static String getTunnelName(TunnelControllerGroup tcg, int tunnel) {
        TunnelController tun = getController(tcg, tunnel);
        if (tun != null)
            return tun.getName();
        else
            return null;
    }

    protected static String _(String key, I2PAppContext context) {
        return Messages._(key, context);
    }
}
