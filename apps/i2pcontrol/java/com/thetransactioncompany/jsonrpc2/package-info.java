/** 
 * Classes to represent, parse and serialise JSON-RPC 2.0 requests, 
 * notifications and responses.
 *
 * <p>JSON-RPC is a protocol for 
 * <a href="http://en.wikipedia.org/wiki/Remote_procedure_call">remote 
 * procedure calls</a> (RPC) using <a href="http://www.json.org" >JSON</a>
 * - encoded requests and responses. It can be easily relayed over HTTP 
 * and is of JavaScript origin, making it ideal for use in dynamic web 
 * applications in the spirit of Ajax and Web 2.0.
 *
 * <p>This package implements <b>version 2.0</b> of the protocol, with the 
 * exception of <i>batching / multicall</i>. This feature is deliberately left
 * out as it tends to confuse users (judging by posts in the JSON-RPC forum).
 *
 * <p>See the <a href="http://www.jsonrpc.org/specification">JSON-RPC 2.0
 * specification</a> for more information or write to the
 * <a href="https://groups.google.com/forum/#!forum/json-rpc">user group</a> if
 * you have questions.
 *
 * <p><b>Package dependencies:</b> The classes in this package rely on the 
 * {@code org.json.simple} and {@code org.json.simple.parser} packages 
 * (version 1.1.1 and compabile) for JSON encoding and decoding. You can obtain
 * them from the <a href="http://code.google.com/p/json-smart/">JSON-Smart</a> 
 * website.
 *
 * @author Vladimir Dzhuvinov
 */
package com.thetransactioncompany.jsonrpc2;


  
