package com.thetransactioncompany.jsonrpc2.server;


import com.thetransactioncompany.jsonrpc2.JSONRPC2Notification;


/**
 * Interface for handling JSON-RPC 2.0 notifications.
 *
 * @author Vladimir Dzhuvinov
 */
public interface NotificationHandler {

	
	/**
	 * Gets the names of the handled JSON-RPC 2.0 notification methods.
	 *
	 * @return The names of the handled JSON-RPC 2.0 notification methods.
	 */
	public String[] handledNotifications();
	
	
	/**
	 * Processes a JSON-RPC 2.0 notification.
	 *
	 * <p>Note that JSON-RPC 2.0 notifications don't produce a response!
	 *
	 * @param notification    A valid JSON-RPC 2.0 notification instance.
	 *                        Must not be {@code null}.
	 * @param notificationCtx Context information about the notification
	 *                        message, may be {@code null} if undefined.
	 */
	public void process(final JSONRPC2Notification notification, final MessageContext notificationCtx);

}
