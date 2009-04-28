/******************************************************************
*
*	CyberXML for Java
*
*	Copyright (C) Satoshi Konno 2002-2003
*
*	File: XML.java
*
*	Revision;
*
*	01/05/03
*		- first revision.
*	12/15/03
*		- Terje Bakken
*		- Added escapeXMLChars()
*	
******************************************************************/

package org.cybergarage.xml;

public class XML 
{
	public final static String CONTENT_TYPE = "text/xml; charset=\"utf-8\"";

	////////////////////////////////////////////////
	// escapeXMLChars
	////////////////////////////////////////////////
	
	private final static String escapeXMLChars(String input, boolean quote) 
	{
		StringBuilder out = new StringBuilder();
		if (input == null)
			return null;
		int oldsize=input.length();
		char[] old=new char[oldsize];
		input.getChars(0,oldsize,old,0);
		int selstart = 0;
		String entity=null;			
		for (int i=0;i<oldsize;i++) {			
			switch (old[i]) {
			case '&': entity="&amp;"; break;				
			case '<': entity="&lt;"; break;
			case '>': entity="&gt;"; break;
			case '\'': if (quote) { entity="&apos;"; break; } 
			case '"': if (quote) { entity="&quot;"; break; }
			}
			if (entity != null) {
				out.append(old,selstart,i-selstart);
				out.append(entity);
				selstart=i+1;
				entity=null;											 
			}
		}
		if (selstart == 0)
			return input;
		out.append(old,selstart,oldsize-selstart);
		return out.toString();	
	}

	public final static String escapeXMLChars(String input)
	{
		return escapeXMLChars(input, true);
	}
}

