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
*/

package HTML.Tmpl.Element;
import java.util.Vector;
import java.util.Hashtable;
import java.util.Enumeration;
import java.util.NoSuchElementException;

public class Loop extends Element
{
	private boolean loop_context_vars=false;
	private boolean global_vars=false;

	private Vector control_val = null;
	private Vector data;

	public Loop(String name)
	{
		this.type = "loop";
		this.name = name;
		this.data = new Vector();
	}

	public Loop(String name, boolean loop_context_vars)
	{
		this(name);
		this.loop_context_vars=loop_context_vars;
	}

	public Loop(String name, boolean loop_context_vars, boolean global_vars)
	{
		this(name);
		this.loop_context_vars=loop_context_vars;
		this.global_vars=global_vars;
	}

	public void add(String text)
	{
		data.addElement(text);
	}

	public void add(Element node)
	{
		data.addElement(node);
	}

	public void setControlValue(Vector control_val)
			throws IllegalArgumentException
	{
		this.control_val = process_var(control_val);
	}

	public String parse(Hashtable p)
	{
		if(!p.containsKey(this.name))
			this.control_val = null;
		else {
			Object o = p.get(this.name);
			if(!o.getClass().getName().endsWith(".Vector") &&
					!o.getClass().getName().endsWith(".List"))
				throw new ClassCastException(
					"Attempt to set <tmpl_loop> with a non-list.  tmpl_loop=" + this.name);
			setControlValue((Vector)p.get(this.name));
		}

		if(control_val == null)
			return "";

		StringBuffer output = new StringBuffer();
		Enumeration iterator = control_val.elements();

		boolean first=true;
		boolean last=false;
		boolean inner=false;
		boolean odd=true;
		int counter=1;

		while(iterator.hasMoreElements()) {
			Hashtable params = (Hashtable)iterator.nextElement();

			if(params==null)
				params = new Hashtable();

			if(global_vars) {
				for(Enumeration e = p.keys(); e.hasMoreElements();) {
					Object key = e.nextElement();
					if(!params.containsKey(key))
						params.put(key, p.get(key));
				}
			}

			if(loop_context_vars) {
				if(!iterator.hasMoreElements())
					last=true;
				inner = !first && !last;

				params.put("__FIRST__", first?"1":"");
				params.put("__LAST__", last?"1":"");
				params.put("__ODD__", odd?"1":"");
				params.put("__INNER__", inner?"1":"");
				params.put("__COUNTER__", "" + (counter++));
			}
				
			Enumeration de = data.elements();
			while(de.hasMoreElements()) {

				Object e = de.nextElement();
				if(e.getClass().getName().indexOf("String")>-1)
					output.append((String)e);
				else
					output.append(((Element)e).parse(params));
			}
			first = false;
			odd = !odd;
		}

		return output.toString();
	}

	public String typeOfParam(String param)
			throws NoSuchElementException
	{
		for(Enumeration e = data.elements(); e.hasMoreElements();)
		{
			Object o = e.nextElement();
			if(o.getClass().getName().endsWith(".String"))
				continue;
			if(((Element)o).Name().equals(param))
				return ((Element)o).Type();
		}
		throw new NoSuchElementException(param);
	}

	private Vector process_var(Vector control_val) 
			throws IllegalArgumentException 
	{
		String control_class = "";

		if(control_val == null)
			return null;
		
		control_class=control_val.getClass().getName();

		if(control_class.indexOf("Vector") > -1) {
			if(control_val.isEmpty())
				return null;
		} else {
			throw new IllegalArgumentException("Unrecognised type");
		}

		return control_val;
	}

}

