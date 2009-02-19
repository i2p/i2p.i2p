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

public final class Debug
{
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
		if (enabled == true)
			System.out.println("CyberGarage message : " + s);
	}
	public static final void message(String m1, String m2) {
		if (enabled == true)
			System.out.println("CyberGarage message : ");
			System.out.println(m1);
			System.out.println(m2);
	}
	public static final void warning(String s) {
		System.out.println("CyberGarage warning : " + s);
	}
	public static final void warning(String m, Exception e) {
		System.out.println("CyberGarage warning : " + m + " (" + e.getMessage() + ")");
	}
	public static final void warning(Exception e) {
		warning(e.getMessage());
		e.printStackTrace();
	}
}