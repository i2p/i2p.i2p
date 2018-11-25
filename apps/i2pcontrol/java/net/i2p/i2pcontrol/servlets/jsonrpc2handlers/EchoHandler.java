package net.i2p.i2pcontrol.servlets.jsonrpc2handlers;

import com.thetransactioncompany.jsonrpc2.JSONRPC2Error;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Request;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Response;
import com.thetransactioncompany.jsonrpc2.server.MessageContext;
import com.thetransactioncompany.jsonrpc2.server.RequestHandler;

import java.util.HashMap;
import java.util.Map;

public class EchoHandler implements RequestHandler {

    private static final String[] requiredArgs = {"Echo"};
    private final JSONRPC2Helper _helper;

    public EchoHandler(JSONRPC2Helper helper) {
        _helper = helper;
    }

    // Reports the method names of the handled requests
    public String[] handledRequests() {
        return new String[] {"Echo"};
    }

    // Processes the requests
    public JSONRPC2Response process(JSONRPC2Request req, MessageContext ctx) {
        if (req.getMethod().equals("Echo")) {
            JSONRPC2Error err = _helper.validateParams(requiredArgs, req);
            if (err != null)
                return new JSONRPC2Response(err, req.getID());

            Map<String, Object> inParams = req.getNamedParams();
            String echo = (String) inParams.get("Echo");
            Map<String, Object> outParams = new HashMap<String, Object>(4);
            outParams.put("Result", echo);
            return new JSONRPC2Response(outParams, req.getID());
        }
        else {
            // Method name not supported
            return new JSONRPC2Response(JSONRPC2Error.METHOD_NOT_FOUND, req.getID());
        }
    }
}
