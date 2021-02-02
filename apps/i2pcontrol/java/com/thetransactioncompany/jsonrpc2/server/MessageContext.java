package com.thetransactioncompany.jsonrpc2.server;


import java.net.InetAddress;
import java.net.URLConnection;
import java.security.Principal;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.servlet.http.HttpServletRequest;


/**
 * Context information about JSON-RPC 2.0 request and notification messages.
 * This class is immutable.
 *
 * <ul>
 *     <li>The client's hostname.
 *     <li>The client's IP address.
 *     <li>Whether the request / notification was transmitted securely (e.g. 
 *         via HTTPS).
 *     <li>The client principal(s) (user), if authenticated.
 * </ul>
 *
 * @author Vladimir Dzhuvinov
 */
public class MessageContext {


	/** 
	 * The client hostname, {@code null} if none was specified.
	 */
	private String clientHostName = null;

	
	/** 
	 * The client IP address, {@code null} if none was specified.
	 */
	private String clientInetAddress = null;

	
	/** 
	 * Indicates whether the request was received over a secure channel
	 * (typically HTTPS). 
	 */
	private boolean secure = false;
	
	
	/**
	 * The authenticated client principals, {@code null} if none were 
	 * specified.
	 */
	private Principal[] principals = null;
	
	
	/**
	 * Minimal implementation of the {@link java.security.Principal} 
	 * interface.
	 */
	public class BasicPrincipal implements Principal {
	
		/**
		 * The principal name.
		 */
		private String name;
	
	
		/**
		 * Creates a new principal.
		 *
		 * @param name The principal name, must not be {@code null} or
		 *             empty string.
		 *
		 * @throws IllegalArgumentException On a {@code null} or empty 
		 *                                  principal name.
		 */
		public BasicPrincipal(final String name) {
		
			if (name == null || name.trim().isEmpty())
				throw new IllegalArgumentException("The principal name must be defined");
		
			this.name = name;
		}
	
	
		/**
		 * Checks for equality.
		 *
		 * @param another The object to compare to.
		 */
		public boolean equals(final Object another) {
		
			return another != null &&
			       another instanceof Principal && 
			       ((Principal)another).getName().equals(this.getName());
		}
		
		
		/**
		 * Returns a hash code for this principal.
		 *
		 * @return The hash code.
		 */
		public int hashCode() {
		
			return getName().hashCode();
		}
		
		
		/**
		 * Returns the principal name.
		 *
		 * @return The principal name.
		 */
		public String getName() {
			
			return name;
		}
	}
	
	
	/**
	 * Creates a new JSON-RPC 2.0 request / notification context.
	 *
	 * @param clientHostName    The client hostname, {@code null} if 
	 *                          unknown.
	 * @param clientInetAddress The client IP address, {@code null} if 
	 *                          unknown.
	 * @param secure            Specifies a request received over HTTPS.
	 * @param principalName     Specifies the authenticated client principle
	 *                          name, {@code null} if unknown. The name must
	 *                          not be an empty or blank string.
	 */
	public MessageContext(final String clientHostName, 
	                      final String clientInetAddress, 
			      final boolean secure, 
			      final String principalName) {
	
		this.clientHostName = clientHostName;
		this.clientInetAddress = clientInetAddress;
		this.secure = secure;
		
		if (principalName != null) {
			principals = new Principal[1];
			principals[0] = new BasicPrincipal(principalName);
		}
	}
	
	
	/**
	 * Creates a new JSON-RPC 2.0 request / notification context.
	 *
	 * @param clientHostName    The client hostname, {@code null} if 
	 *                          unknown.
	 * @param clientInetAddress The client IP address, {@code null} if 
	 *                          unknown.
	 * @param secure            Specifies a request received over HTTPS.
	 * @param principalNames    Specifies the authenticated client principle
	 *                          names, {@code null} if unknown. The names
	 *                          must not be an empty or blank string.
	 */
	public MessageContext(final String clientHostName, 
	                      final String clientInetAddress, 
			      final boolean secure, 
			      final String[] principalNames) {
	
		this.clientHostName = clientHostName;
		this.clientInetAddress = clientInetAddress;
		this.secure = secure;
		
		if (principalNames != null) {
			principals = new Principal[principalNames.length];
			
			for (int i=0; i < principals.length; i++)
				principals[0] = new BasicPrincipal(principalNames[i]);
		}
	}
	
	
	/**
	 * Creates a new JSON-RPC 2.0 request / notification context. No 
	 * authenticated client principal is specified.
	 *
	 * @param clientHostName    The client hostname, {@code null} if 
	 *                          unknown.
	 * @param clientInetAddress The client IP address, {@code null} if 
	 *                          unknown.
	 * @param secure            Specifies a request received over HTTPS.
	 */
	public MessageContext(final String clientHostName, 
	                      final String clientInetAddress, 
			      final boolean secure) {
	
		this.clientHostName = clientHostName;
		this.clientInetAddress = clientInetAddress;
		this.secure = secure;
	}
	
	
	/**
	 * Creates a new JSON-RPC 2.0 request / notification context. Indicates 
	 * an insecure transport (plain HTTP) and no authenticated client 
	 * principal.
	 *
	 * @param clientHostName    The client hostname, {@code null} if 
	 *                          unknown.
	 * @param clientInetAddress The client IP address, {@code null} if 
	 *                          unknown.
	 */
	public MessageContext(final String clientHostName, 
	                      final String clientInetAddress) {
	
		this.clientHostName = clientHostName;
		this.clientInetAddress = clientInetAddress;
		this.secure = false;
	}
	
	
	/**
	 * Creates a new JSON-RPC 2.0 request / notification context. Indicates 
	 * an insecure transport (plain HTTP) and no authenticated client 
	 * principal. Not client hostname / IP is specified.
	 */
	public MessageContext() {
	
		this.secure = false;
	}
	
	
	/**
	 * Creates a new JSON-RPC 2.0 request / notification context from the
	 * specified HTTP request.
	 *
	 * @param httpRequest The HTTP request.
	 */
	public MessageContext(final HttpServletRequest httpRequest) {
	
		clientInetAddress = httpRequest.getRemoteAddr();
	
		clientHostName = httpRequest.getRemoteHost();
		
		if (clientHostName != null && clientHostName.equals(clientInetAddress))
			clientHostName = null; // not resolved actually
		
		secure = httpRequest.isSecure();

		X509Certificate[] certs = (X509Certificate[])httpRequest.getAttribute("javax.servlet.request.X509Certificate");

		if (certs != null && certs.length > 0) {	
			
			principals = new Principal[certs.length];
			
			for (int i=0; i < principals.length; i++)
				principals[i] = certs[i].getSubjectX500Principal();
		}
	}


	/**
	 * Creates a new JSON-RPC 2.0 request / notification context from the
	 * specified URL connection. Use this constructor in cases when the 
	 * HTTP server is the origin of the JSON-RPC 2.0 requests / 
	 * notifications. If the IP address of the HTTP server cannot be 
	 * resolved {@link #getClientInetAddress} will return {@code null}.
	 *
	 * @param connection The URL connection, must be established and not
	 *                   {@code null}.
	 */
	public MessageContext(final URLConnection connection) {

		clientHostName = connection.getURL().getHost();

		InetAddress ip = null;

		if (clientHostName != null) {

			try {
				ip = InetAddress.getByName(clientHostName);

			} catch (Exception e) {

				// UnknownHostException, SecurityException 
				// ignore
			}
		}

		if (ip != null)
			clientInetAddress = ip.getHostAddress();


		if (connection instanceof HttpsURLConnection) {

			secure = true;

			HttpsURLConnection httpsConnection = (HttpsURLConnection)connection;

			Principal prn = null;

			try {
				prn = httpsConnection.getPeerPrincipal();

			} catch (Exception e) {

				// SSLPeerUnverifiedException, IllegalStateException 
				// ignore
			}

			if (prn != null) {

				principals = new Principal[1];
				principals[0] = prn;
			}
		}
	}
	
	
	/**
	 * Gets the hostname of the client that sent the request / 
	 * notification.
	 *
	 * @return The client hostname, {@code null} if unknown.
	 */
	public String getClientHostName() {
	
		return clientHostName;
	}
	
	
	/**
	 * Gets the IP address of the client that sent the request /
	 * notification.
	 *
	 * @return The client IP address, {@code null} if unknown.
	 */
	public String getClientInetAddress() {
		
		return clientInetAddress;
	}
	 

	/**
	 * Indicates whether the request / notification was received over a 
	 * secure HTTPS connection.
	 *
	 * @return {@code true} If the request was received over HTTPS, 
	 *         {@code false} if it was received over plain HTTP.
	 */
	public boolean isSecure() {
	
		return secure;
	}
	
	
	/**
	 * Returns the first authenticated client principal, {@code null} if 
	 * none.
	 *
	 * @return The first client principal, {@code null} if none.
	 */
	public Principal getPrincipal() {
	
		if (principals != null)
			return principals[0];
		else
			return null;
	}
	
	
	/**
	 * Returns the authenticated client principals, {@code null} if 
	 * none.
	 *
	 * @return The client principals, {@code null} if none.
	 */
	public Principal[] getPrincipals() {
	
		return principals;
	}
	
	
	/**
	 * Returns the first authenticated client principal name, {@code null} 
	 * if none.
	 *
	 * @return The first client principal name, {@code null} if none.
	 */
	public String getPrincipalName() {
	
		if (principals != null)
			return principals[0].getName();
		else
			return null;
	}
	
	
	/**
	 * Returns the authenticated client principal names, {@code null} 
	 * if none.
	 *
	 * @return The client principal names, {@code null} if none.
	 */
	public String[] getPrincipalNames() {
	
		String[] names = new String[principals.length];
		
		for (int i=0; i < names.length; i++)
			names[i] = principals[i].getName();
		
		return names;
	}


	@Override
	public String toString() {

		String s = "[host=" + clientHostName + " hostIP=" + clientInetAddress + " secure=" + secure;

		if (principals != null) {

			int i = 0;

			for (Principal p: principals)
				s += " principal[" + (i++) + "]=" + p;
		}

		return s + "]";
	}
}
