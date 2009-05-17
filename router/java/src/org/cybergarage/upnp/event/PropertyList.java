/******************************************************************
*
*	CyberUPnP for Java
*
*	Copyright (C) Satoshi Konno 2002
*
*	File: PropertyList.java
*
*	Revision;
*
*	09/08/03
*		- first revision.
*
******************************************************************/

package org.cybergarage.upnp.event;

import java.util.*;

public class PropertyList extends Vector 
{
	////////////////////////////////////////////////
	//	Constants
	////////////////////////////////////////////////
	
	private static final long serialVersionUID = 8718064210738306226L;
	public final static String ELEM_NAME = "PropertyList";

	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////
	
	public PropertyList() 
	{
	}
	
	////////////////////////////////////////////////
	//	Methods
	////////////////////////////////////////////////
	
	public Property getProperty(int n)
	{
		return (Property)get(n);
	}
}

