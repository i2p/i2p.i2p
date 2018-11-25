package com.thetransactioncompany.jsonrpc2.server;


import com.thetransactioncompany.jsonrpc2.JSONRPC2Request;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Response;


/**
 * Interface for handling JSON-RPC 2.0 requests.
 *
 * @author Vladimir Dzhuvinov
 */
public interface RequestHandler {

	
	/**
	 * Gets the names of the handled JSON-RPC 2.0 request methods.
	 *
	 * @return The names of the handled JSON-RPC 2.0 request methods.
	 */
	public String[] handledRequests();
	
	
	/**
	 * Processes a JSON-RPC 2.0 request.
	 *
	 * @param request    A valid JSON-RPC 2.0 request instance. Must not be
	 *                   {@code null}.
	 * @param requestCtx Context information about the request message, may 
	 *                   be {@code null} if undefined.
	 *
	 * @return The resulting JSON-RPC 2.0 response. It indicates success
	 *         or an error, such as METHOD_NOT_FOUND.
	 */
	public JSONRPC2Response process(final JSONRPC2Request request, final MessageContext requestCtx);
}
