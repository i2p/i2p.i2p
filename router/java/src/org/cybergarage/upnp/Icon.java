/******************************************************************
*
*	CyberUPnP for Java
*
*	Copyright (C) Satoshi Konno 2002
*
*	File: Icon.java
*
*	Revision;
*
*	11/28/02
*		- first revision.
*	
******************************************************************/

package org.cybergarage.upnp;

import org.cybergarage.xml.*;

public class Icon
{
	////////////////////////////////////////////////
	//	Constants
	////////////////////////////////////////////////
	
	public final static String ELEM_NAME = "icon";

	////////////////////////////////////////////////
	//	Member
	////////////////////////////////////////////////

	private Node iconNode;

	public Node getIconNode()
	{
		return iconNode;
	}
	
	////////////////////////////////////////////////
	//	Constructor
	////////////////////////////////////////////////

	public Icon(Node node)
	{
		iconNode = node;
	}

	////////////////////////////////////////////////
	//	isIconNode
	////////////////////////////////////////////////

	public static boolean isIconNode(Node node)
	{
		return Icon.ELEM_NAME.equals(node.getName());
	}

	////////////////////////////////////////////////
	//	mimeType
	////////////////////////////////////////////////

	private final static String MIME_TYPE = "mimeType";
	
	public void setMimeType(String value)
	{
		getIconNode().setNode(MIME_TYPE, value);
	}

	public String getMimeType()
	{
		return getIconNode().getNodeValue(MIME_TYPE);
	}

	////////////////////////////////////////////////
	//	width
	////////////////////////////////////////////////

	private final static String WIDTH = "width";
	
	public void setWidth(String value)
	{
		getIconNode().setNode(WIDTH, value);
	}

	public String getWidth()
	{
		return getIconNode().getNodeValue(WIDTH);
	}

	////////////////////////////////////////////////
	//	height
	////////////////////////////////////////////////

	private final static String HEIGHT = "height";
	
	public void setHeight(String value)
	{
		getIconNode().setNode(HEIGHT, value);
	}

	public String getHeight()
	{
		return getIconNode().getNodeValue(HEIGHT);
	}

	////////////////////////////////////////////////
	//	depth
	////////////////////////////////////////////////

	private final static String DEPTH = "depth";
	
	public void setDepth(String value)
	{
		getIconNode().setNode(DEPTH, value);
	}

	public String getDepth()
	{
		return getIconNode().getNodeValue(DEPTH);
	}

	////////////////////////////////////////////////
	//	URL
	////////////////////////////////////////////////

	private final static String URL = "url";
	
	public void setURL(String value)
	{
		getIconNode().setNode(URL, value);
	}

	public String getURL()
	{
		return getIconNode().getNodeValue(URL);
	}
}
