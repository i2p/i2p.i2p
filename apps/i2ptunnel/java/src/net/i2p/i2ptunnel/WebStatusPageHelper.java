package net.i2p.i2ptunnel;

import java.util.List;
import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Ugly hack to let the web interface access the list of known tunnels and
 * control their operation.  Any data submitted by setting properties are 
 * acted upon by calling getActionResults() (which returns any messages
 * generated).  In addition, the getSummaryList() generates the html for
 * summarizing all of the tunnels known, including both their status and the
 * links to edit, stop, or start them.
 *
 */
public class WebStatusPageHelper {
    private I2PAppContext _context;
    private Log _log;
    private String _action;
    private int _controllerNum;
    private long _nonce;
    
    public WebStatusPageHelper() {
        _context = I2PAppContext.getGlobalContext();
        _action = null;
        _controllerNum = -1;
        _log = _context.logManager().getLog(WebStatusPageHelper.class);
    }
    
    public void setAction(String action) {
        _action = action;
    }
    public void setNum(String num) {
        if (num != null) {
            try {
                _controllerNum = Integer.parseInt(num);
            } catch (NumberFormatException nfe) {
                _controllerNum = -1;
            }
        }
    }
    public void setNonce(long nonce) { _nonce = nonce; }
    public void setNonce(String nonce) {
        if (nonce != null) {
            try { 
                _nonce = Long.parseLong(nonce); 
            } catch (NumberFormatException nfe) {}
        }
    }
    
    public String getActionResults() {
        try {
            return processAction();
        } catch (Throwable t) {
            _log.log(Log.CRIT, "Internal error processing web status", t);
            return "Internal error processing request - " + t.getMessage();
        }
    }
    
    public String getSummaryList() {
        TunnelControllerGroup group = TunnelControllerGroup.getInstance();
        if (group == null)
            return "<b>I2PTunnel instances not yet started - please be patient</b>\n";
            
        long nonce = _context.random().nextLong();
        StringBuffer buf = new StringBuffer(4*1024);
        buf.append("<ul>");
        List tunnels = group.getControllers();
        for (int i = 0; i < tunnels.size(); i++) {
            buf.append("<li>\n");
            getSummary(buf, i, (TunnelController)tunnels.get(i), nonce);
            buf.append("</li>\n");
        }
        buf.append("</ul>");
        
        buf.append("<hr /><form action=\"index.jsp\" method=\"GET\">\n");
        buf.append("<input type=\"hidden\" name=\"nonce\" value=\"").append(nonce).append("\" />\n");
        buf.append("<input type=\"submit\" name=\"action\" value=\"Stop all\" />\n");
        buf.append("<input type=\"submit\" name=\"action\" value=\"Start all\" />\n");
        buf.append("<input type=\"submit\" name=\"action\" value=\"Restart all\" />\n");
        buf.append("<input type=\"submit\" name=\"action\" value=\"Reload config\" />\n");        
        buf.append("</form>\n");
        
        System.setProperty(getClass().getName() + ".nonce", nonce+"");
        
        return buf.toString();
    }
    
    private void getSummary(StringBuffer buf, int num, TunnelController controller, long nonce) {
        buf.append("<b>").append(controller.getName()).append("</b>: ");
        if (controller.getIsRunning()) {
            buf.append("<i>running</i> ");
            buf.append("<a href=\"index.jsp?num=").append(num);
            buf.append("&nonce=").append(nonce);
            buf.append("&action=stop\">stop</a> ");
        } else if (controller.getIsStarting()) {
            buf.append("<i>startup in progress (please be patient)</i>");
        } else {
            buf.append("<i>not running</i> ");
            buf.append("<a href=\"index.jsp?num=").append(num);
            buf.append("&nonce=").append(nonce);
            buf.append("&action=start\">start</a> ");
        }
        buf.append("<a href=\"edit.jsp?num=").append(num).append("\">edit</a> ");
        buf.append("<br />\n");
        controller.getSummary(buf);
    }
    
    private String processAction() {
        if ( (_action == null) || (_action.trim().length() <= 0) )
            return getMessages();
        String expected = System.getProperty(getClass().getName() + ".nonce");
        if ( (expected == null) || (!expected.equals(Long.toString(_nonce))) )
            return "<b>Invalid nonce, are you being spoofed?</b>";
        if ("Stop all".equals(_action)) 
            return stopAll();
        else if ("Start all".equals(_action))
            return startAll();
        else if ("Restart all".equals(_action))
            return restartAll();
        else if ("Reload config".equals(_action))
            return reloadConfig();
        else if ("stop".equals(_action))
            return stop();
        else if ("start".equals(_action))
            return start();
        else 
            return "Action <i>" + _action + "</i> unknown";
    }
    private String stopAll() {
        TunnelControllerGroup group = TunnelControllerGroup.getInstance();
        if (group == null)
            return "<b>I2PTunnel instances not yet started - please be patient</b>\n";
        
        List msgs = group.stopAllControllers();
        return getMessages(msgs);
    }
    private String startAll() {
        TunnelControllerGroup group = TunnelControllerGroup.getInstance();
        if (group == null)
            return "<b>I2PTunnel instances not yet started - please be patient</b>\n";
        
        List msgs = group.startAllControllers();
        return getMessages(msgs);
    }
    private String restartAll() {
        TunnelControllerGroup group = TunnelControllerGroup.getInstance();
        if (group == null)
            return "<b>I2PTunnel instances not yet started - please be patient</b>\n";
        
        List msgs = group.restartAllControllers();
        return getMessages(msgs);
    }
    private String reloadConfig() {
        TunnelControllerGroup group = TunnelControllerGroup.getInstance();
        if (group == null)
            return "<b>I2PTunnel instances not yet started - please be patient</b>\n";
        
        group.reloadControllers();
        return "Config reloaded";
    }
    private String start() {
        TunnelControllerGroup group = TunnelControllerGroup.getInstance();
        if (group == null)
            return "<b>I2PTunnel instances not yet started - please be patient</b>\n";
        
        if (_controllerNum < 0) return "Invalid tunnel";
        
        List controllers = group.getControllers();
        if (_controllerNum >= controllers.size()) return "Invalid tunnel";
        TunnelController controller = (TunnelController)controllers.get(_controllerNum);
        controller.startTunnelBackground();
        return getMessages(controller.clearMessages());
    }
    
    private String stop() {
        TunnelControllerGroup group = TunnelControllerGroup.getInstance();
        if (group == null)
            return "<b>I2PTunnel instances not yet started - please be patient</b>\n";
        
        if (_controllerNum < 0) return "Invalid tunnel";
        
        List controllers = group.getControllers();
        if (_controllerNum >= controllers.size()) return "Invalid tunnel";
        TunnelController controller = (TunnelController)controllers.get(_controllerNum);
        controller.stopTunnel();
        return getMessages(controller.clearMessages());
    }
    
    private String getMessages() {
        TunnelControllerGroup group = TunnelControllerGroup.getInstance();
        if (group == null)
            return "";
        
        return getMessages(group.clearAllMessages());
    }
    
    private String getMessages(List msgs) {
        if (msgs == null) return "";
        int num = msgs.size();
        switch (num) {
            case 0: return "";
            case 1: return (String)msgs.get(0);
            default:
                StringBuffer buf = new StringBuffer(512);
                buf.append("<ul>");
                for (int i = 0; i < num; i++)
                    buf.append("<li>").append((String)msgs.get(i)).append("</li>\n");
                buf.append("</ul>\n");
                return buf.toString();
        }
    }
}
