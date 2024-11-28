package com.thetransactioncompany.jsonrpc2.server;


import java.util.Hashtable;

import com.thetransactioncompany.jsonrpc2.JSONRPC2Error;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Notification;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Request;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Response;


/**
 * Dispatcher for JSON-RPC 2.0 requests and notifications. This class is
 * tread-safe.
 *
 * <p>Use the {@code register()} methods to add a request or notification
 * handler for an RPC method.
 *
 * <p>Use the {@code process()} methods to have an incoming request or
 * notification processed by the matching handler.
 *
 * <p>The {@code reportProcTime()} method enables reporting of request 
 * processing time (in microseconds) by appending a non-standard "xProcTime" 
 * attribute to the resulting JSON-RPC 2.0 response message.
 *
 * <p>Example:
 *
 * <pre>
 * { 
 *   "result"    : "xyz",
 *   "id"        : 1,
 *   "jsonrpc"   : "2.0",
 *   "xProcTime" : "189 us"
 * }
 * </pre>
 *
 * <p>Note: The dispatch(...) methods were deprecated in version 1.7. Use 
 * process(...) instead.
 *
 * @author Vladimir Dzhuvinov
 */
public class Dispatcher implements RequestHandler, NotificationHandler {
	
	
	/** 
	 * Hashtable of request name / handler pairs. 
	 */
	private final Hashtable<String,RequestHandler> requestHandlers;
	
	
	/**
	 * Hashtable of notification name / handler pairs.
	 */
	private final Hashtable<String,NotificationHandler> notificationHandlers;
	
	
	/**
	 * Controls reporting of request processing time by appending a 
	 * non-standard "xProcTime" attribute to the JSON-RPC 2.0 response.
	 */
	private boolean reportProcTime = false;
	
	
	/**
	 * Creates a new dispatcher with no registered handlers.
	 */
	public Dispatcher() {
	
		requestHandlers = new Hashtable<String,RequestHandler>();
		notificationHandlers = new Hashtable<String,NotificationHandler>();
	}
	
	
	/**
	 * Registers a new JSON-RPC 2.0 request handler.
	 *
	 * @param handler The request handler to register. Must not be 
	 *                {@code null}.
	 *
	 * @throws IllegalArgumentException On attempting to register a handler
	 *                                  that duplicates an existing request
	 *                                  name.
	 */
	public void register(final RequestHandler handler) {
	
		for (String name: handler.handledRequests()) {
		
			if (requestHandlers.containsKey(name))
				throw new IllegalArgumentException("Cannot register a duplicate JSON-RPC 2.0 handler for request " + name);
		
			requestHandlers.put(name, handler);
		}
	}
	
	
	/**
	 * Registers a new JSON-RPC 2.0 notification handler.
	 *
	 * @param handler The notification handler to register. Must not be
	 *                {@code null}.
	 *
	 * @throws IllegalArgumentException On attempting to register a handler
	 *                                  that duplicates an existing
	 *                                  notification name.
	 */
	public void register(final NotificationHandler handler) {
	
		for (String name: handler.handledNotifications()) {
		
			if (notificationHandlers.containsKey(name))
				throw new IllegalArgumentException("Cannot register a duplicate JSON-RPC 2.0 handler for notification " + name);
		
			notificationHandlers.put(name, handler);
		}
	}
	
	
	@Override
	public String[] handledRequests() {

		java.util.Set<String> var = requestHandlers.keySet();
		return var.toArray(new String[var.size()]);
	}
	
	
	@Override
	public String[] handledNotifications() {

		java.util.Set<String> var = notificationHandlers.keySet();
		return var.toArray(new String[var.size()]);
	}
	
	
	/**
	 * Gets the handler for the specified JSON-RPC 2.0 request name.
	 *
	 * @param requestName The request name to lookup.
	 *
	 * @return The corresponding request handler or {@code null} if none 
	 *         was found.
	 */
	public RequestHandler getRequestHandler(final String requestName) {
	
		return requestHandlers.get(requestName);
	}
	
	
	/**
	 * Gets the handler for the specified JSON-RPC 2.0 notification name.
	 *
	 * @param notificationName The notification name to lookup.
	 *
	 * @return The corresponding notification handler or {@code null} if
	 *         none was found.
	 */
	public NotificationHandler getNotificationHandler(final String notificationName) {
	
		return notificationHandlers.get(notificationName);
	}
	
	
	/**
	 * @deprecated
	 */
	@Deprecated
	public JSONRPC2Response dispatch(final JSONRPC2Request request, final MessageContext requestCtx) {
	
		return process(request, requestCtx);
	}
	
	
	@Override
	public JSONRPC2Response process(final JSONRPC2Request request, final MessageContext requestCtx) {
	
		long startNanosec = 0;
		
		// Measure request processing time?
		if (reportProcTime)
			startNanosec = System.nanoTime();
		
	
		final String method = request.getMethod();
		
		RequestHandler handler = getRequestHandler(method);
		
		if (handler == null) {
		
			// We didn't find a handler for the requested RPC
		
			Object id = request.getID();
			
			return new JSONRPC2Response(JSONRPC2Error.METHOD_NOT_FOUND, id);
		}
			
		// Process the request
		
		JSONRPC2Response response = handler.process(request, requestCtx);
		
		if (reportProcTime) {
		
			final long procTimeNanosec = System.nanoTime() - startNanosec;
			
			response.appendNonStdAttribute("xProcTime", procTimeNanosec / 1000 + " us");
		}
		
		return response;
	}
	
	
	/**
	 * @deprecated
	 */
	@Deprecated
	public void dispatch(final JSONRPC2Notification notification, final MessageContext notificationCtx) {
	
		process(notification, notificationCtx);
	}
	
	
	@Override
	public void process(final JSONRPC2Notification notification, final MessageContext notificationCtx) {
	
		final String method = notification.getMethod();
		
		NotificationHandler handler = getNotificationHandler(method);
		
		if (handler == null) {
		
			// We didn't find a handler for the requested RPC
			return;
		}
			
		// Process the notification
		
		handler.process(notification, notificationCtx);
	}
	
	
	/**
	 * Controls reporting of request processing time by appending a 
	 * non-standard "xProcTime" attribute to the JSON-RPC 2.0 response.
	 * Reporting is disabled by default.
	 *
	 * @param enable {@code true} to enable proccessing time reporting, 
	 *               {@code false} to disable it.
	 */
	public void reportProcTime(final boolean enable) {
	
		reportProcTime = enable;
	}
	
	
	/**
	 * Returns {@code true} if reporting of request processing time is 
	 * enabled. See the {@link #reportProcTime} description for more
	 * information.
	 *
	 * @return {@code true} if reporting of request processing time is 
	 *         enabled, else {@code false}.
	 */
	public boolean reportsProcTime() {
	
		return reportProcTime;
	}
}
