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
    private Log _log;
    private String _action;
    private int _controllerNum;
    
    public WebStatusPageHelper() {
        _action = null;
        _controllerNum = -1;
        _log = I2PAppContext.getGlobalContext().logManager().getLog(WebStatusPageHelper.class);
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
    
    public String getActionResults() {
        try {
            return processAction();
        } catch (Throwable t) {
            _log.log(Log.CRIT, "Internal error processing web status", t);
            return "Internal error processing request - " + t.getMessage();
        }
    }
    
    public String getSummaryList() {
        StringBuffer buf = new StringBuffer(4*1024);
        buf.append("<ul>");
        List tunnels = TunnelControllerGroup.getInstance().getControllers();
        for (int i = 0; i < tunnels.size(); i++) {
            buf.append("<li>\n");
            getSummary(buf, i, (TunnelController)tunnels.get(i));
            buf.append("</li>\n");
        }
        buf.append("</ul>");
        return buf.toString();
    }
    
    private void getSummary(StringBuffer buf, int num, TunnelController controller) {
        buf.append("<b>").append(controller.getName()).append("</b>: ");
        if (controller.getIsRunning()) {
            buf.append("<i>running</i> ");
            buf.append("<a href=\"index.jsp?num=").append(num).append("&action=stop\">stop</a> ");
        } else {
            buf.append("<i>not running</i> ");
            buf.append("<a href=\"index.jsp?num=").append(num).append("&action=start\">start</a> ");
        }
        buf.append("<a href=\"edit.jsp?num=").append(num).append("\">edit</a> ");
        buf.append("<br />\n");
        controller.getSummary(buf);
    }
    
    private String processAction() {
        if ( (_action == null) || (_action.trim().length() <= 0) )
            return getMessages();
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
        List msgs = TunnelControllerGroup.getInstance().stopAllControllers();
        return getMessages(msgs);
    }
    private String startAll() {
        List msgs = TunnelControllerGroup.getInstance().startAllControllers();
        return getMessages(msgs);
    }
    private String restartAll() {
        List msgs = TunnelControllerGroup.getInstance().restartAllControllers();
        return getMessages(msgs);
    }
    private String reloadConfig() {
        TunnelControllerGroup.getInstance().reloadControllers();
        return "Config reloaded";
    }
    private String start() {
        if (_controllerNum < 0) return "Invalid tunnel";
        List controllers = TunnelControllerGroup.getInstance().getControllers();
        if (_controllerNum >= controllers.size()) return "Invalid tunnel";
        TunnelController controller = (TunnelController)controllers.get(_controllerNum);
        controller.startTunnel();
        return getMessages(controller.clearMessages());
    }
    
    private String stop() {
        if (_controllerNum < 0) return "Invalid tunnel";
        List controllers = TunnelControllerGroup.getInstance().getControllers();
        if (_controllerNum >= controllers.size()) return "Invalid tunnel";
        TunnelController controller = (TunnelController)controllers.get(_controllerNum);
        controller.stopTunnel();
        return getMessages(controller.clearMessages());
    }
    
    private String getMessages() {
        return getMessages(TunnelControllerGroup.getInstance().clearAllMessages());
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
