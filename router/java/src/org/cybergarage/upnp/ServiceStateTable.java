/******************************************************************
*
*	CyberUPnP for Java
*
*	Copyright (C) Satoshi Konno 2002
*
*	File: ServiceStateTable.java
*
*	Revision:
*
*	12/06/02
*		- first revision.
*
******************************************************************/

package org.cybergarage.upnp;

import java.util.*;

public class ServiceStateTable extends Vector 
{
	////////////////////////////////////////////////
	//	Constants
	////////////////////////////////////////////////
	
	private static final long serialVersionUID = 7626909231678469365L;
	public final static String ELEM_NAME = "serviceStateTable";

	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////
	
	public ServiceStateTable() 
	{
	}
	
	////////////////////////////////////////////////
	//	Methods
	////////////////////////////////////////////////
	
	public StateVariable getStateVariable(int n)
	{
		return (StateVariable)get(n);
	}
}

