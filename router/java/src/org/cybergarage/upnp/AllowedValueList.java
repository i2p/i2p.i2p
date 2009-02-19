/******************************************************************
*
*	CyberLink for Java
*
*	Copyright (C) Satoshi Konno 2002-2004
*
*	File: AllowedValueList.java
*
*	Revision:
*
*	03/27/04
*		- first revision.
*	02/28/05
*		- Changed to use AllowedValue instead of String as the member.
*	
******************************************************************/

package org.cybergarage.upnp;

import java.util.*;

public class AllowedValueList extends Vector
{
	////////////////////////////////////////////////
	//	Constants
	////////////////////////////////////////////////
	
	private static final long serialVersionUID = 5740394642751180992L;
	public final static String ELEM_NAME = "allowedValueList";


	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////
	
	public AllowedValueList() 
	{
	}
	
	////////////////////////////////////////////////
	//	Methods
	////////////////////////////////////////////////
	
	public AllowedValue getAllowedValue(int n)
	{
		return (AllowedValue)get(n);
	}

}
