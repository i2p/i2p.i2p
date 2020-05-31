package net.i2p.i2pcontrol.servlets.jsonrpc2handlers;

import com.thetransactioncompany.jsonrpc2.JSONRPC2Error;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Request;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Response;
import com.thetransactioncompany.jsonrpc2.server.MessageContext;
import com.thetransactioncompany.jsonrpc2.server.RequestHandler;
import net.i2p.I2PAppContext;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;

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

public class GetRateHandler implements RequestHandler {

    private static final String[] requiredArgs = {"Stat", "Period"};
    private final JSONRPC2Helper _helper;

    public GetRateHandler(JSONRPC2Helper helper) {
        _helper = helper;
    }

    // Reports the method names of the handled requests
    public String[] handledRequests() {
        return new String[] {"GetRate"};
    }

    // Processes the requests
    public JSONRPC2Response process(JSONRPC2Request req, MessageContext ctx) {
        if (req.getMethod().equals("GetRate")) {
            JSONRPC2Error err = _helper.validateParams(requiredArgs, req);
            if (err != null)
                return new JSONRPC2Response(err, req.getID());

            Map<String, Object> inParams = req.getNamedParams();

            String input = (String) inParams.get("Stat");
            if (input == null) {
                return new JSONRPC2Response(JSONRPC2Error.INVALID_PARAMS, req.getID());
            }
            Number p = (Number) inParams.get("Period");
            if (p == null)
                return new JSONRPC2Response(JSONRPC2Error.INVALID_PARAMS, req.getID());
            long period = p.longValue();

            RateStat rateStat = I2PAppContext.getGlobalContext().statManager().getRate(input);

            // If RateStat or the requested period doesn't already exist, create them.
            if (rateStat == null || rateStat.getRate(period) == null) {
                long[] tempArr = new long[1];
                tempArr[0] = period;
                I2PAppContext.getGlobalContext().statManager().createRequiredRateStat(input, "I2PControl", "I2PControl", tempArr);
                rateStat = I2PAppContext.getGlobalContext().statManager().getRate(input);
            }
            if (rateStat.getRate(period) == null)
                return new JSONRPC2Response(JSONRPC2Error.INTERNAL_ERROR, req.getID());
            Map<String, Object> outParams = new HashMap<String, Object>(4);
            Rate rate = rateStat.getRate(period);
            rate.coalesce();
            outParams.put("Result", rate.getAverageValue());
            return new JSONRPC2Response(outParams, req.getID());
        }
        return new JSONRPC2Response(JSONRPC2Error.METHOD_NOT_FOUND, req.getID());
    }
}
