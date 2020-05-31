package net.i2p.i2pcontrol.servlets.jsonrpc2handlers;

import com.thetransactioncompany.jsonrpc2.JSONRPC2Error;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Request;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Response;
import com.thetransactioncompany.jsonrpc2.server.MessageContext;
import com.thetransactioncompany.jsonrpc2.server.RequestHandler;
import net.i2p.i2pcontrol.I2PControlVersion;
import net.i2p.i2pcontrol.security.AuthToken;
import net.i2p.i2pcontrol.security.SecurityManager;

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

public class AuthenticateHandler implements RequestHandler {

    private static final String[] requiredArgs = {"Password", "API"};
    private final JSONRPC2Helper _helper;
    private final SecurityManager _secMan;

    public AuthenticateHandler(JSONRPC2Helper helper, SecurityManager secMan) {
        _helper = helper;
        _secMan = secMan;
    }

    // Reports the method names of the handled requests
    public String[] handledRequests() {
        return new String[] {"Authenticate"};
    }

    // Processes the requests
    public JSONRPC2Response process(JSONRPC2Request req, MessageContext ctx) {
        if (req.getMethod().equals("Authenticate")) {
            JSONRPC2Error err = _helper.validateParams(requiredArgs, req, JSONRPC2Helper.USE_NO_AUTH);
            if (err != null)
                return new JSONRPC2Response(err, req.getID());

            Map<String, Object> inParams = req.getNamedParams();

            String pwd = (String) inParams.get("Password");

            // Try get an AuthToken

            AuthToken token = _secMan.validatePasswd(pwd);
            if (token == null) {
                return new JSONRPC2Response(JSONRPC2ExtendedError.INVALID_PASSWORD, req.getID());
            }

            Object api = inParams.get("API");
            err = validateAPIVersion(api);
            if (err != null)
                return new JSONRPC2Response(err, req.getID());


            Map<String, Object> outParams = new HashMap<String, Object>(4);
            outParams.put("Token", token.getId());
            outParams.put("API", I2PControlVersion.API_VERSION);
            return new JSONRPC2Response(outParams, req.getID());
        } else {
            // Method name not supported
            return new JSONRPC2Response(JSONRPC2Error.METHOD_NOT_FOUND, req.getID());
        }
    }

    /**
     * Validate the provided I2PControl API version against the ones supported by I2PControl.
     */
    private static JSONRPC2Error validateAPIVersion(Object api) {

        Integer apiVersion;
        try {
            apiVersion = ((Number) api).intValue();
        } catch (ClassCastException e) {
            e.printStackTrace();
            return JSONRPC2ExtendedError.UNSPECIFIED_API_VERSION;
        }

        if (!I2PControlVersion.SUPPORTED_API_VERSIONS.contains(apiVersion)) {
            String supportedAPIVersions = "";
            for (Integer i : I2PControlVersion.SUPPORTED_API_VERSIONS) {
                supportedAPIVersions += ", " + i;
            }
            return new JSONRPC2Error(JSONRPC2ExtendedError.UNSUPPORTED_API_VERSION.getCode(),
                                     "The provided API version \'" + apiVersion + "\' is not supported. The supported versions are" + supportedAPIVersions + ".");
        }
        return null;
    }
}
