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
*/

package HTML.Tmpl.Element;
import java.util.Hashtable;
import java.util.NoSuchElementException;

import HTML.Template;
import HTML.Tmpl.Util;

public class Var extends Element
{
	public static final int ESCAPE_NONE  = 0;
	public static final int ESCAPE_URL   = 1;
	public static final int ESCAPE_HTML  = 2;
	public static final int ESCAPE_QUOTE = 4;
	
	public Var(String name, int escape, Object default_value)
			throws IllegalArgumentException
	{
		this(name, escape);
		this.default_value = stringify(default_value);
	}

	public Var(String name, int escape) 
			throws IllegalArgumentException
	{
		if(name == null)
			throw new IllegalArgumentException("tmpl_var must have a name");
		this.type = "var";
		this.name = name;
		this.escape = escape;
	}

	public Var(String name, String escape)
			throws IllegalArgumentException
	{
		this(name, escape, null);
	}

	public Var(String name, String escape, Object default_value) 
			throws IllegalArgumentException
	{
		this(name, ESCAPE_NONE, default_value);

		if(escape.equalsIgnoreCase("html"))
			this.escape = ESCAPE_HTML;
		else if(escape.equalsIgnoreCase("url"))
			this.escape = ESCAPE_URL;
		else if(escape.equalsIgnoreCase("quote"))
			this.escape = ESCAPE_QUOTE;
	}

	public Var(String name, boolean escape) 
			throws IllegalArgumentException
	{
		this(name, escape?ESCAPE_HTML:ESCAPE_NONE);
	}

	public String parse(Hashtable params)
	{
		String value = null;

		if(params.containsKey(this.name))
			value = stringify(params.get(this.name));
		else
			value = this.default_value;

		if(value == null)
			return "";

		if(this.escape == ESCAPE_HTML)
			return Util.escapeHTML(value);
		else if(this.escape == ESCAPE_URL)
			return Util.escapeURL(value);
		else if(this.escape == ESCAPE_QUOTE)
			return Util.escapeQuote(value);
		else
			return value;
	}

	public String typeOfParam(String param)
			throws NoSuchElementException
	{
		throw new NoSuchElementException(param);
	}

	private String stringify(Object o)
	{
		if(o == null)
			return null;

		String cname = o.getClass().getName();
		if(cname.endsWith(".String"))
			return (String)o;
		else if(cname.endsWith(".Integer"))
			return ((Integer)o).toString();
		else if(cname.endsWith(".Boolean"))
			return ((Boolean)o).toString();
		else if(cname.endsWith(".Date"))
			return ((java.util.Date)o).toString();
		else if(cname.endsWith(".Vector"))
			throw new ClassCastException("Attempt to set <tmpl_var> with a non-scalar. Var name=" + this.name);
        else if(cname.endsWith(".Template"))
            return ((Template)o).output();
		else
			throw new ClassCastException("Unknown object type: " + cname);
	}

	// Private data starts here
	private int escape=ESCAPE_NONE;
	private String default_value=null;

}
