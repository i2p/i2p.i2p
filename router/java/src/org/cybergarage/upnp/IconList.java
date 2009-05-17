/******************************************************************
*
*	CyberUPnP for Java
*
*	Copyright (C) Satoshi Konno 2002
*
*	File: IconList.java
*
*	Revision;
*
*	12/04/02
*		- first revision.
*
******************************************************************/

package org.cybergarage.upnp;

import java.util.*;

public class IconList extends Vector 
{
	////////////////////////////////////////////////
	//	Constants
	////////////////////////////////////////////////
	
	private static final long serialVersionUID = -1097238335037012991L;
	public final static String ELEM_NAME = "iconList";

	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////
	
	public IconList() 
	{
	}
	
	////////////////////////////////////////////////
	//	Methods
	////////////////////////////////////////////////
	
	public Icon getIcon(int n)
	{
		return (Icon)get(n);
	}
}

