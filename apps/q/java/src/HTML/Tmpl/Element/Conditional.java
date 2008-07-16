/*
*      HTML.Template:  A module for using HTML Templates with java
*
*      Copyright (c) 2002 Philip S Tellis (philip.tellis@iname.com)
*
*      This module is free software; you can redistribute it
*      and/or modify it under the terms of either:
*
*      a) the GNU General Public License as published by the Free
*      Software Foundation; either version 1, or (at your option)
*      any later version, or
*
*      b) the "Artistic License" which comes with this module.
*
*      This program is distributed in the hope that it will be
*      useful, but WITHOUT ANY WARRANTY; without even the implied
*      warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
*      PURPOSE.  See either the GNU General Public License or the
*      Artistic License for more details.
*
*      You should have received a copy of the Artistic License
*      with this module, in the file ARTISTIC.  If not, I'll be
*      glad to provide one.
*
*      You should have received a copy of the GNU General Public
*      License along with this program; if not, write to the Free
*      Software Foundation, Inc., 59 Temple Place, Suite 330,
*      Boston, MA 02111-1307 USA
*
* Modified by David McNab (david@rebirthing.co.nz) to allow nesting of
* templates (ie, passing a child Template object as a value argument
* to a .setParam() invocation on a parent Template object).
*
*/

package HTML.Tmpl.Element;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.NoSuchElementException;
import java.util.Vector;

import HTML.Template;

public class Conditional extends Element
{
	private boolean control_val = false;
	private Vector [] data;

	public Conditional(String type, String name) 
			throws IllegalArgumentException
	{
		if(type.equalsIgnoreCase("if"))
			this.type="if";
		else if(type.equalsIgnoreCase("unless"))
			this.type="unless";
		else
			throw new IllegalArgumentException(
				"Unrecognised type: " + type);

		this.name = name;
		this.data = new Vector[2];
		this.data[0] = new Vector();
	}

	public void addBranch() throws IndexOutOfBoundsException
	{
		if(data[1] != null)
			throw new IndexOutOfBoundsException("Already have two branches");

		if(data[0] == null)
			data[0] = new Vector();
		else if(data[1] == null)
			data[1] = new Vector();
	}

	public void add(String text)
	{
		if(data[1] != null)
			data[1].addElement(text);
		else
			data[0].addElement(text);
	}

	public void add(Element node)
	{
		if(data[1] != null)
			data[1].addElement(node);
		else
			data[0].addElement(node);
	}

	public void setControlValue(Object control_val)
			throws IllegalArgumentException
	{
		this.control_val = process_var(control_val);
	}

	public String parse(Hashtable params)
	{
		if(!params.containsKey(this.name))
			this.control_val = false;
		else	
			setControlValue(params.get(this.name));

		StringBuffer output = new StringBuffer();

		Enumeration de;
		if(type.equals("if") && control_val ||
			type.equals("unless") && !control_val)
			de = data[0].elements();
		else if(data[1] != null)
			de = data[1].elements();
		else
			return "";

		while(de.hasMoreElements()) {
			Object e = de.nextElement();
            String eType = e.getClass().getName();
			if(eType.endsWith(".String"))
				output.append((String)e);
            else if (eType.endsWith(".Template"))
                output.append(((Template)e).output());
			else
				output.append(((Element)e).parse(params));
		}

		return output.toString();
	}

	public String typeOfParam(String param)
			throws NoSuchElementException
	{
		for(int i=0; i<data.length; i++)
		{
			if(data[i] == null)
				continue;
			for(Enumeration e = data[i].elements(); 
				e.hasMoreElements();)
			{
				Object o = e.nextElement();
				if(o.getClass().getName().endsWith(".String"))
					continue;
				if(((Element)o).Name().equals(param))
					return ((Element)o).Type();
			}
		}
		throw new NoSuchElementException(param);
	}

	private boolean process_var(Object control_val) 
			throws IllegalArgumentException 
	{
		String control_class = "";

		if(control_val == null)
			return false;
		
		control_class=control_val.getClass().getName();
		if(control_class.indexOf(".") > 0)
			control_class = control_class.substring(
					control_class.lastIndexOf(".")+1);

		if(control_class.equals("String")) {
			return !(((String)control_val).equals("") || 
				((String)control_val).equals("0"));
		} else if(control_class.equals("Vector")) {
			return !((Vector)control_val).isEmpty();
		} else if(control_class.equals("Boolean")) {
			return ((Boolean)control_val).booleanValue();
		} else if(control_class.equals("Integer")) {
			return (((Integer)control_val).intValue() != 0);
		} else {
			throw new IllegalArgumentException("Unrecognised type");
		}
	}
}

