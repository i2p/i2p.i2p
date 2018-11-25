package net.i2p.i2pcontrol.servlets.jsonrpc2handlers;

import com.thetransactioncompany.jsonrpc2.JSONRPC2Error;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Request;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Response;
import com.thetransactioncompany.jsonrpc2.server.MessageContext;
import com.thetransactioncompany.jsonrpc2.server.RequestHandler;

import net.i2p.I2PAppContext;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class AdvancedSettingsHandler implements RequestHandler {

    private final RouterContext _context;
    private final Log _log;
    private final JSONRPC2Helper _helper;
    private static final String[] requiredArgs = {};

    public AdvancedSettingsHandler(RouterContext ctx, JSONRPC2Helper helper) {
        _helper = helper;
        _context = ctx;
        if (ctx != null)
            _log = ctx.logManager().getLog(AdvancedSettingsHandler.class);
        else
            _log = I2PAppContext.getGlobalContext().logManager().getLog(AdvancedSettingsHandler.class);
    }

    // Reports the method names of the handled requests
    public String[] handledRequests() {
        return new String[] {"AdvancedSettings"};
    }

    // Processes the requests
    @SuppressWarnings("unchecked")
    public JSONRPC2Response process(JSONRPC2Request req, MessageContext ctx) {
        if (req.getMethod().equals("AdvancedSettings")) {
            JSONRPC2Error err = _helper.validateParams(requiredArgs, req);
            if (err != null) {
                return new JSONRPC2Response(err, req.getID());
            }

            if (_context == null) {
                return new JSONRPC2Response(new JSONRPC2Error(
                                                JSONRPC2Error.INTERNAL_ERROR.getCode(),
                                                "RouterContext was not initialized. Query failed"),
                                            req.getID());
            }

            @SuppressWarnings("rawtypes")
            Map<String, Object> inParams = req.getNamedParams();
            Map outParams = new HashMap();

            if (inParams.containsKey("setAll")) {
                Object obj = inParams.get("setAll");
                if (!(obj instanceof Map)) {
                    JSONRPC2Error rpcErr = new JSONRPC2Error(JSONRPC2Error.INVALID_PARAMS.getCode(),
                            "Value of \"setAll\" is not a Map");
                    return new JSONRPC2Response(rpcErr, req.getID());
                }

                @SuppressWarnings("rawtypes")
                Map objMap = (Map) inParams.get("setAll");
                if (objMap.size() > 0)
                {
                    if (!(objMap.keySet().toArray()[0] instanceof String) &&
                            !(objMap.values().toArray()[0] instanceof String)) {
                        JSONRPC2Error rpcErr = new JSONRPC2Error(JSONRPC2Error.INVALID_PARAMS.getCode(),
                                "Map of settings does not contain String keys and values");
                        return new JSONRPC2Response(rpcErr, req.getID());
                    }

                    if (!checkTypes(objMap)) {
                        JSONRPC2Error rpcErr = new JSONRPC2Error(JSONRPC2Error.INTERNAL_ERROR.getCode(),
                                "Some of the supplied values are not strings");
                        return new JSONRPC2Response(rpcErr, req.getID());
                    }

                    Map<String, String> allSettings = (Map<String, String>) objMap;
                    boolean success = setAdvancedSettings(allSettings, true);
                    if (!success) {
                        JSONRPC2Error rpcErr = new JSONRPC2Error(JSONRPC2Error.INTERNAL_ERROR.getCode(),
                                "Failed to save new config");
                        return new JSONRPC2Response(rpcErr, req.getID());
                    }

                } else {
                    // Empty list of settings submitted
                    boolean success = setAdvancedSettings(null, true);
                    if (!success) {
                        JSONRPC2Error rpcErr = new JSONRPC2Error(JSONRPC2Error.INTERNAL_ERROR.getCode(),
                                "Failed to save new config");
                        return new JSONRPC2Response(rpcErr, req.getID());
                    }
                }
            }

            if (inParams.containsKey("getAll")) {
                outParams.put("getAll", getAdvancedSettings());
            }

            if (inParams.containsKey("set")) {
                Object obj = inParams.get("set");
                if (!(obj instanceof Map)) {
                    JSONRPC2Error rpcErr = new JSONRPC2Error(JSONRPC2Error.INVALID_PARAMS.getCode(),
                            "Value of \"set\" is not a Map");
                    return new JSONRPC2Response(rpcErr, req.getID());
                }

                Map objMap = (Map) inParams.get("set");
                if (objMap.size() > 0)
                {
                    if (!(objMap.keySet().toArray()[0] instanceof String) &&
                            !(objMap.values().toArray()[0] instanceof String)) {
                        JSONRPC2Error rpcErr = new JSONRPC2Error(JSONRPC2Error.INVALID_PARAMS.getCode(),
                                "Map of settings does not contain String keys and values");
                        return new JSONRPC2Response(rpcErr, req.getID());
                    }

                    if (!checkTypes(objMap)) {
                        JSONRPC2Error rpcErr = new JSONRPC2Error(JSONRPC2Error.INTERNAL_ERROR.getCode(),
                                "Some of the supplied values are not strings");
                        return new JSONRPC2Response(rpcErr, req.getID());
                    }

                    Map<String, String> allSettings = (Map<String, String>) objMap;
                    boolean success = setAdvancedSettings(allSettings, false);
                    if (!success) {
                        JSONRPC2Error rpcErr = new JSONRPC2Error(JSONRPC2Error.INTERNAL_ERROR.getCode(),
                                "Failed to save new config");
                        return new JSONRPC2Response(rpcErr, req.getID());
                    }

                } else {
                    // Empty list of settings submitted
                    JSONRPC2Error rpcErr = new JSONRPC2Error(JSONRPC2Error.INVALID_PARAMS.getCode(),
                            "Map of settings does not contain any entries");
                    return new JSONRPC2Response(rpcErr, req.getID());
                }
            }

            if (inParams.containsKey("get")) {
                Object obj = inParams.get("get");
                if (!(obj instanceof String)) {
                    JSONRPC2Error rpcErr = new JSONRPC2Error(JSONRPC2Error.INVALID_PARAMS.getCode(),
                            "Value of \"get\" is not a string");
                    return new JSONRPC2Response(rpcErr, req.getID());
                }
                String getStr = (String) obj;
                String getVal = getAdvancedSetting(getStr);
                Map<String, String> outMap = new HashMap<String, String>();
                outMap.put(getStr, getVal);
                outParams.put("get", outMap);
            }

            return new JSONRPC2Response(outParams, req.getID());
        } else {
            // Method name not supported
            return new JSONRPC2Response(JSONRPC2Error.METHOD_NOT_FOUND, req.getID());
        }
    }

    private String getAdvancedSetting(String key) {
        return _context.router().getConfigSetting(key);
    }


    private Map<String, String> getAdvancedSettings() {
        return _context.router().getConfigMap();
    }

    private boolean checkTypes(Map<String, Object> newSettings) {
        for (String key : newSettings.keySet()) {
            if (!(newSettings.get(key) instanceof String)) {
                return false;
            }
        }

        return true;
    }

    private boolean setAdvancedSettings(Map<String, String> newSettings, boolean clearConfig) {
        Set<String> unsetKeys = null;

        if (clearConfig) {
            unsetKeys = new HashSet<String>(_context.router().getConfigSettings());

            for (String key : newSettings.keySet()) {
                unsetKeys.remove(key);
            }
        }

        return _context.router().saveConfig(newSettings, unsetKeys);
    }
}
