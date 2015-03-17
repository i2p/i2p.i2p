/******************************************************************
*
*	CyberUtil for Java
*
*	Copyright (C) Satoshi Konno 2002
*
*	File: Debug.java
*
*	Revision;
*
*	11/18/02
*		- first revision.
*
******************************************************************/

package org.cybergarage.util;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

public final class Debug
{
	private static Log _log;

	/** I2P this is all static so have the UPnPManager call this */
	public static void initialize(I2PAppContext ctx) {
		_log = ctx.logManager().getLog(Debug.class);
		// org.cybergarage.util.Debug=DEBUG at startup
		enabled = _log.shouldLog(Log.DEBUG);
	}

	public static boolean enabled = false;
	
	public static final void on() {
		enabled = true;
	}
	public static final void off() {
		enabled = false;
	}
	public static boolean isOn() {
		return enabled;
	}
	public static final void message(String s) {
		if (_log != null)
			_log.debug(s);
	}
	public static final void message(String m1, String m2) {
		if (_log != null) {
			_log.debug(m1);
			_log.debug(m2);
		}
	}
	public static final void warning(String s) {
		if (_log != null)
			_log.warn(s);
	}
	public static final void warning(String m, Exception e) {
		if (_log != null)
			_log.warn(m, e);
	}
	public static final void warning(Exception e) {
		if (_log != null)
			_log.warn("", e);
	}
}
