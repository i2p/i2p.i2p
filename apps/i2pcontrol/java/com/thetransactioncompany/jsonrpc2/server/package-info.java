/** 
 * Simple server framework for processing JSON-RPC 2.0 requests and
 * notifications.
 *
 * <p>Usage:
 *
 * <ol>
 *     <li>Implement {@link com.thetransactioncompany.jsonrpc2.server.RequestHandler request}
 *         and / or {@link com.thetransactioncompany.jsonrpc2.server.NotificationHandler notification}
 *         handlers for the various expected JSON-RPC 2.0 messages. A handler
 *         may process one or more request/notification methods (identified by 
 *         method name).
 *     <li>Create a new {@link com.thetransactioncompany.jsonrpc2.server.Dispatcher}
 *         and register the handlers with it.
 *     <li>Pass the received JSON-RPC 2.0 requests and notifications to the 
 *         appropriate {@code Dispatcher.dispatch(...)} method, then, if the 
 *         message is a request, pass the resulting JSON-RPC 2.0 response back
 *         to the client.
 * </ol>
 *
 * <p>Direct package dependencies:
 *
 * <ul>
 *     <li><b><a href="http://software.dzhuvinov.com/json-rpc-2.0-base.html">JSON-RPC 2.0 Base</a></b> 
 *        [<i>com.thetransactioncompany.jsonrpc2</i>] to construct and represent 
 *         JSON-RPC 2.0 messages.
 *     <li><b>Java Servlet API</b> [<i>javax.servlet.http</i>] for constructing 
 *         {@link com.thetransactioncompany.jsonrpc2.server.MessageContext}
 *         objects from HTTP servlet requests.
 * </ul>
 *
 * @author Vladimir Dzhuvinov
 */
package com.thetransactioncompany.jsonrpc2.server;
