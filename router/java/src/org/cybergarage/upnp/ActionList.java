/******************************************************************
*
*	CyberUPnP for Java
*
*	Copyright (C) Satoshi Konno 2002
*
*	File: ActionList.java
*
*	Revision:
*
*	12/05/02
*		- first revision.
*
******************************************************************/

package org.cybergarage.upnp;

import java.util.*;

public class ActionList extends Vector 
{
	////////////////////////////////////////////////
	//	Constants
	////////////////////////////////////////////////

	private static final long serialVersionUID = 1965922721316119846L;
	public final static String ELEM_NAME = "actionList";

	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////
	
	public ActionList() 
	{
	}
	
	////////////////////////////////////////////////
	//	Methods
	////////////////////////////////////////////////
	
	public Action getAction(int n)
	{
		return (Action)get(n);
	}
}

