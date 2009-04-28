/******************************************************************
*
*	CyberLink for Java
*
*	Copyright (C) Satoshi Konno 2002-2004
*
*	File: AllowedValueRange.java
*
*	Revision:
*
*	03/27/04
*		- first revision.
*	
******************************************************************/

package org.cybergarage.upnp;

import org.cybergarage.xml.*;

public class AllowedValueRange
{
	////////////////////////////////////////////////
	//	Constants
	////////////////////////////////////////////////
	
	public final static String ELEM_NAME = "allowedValueRange";

	////////////////////////////////////////////////
	//	Member
	////////////////////////////////////////////////

	private Node allowedValueRangeNode;

	public Node getAllowedValueRangeNode()
	{
		return allowedValueRangeNode;
	}
	
	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////

	public AllowedValueRange(Node node)
	{
		allowedValueRangeNode = node;
	}

	////////////////////////////////////////////////
	//	isAllowedValueRangeNode
	////////////////////////////////////////////////

	public static boolean isAllowedValueRangeNode(Node node)
	{
		return ELEM_NAME.equals(node.getName());
	}

	////////////////////////////////////////////////
	//	minimum
	////////////////////////////////////////////////

	private final static String MINIMUM = "minimum";
	
	public void setMinimum(String value)
	{
		getAllowedValueRangeNode().setNode(MINIMUM, value);
	}

	public String getMinimum()
	{
		return getAllowedValueRangeNode().getNodeValue(MINIMUM);
	}

	////////////////////////////////////////////////
	//	maximum
	////////////////////////////////////////////////

	private final static String MAXIMUM = "maximum";
	
	public void setMaximum(String value)
	{
		getAllowedValueRangeNode().setNode(MAXIMUM, value);
	}

	public String getMaximum()
	{
		return getAllowedValueRangeNode().getNodeValue(MAXIMUM);
	}

	////////////////////////////////////////////////
	//	width
	////////////////////////////////////////////////

	private final static String STEP = "step";
	
	public void setStep(String value)
	{
		getAllowedValueRangeNode().setNode(STEP, value);
	}

	public String getStep()
	{
		return getAllowedValueRangeNode().getNodeValue(STEP);
	}
}
