/******************************************************************
*
*	CyberXML for Java
*
*	Copyright (C) Satoshi Konno 2002
*
*	File: ParserException.java
*
*	Revision;
*
*	11/27/02
*		- first revision.
*	12/26/03
*		- Changed to a sub class of Exception instead of SAXException.
*
******************************************************************/

package org.cybergarage.xml;

public class ParserException extends Exception 
{
	private static final long serialVersionUID = 7443033796560597360L;

	public ParserException(Exception e)
	{
		super(e);
	}
	
	public ParserException(String s)
	{
		super(s);
	}
}