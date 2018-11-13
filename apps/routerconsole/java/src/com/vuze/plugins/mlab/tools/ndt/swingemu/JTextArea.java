/*
 * Created on May 20, 2010
 * Created by Paul Gardner
 * 
 * Copyright 2010 Vuze, Inc.  All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details ( see the LICENSE file ).
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */



package com.vuze.plugins.mlab.tools.ndt.swingemu;

public class 
JTextArea 
	extends Component
{
	private String text = "";
	
	public 
	JTextArea(
		String		str,
		int			a,
		int			b )
	{
		text	= str;
	}
	
	public void
	append(
		String		str )
	{
		Tcpbw100UIWrapperListener listener = Tcpbw100UIWrapper.current_adapter;
		
		if ( listener != null ){
			
			listener.reportDetail( str );
			
		}else{
			
			System.out.println( "text: " + str );
		}
		
		text += str;
	}
	
	public String
	getText()
	{
		return( text );
	}
	
	public void
	selectAll()
	{
	}
}
