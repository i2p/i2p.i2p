/******************************************************************
*
*	CyberUPnP for Java
*
*	Copyright (C) Satoshi Konno 2002
*
*	File: ArgumentList.java
*
*	Revision:
*
*	12/05/02
*		- first revision.
*
******************************************************************/

package org.cybergarage.upnp;

import java.util.*;

public class ArgumentList extends Vector 
{
	////////////////////////////////////////////////
	//	Constants
	////////////////////////////////////////////////
	
	private static final long serialVersionUID = -5412792105767389170L;
	public final static String ELEM_NAME = "argumentList";

	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////
	
	public ArgumentList() 
	{
	}
	
	////////////////////////////////////////////////
	//	Methods
	////////////////////////////////////////////////
	
	public Argument getArgument(int n)
	{
		return (Argument)get(n);
	}

	public Argument getArgument(String name)
	{
		int nArgs = size();
		for (int n=0; n<nArgs; n++) {
			Argument arg = getArgument(n);
			String argName = arg.getName();
			if (argName == null)
				continue;
			if (argName.equals(name) == true)
				return arg;
		}
		return null;
	}

	////////////////////////////////////////////////
	//	Methods
	////////////////////////////////////////////////
	
	public void set(ArgumentList inArgList)
	{
		int nInArgs = inArgList.size();
		for (int n=0; n<nInArgs; n++) {
			Argument inArg = inArgList.getArgument(n);
			String inArgName = inArg.getName();
			Argument arg = getArgument(inArgName);
			if (arg == null)
				continue;
			arg.setValue(inArg.getValue());
		}
	}
}

