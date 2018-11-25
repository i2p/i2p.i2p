package net.i2p.i2pcontrol.servlets.jsonrpc2handlers;

import com.thetransactioncompany.jsonrpc2.JSONRPC2Error;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Request;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Response;
import com.thetransactioncompany.jsonrpc2.server.MessageContext;
import com.thetransactioncompany.jsonrpc2.server.RequestHandler;

import net.i2p.I2PAppContext;
import net.i2p.i2pcontrol.I2PControlController;
import net.i2p.i2pcontrol.security.SecurityManager;
import net.i2p.i2pcontrol.servlets.configuration.ConfigurationManager;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

/*
 *  Copyright 2011 hottuna (dev@robertfoss.se)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

public class I2PControlHandler implements RequestHandler {

    private static final int BW_BURST_PCT = 110;
    private static final int BW_BURST_TIME = 20;
    private final RouterContext _context;
    private final Log _log;
    //private final ConfigurationManager _conf;
    private final SecurityManager _secMan;
    private final JSONRPC2Helper _helper;

    public I2PControlHandler(RouterContext ctx, JSONRPC2Helper helper, SecurityManager secMan) {
        _helper = helper;
        _secMan = secMan;
        _context = ctx;
        if (ctx != null)
            _log = ctx.logManager().getLog(I2PControlHandler.class);
        else
            _log = I2PAppContext.getGlobalContext().logManager().getLog(I2PControlHandler.class);
    }


    // Reports the method names of the handled requests
    public String[] handledRequests() {
        return new String[] {"I2PControl"};
    }

    // Processes the requests
    public JSONRPC2Response process(JSONRPC2Request req, MessageContext ctx) {
        if (req.getMethod().equals("I2PControl")) {
            return process(req);
        } else {
            // Method name not supported
            return new JSONRPC2Response(JSONRPC2Error.METHOD_NOT_FOUND, req.getID());
        }
    }


    private JSONRPC2Response process(JSONRPC2Request req) {
        JSONRPC2Error err = _helper.validateParams(null, req);
        if (err != null)
            return new JSONRPC2Response(err, req.getID());

/**** only if we enable host/port changes
        if (_context == null) {
            return new JSONRPC2Response(
                       new JSONRPC2Error(JSONRPC2Error.INTERNAL_ERROR.getCode(),
                                         "RouterContext was not initialized. Query failed"),
                       req.getID());
        }
****/
        Map<String, Object> inParams = req.getNamedParams();
        Map<String, Object> outParams = new HashMap<String, Object>(4);

        boolean restartNeeded = false;
        boolean settingsSaved = false;
        String inParam;

/****
        if (inParams.containsKey("i2pcontrol.port")) {
            Integer  oldPort = _conf.getConf("i2pcontrol.listen.port", 7650);
            if ((inParam = (String) inParams.get("i2pcontrol.port")) != null) {
                if (oldPort == null || !inParam.equals(oldPort.toString())) {
                    Integer newPort;
                    try {
                        newPort = Integer.valueOf(inParam);
                        if (newPort < 1 || newPort > 65535) {
                            throw new NumberFormatException();
                        }
                    } catch (NumberFormatException e) {
                        return new JSONRPC2Response(
                                   new JSONRPC2Error(JSONRPC2Error.INVALID_PARAMS.getCode(),
                                                     "\"i2pcontrol.port\" must be a string representing a number in the range 1-65535. " + inParam + " isn't valid."),
                                   req.getID());
                    }
                    try {
                        SslSocketConnector ssl = I2PControlController.buildSslListener(_conf.getConf("i2pcontrol.listen.address", "127.0.0.1"), newPort);
                        I2PControlController.clearListeners();
                        I2PControlController.replaceListener(ssl);

                        _conf.setConf("i2pcontrol.listen.port", newPort);


                        ConfigurationManager.writeConfFile();
                        outParams.put("i2pcontrol.port", null);
                        settingsSaved = true;
                    } catch (Exception e) {
                        try {
                            _conf.setConf("i2pcontrol.listen.port", oldPort);
                            SslSocketConnector ssl = I2PControlController.buildSslListener(_conf.getConf("i2pcontrol.listen.address", "127.0.0.1"), oldPort);
                            I2PControlController.clearListeners();
                            I2PControlController.replaceListener(ssl);
                        } catch (Exception e2) {
                            _log.log(Log.CRIT, "Unable to resume server on previous listening port.");
                        }
                        _log.error("Client tried to set listen port to, " + newPort + " which isn't valid.", e);
                        return new JSONRPC2Response(
                                   new JSONRPC2Error(JSONRPC2Error.INVALID_PARAMS.getCode(),
                                                     "\"i2pcontrol.port\" has been set to a port that is already in use, reverting. " +
                                                     inParam + " is an already used port.\n"
                                                     + "Exception: " + e.toString()),
                                   req.getID());
                    }
                }
            }
            outParams.put("RestartNeeded", restartNeeded);
        }
****/

        if (inParams.containsKey("i2pcontrol.password")) {
            if ((inParam = (String) inParams.get("i2pcontrol.password")) != null) {
                if (_secMan.setPasswd(inParam)) {
                    outParams.put("i2pcontrol.password", null);
                    settingsSaved = true;
                }
            }
        }

/****
        if (inParams.containsKey("i2pcontrol.address")) {
            String oldAddress = _conf.getConf("i2pcontrol.listen.address", "127.0.0.1");
            if ((inParam = (String) inParams.get("i2pcontrol.address")) != null) {
                if ((oldAddress == null || !inParam.equals(oldAddress.toString()) &&
                        (inParam.equals("0.0.0.0") || inParam.equals("127.0.0.1")))) {
                    InetAddress[] newAddress;

                    try {
                        newAddress = InetAddress.getAllByName(inParam);
                    } catch (UnknownHostException e) {
                        return new JSONRPC2Response(
                                   new JSONRPC2Error(JSONRPC2Error.INVALID_PARAMS.getCode(),
                                                     "\"i2pcontrol.address\" must be a string representing a hostname or ipaddress. " + inParam + " isn't valid."),
                                   req.getID());
                    }
                    try {
                        SslSocketConnector ssl = I2PControlController.buildSslListener(inParam, _conf.getConf("i2pcontrol.listen.port", 7650));
                        I2PControlController.clearListeners();
                        I2PControlController.replaceListener(ssl);
                        _conf.setConf("i2pcontrol.listen.address", inParam);

                        ConfigurationManager.writeConfFile();
                        outParams.put("i2pcontrol.address", null);
                        settingsSaved = true;
                    } catch (Exception e) {
                        _conf.setConf("i2pcontrol.listen.address", oldAddress);
                        try {
                            SslSocketConnector ssl = I2PControlController.buildSslListener(inParam, _conf.getConf("i2pcontrol.listen.port", 7650));
                            I2PControlController.clearListeners();
                            I2PControlController.replaceListener(ssl);
                        } catch (Exception e2) {
                            _log.log(Log.CRIT, "Unable to resume server on previous listening ip.");
                        }
                        _log.error("Client tried to set listen address to, " + newAddress.toString() + " which isn't valid.", e);
                        return new JSONRPC2Response(
                                   new JSONRPC2Error(JSONRPC2Error.INVALID_PARAMS.getCode(),
                                                     "\"i2pcontrol.address\" has been set to an invalid address, reverting. "),    req.getID());
                    }
                }
            } else {
                outParams.put("i2pcontrol.address", oldAddress);
            }
            outParams.put("RestartNeeded", restartNeeded);
        }
****/

        outParams.put("SettingsSaved", settingsSaved);
        return new JSONRPC2Response(outParams, req.getID());
    }
}
