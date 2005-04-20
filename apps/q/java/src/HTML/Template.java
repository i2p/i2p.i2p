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

package HTML;
import java.util.*;
import java.io.*;

import HTML.Tmpl.Element.*;
import HTML.Tmpl.Parsers.*;
import HTML.Tmpl.Util;
import HTML.Tmpl.Filter;

/**
 * Use HTML Templates with java.
 * <p>
 * The HTML.Template class allows you to use HTML Templates from within
 * your java programs.  It makes it possible to change the look of your
 * servlets without having to recompile them.  Use HTML.Template to 
 * separate code from presentation in your servlets.
 * <p>
 * <pre>
 *	Hashtable args = new Hashtable();
 *	args.put("filename", "my_template.tmpl");
 *
 *	Template t = new Template(args);
 *
 *	t.setParam("title", "The HTML Template package");
 *	t.printTo(response.getWriter());
 * </pre>
 * <p>
 * HTML.Template is based on the perl module HTML::Template by Sam Tregar
 * <p>
 * Modified by David McNab (david@rebirthing.co.nz) to allow nesting of
 * templates (ie, passing a child Template object as a value argument
 * to a .setParam() invocation on a parent Template object).
 * </p>
 * @author	Philip S Tellis
 * @version	0.1.2
 */
public class Template 
{
	private If __template__ = new If("__template__");
	private Hashtable params = new Hashtable();

	private boolean dirty = true;

	private boolean strict = true;
	private boolean die_on_bad_params = false;
	private boolean global_vars = false;
	private boolean case_sensitive = false;
	private boolean loop_context_vars = false;
	private boolean debug = false;
	private boolean no_includes = false;
	private boolean search_path_on_include = false;
	private int max_includes = 11;
	private String filename = null;
	private String scalarref = null;
	private String [] arrayref = null;
	private String [] path = null;
	private Reader filehandle = null;
	private Filter [] filters = null;

	private Stack elements = new Stack();
	private Parser parser;

	/**
	 * Initialises a new HTML.Template object with the contents of
	 * the given file.
	 *
	 * @param filename			a string containing the name of 
	 *					the file to be used as a 
	 *					template.  This may be an 
	 *					absolute or relative path to a 
	 *					template file.
	 *
	 * @throws FileNotFoundException	If the file specified does not 
	 *					exist.
	 * @throws IllegalStateException	If &lt;tmpl_include&gt; is
	 *					used when no_includes is in
	 *					effect.
	 * @throws IOException			If an input or output Exception 
	 *					occurred while reading the 
	 *					template.
	 *
	 * @deprecated No replacement.  You should use either
	 *				{@link #Template(Object [])} or
	 *				{@link #Template(Hashtable)}
	 */
	public Template(String filename)
			throws FileNotFoundException, 
				IllegalStateException,
				IOException
	{
		this.filename = filename;
		init();
	}

        
	/**
	 * Initialises a new Template object, using the name/value
	 * pairs passed as default values.
	 * <p>
	 * The parameters passed may be any combination of filename, 
	 * scalarref, arrayref, path, case_sensitive, loop_context_vars, 
	 * strict, die_on_bad_params, global_vars, max_includes, 
	 * no_includes, search_path_on_include and debug.
	 * Each with its own value.  Any one of filename, scalarref or
	 * arrayref must be passed.
	 * <p>
	 * Eg:
	 * <pre>
	 *	String [] template_init = {
	 *		"filename",  "my_template.tmpl",
	 *		"case_sensitive", "true",
	 *		"max_includes",   "5"
	 *	};
	 *
	 *      Template t = new Template(template_init);
	 * </pre>
	 * <p>
	 * The above code creates a new Template object, initialising
	 * its input file to my_template.tmpl, turning on case_sensitive
	 * parameter matching, and restricting maximum depth of includes
	 * to five.
	 * <p>
	 * Parameter values that take boolean values may either be a String
	 * containing the words true/false, or the Boolean values Boolean.TRUE
	 * and Boolean.FALSE.  Numeric values may be Strings, or Integers.
	 *
	 * @since 0.0.8
	 *
	 * @param args		an array of name/value pairs to initialise
	 *			this template with.  Valid values for
	 *			each element may be:
	 * @param filename	[Required] a String containing the path to a 
	 *			template file
	 * @param scalarref	[Required] a String containing the entire 
	 *			template as its contents
	 * @param arrayref	[Required] an array of lines that make up
	 *			the template
	 * @param path		[Optional] an array of Strings specifying
	 *			the directories in which to look for the
	 *			template file.  If not specified, the current
	 *			working directory is used.  If specified,
	 *			only the directories in this array are used.
	 *			If you want the current directory searched,
	 *			include "." in the path.
	 *			<p>
	 *			If you have only a single path, it can be a
	 *			plain String instead of a String array.
	 *			<p>
	 *			This is effective only for the template file,
	 *			and not for included files, but see
	 *			search_path_on_include for how to change that.
	 * @param case_sensitive	[Optional] specifies whether parameter 
	 *			matching is case sensitive or not.  A value 
	 *			of "false", "0" or "" is considered false.  
	 *			All other values are true.
	 *			<p>
	 *			Default: false
	 * @param loop_context_vars	[Optional] when set to true four loop
	 * 			context variables are made available inside a 
	 *			loop: <code>__FIRST__, __LAST__, __INNER__, __ODD__, __COUNTER__</code>.
	 *			They can be used with <code>&lt;TMPL_IF&gt;</code>, 
	 *			<code>&lt;TMPL_UNLESS&gt;</code> and <code>&lt;TMPL_ELSE&gt;</code> to 
	 *			control how a loop is output. Example:
	 *			<pre>
	 *	    &lt;TMPL_LOOP NAME="FOO"&gt;
	 *	       &lt;TMPL_IF NAME="__FIRST__"&gt;
	 *	         This only outputs on the first pass.
	 *	       &lt;/TMPL_IF&gt;
	 *
	 *	       &lt;TMPL_IF NAME="__ODD__"&gt;
	 *	         This outputs on the odd passes.
	 *	       &lt;/TMPL_IF&gt;
	 *
	 *	       &lt;TMPL_UNLESS NAME="__ODD__"&gt;
	 *	         This outputs on the even passes.
	 *	       &lt;/TMPL_IF&gt;
	 *
	 *	       &lt;TMPL_IF NAME="__INNER__"&gt;
	 *	         This outputs on passes that are 
	 *		neither first nor last.
	 *	       &lt;/TMPL_IF&gt;
	 *
	 *	       &lt;TMPL_IF NAME="__LAST__"&gt;
	 *	         This only outputs on the last pass.
	 *	       &lt;TMPL_IF&gt;
	 *	    &lt;/TMPL_LOOP&gt;
	 *			</pre>
	 *			<p>
	 *			NOTE: A loop with only a single pass will get 
	 *			both <code>__FIRST__</code> and <code>__LAST__</code>
	 *			set to true, but not <code>__INNER__</code>.
	 *			<p>
	 *			Default: false
	 * @param strict	[Optional] if set to false the module will 
	 *			allow things that look like they might be 
	 *			TMPL_* tags to get by without throwing
	 *			an exception.  Example:
	 *			<pre>
	 *          &lt;TMPL_HUH NAME=ZUH&gt;
	 *			</pre>
	 *			<p>
	 *			Would normally cause an error, but if you 
	 *			create the Template with strict == 0, 
	 *			HTML.Template will ignore it.
	 *			<p>
	 *			Default: true
	 * @param die_on_bad_params		[Optional] if set to true
	 *			the module will complain if you try to set
	 *			tmpl.setParam("param_name", "value") and
	 *			param_name doesn't exist in the template.
	 *			<p>
	 *			This effect doesn't descend into loops. 
	 *			<p>
	 *			Default: false (may change in later versions)
	 * @param global_vars	[Optional] normally variables declared outside
	 *			a loop are not available inside a loop.  This
	 *			option makes TMPL_VARs global throughout
	 *			the template.  It also affects TMPL_IF and TMPL_UNLESS.
	 *			<pre>
	 *	    &lt;p&gt;This is a normal variable: &lt;TMPL_VAR NORMAL&gt;.&lt;/p&gt;
	 *
	 *	    &lt;TMPL_LOOP NAME="FROOT_LOOP&gt;
	 *	       Here it is inside the loop: &lt;TMPL_VAR NORMAL&gt;
	 *	    &lt;/TMPL_LOOP&gt;
	 *			</pre>
	 *			<p>
	 *			Normally this wouldn't work as expected, since
	 *			&lt;TMPL_VAR NORMAL&gt;'s value outside the loop
	 *			isn't available inside the loop.
	 *			<p>
	 *			Default: false (may change in later versions)
	 * @param max_includes	[Optional] specifies the maximum depth that
	 *			includes can reach.  Including files to a 
	 *			depth greater than this value causes an error 
	 *			message to be displayed.  Set to 0 to disable 
	 *			this protection.
	 *			<p>
	 *			Default: 10
	 * @param no_includes	[Optional] If set to true, disallows the
	 *			&lt;TMPL_INCLUDE&gt; tag in the template
	 *			file.  This can be used to make opening 
	 *			untrusted templates slightly less dangerous.
	 *			<p>
	 *			Default: false
	 * @param search_path_on_include	[Optional] if set, then the
	 *			path is searched for included files as well
	 *			as the template file.  See the path parameter
	 *			for more information.
	 *			<p>
	 *			Default: false
	 * @param debug		[Optional] setting this option to true causes
	 *			HTML.Template to print random error messages
	 *			to STDERR.
	 *			
	 * @throws ArrayIndexOutOfBoundsException	If an odd number of
	 *					parameters is passed.
	 * @throws FileNotFoundException	If the file specified does not 
	 *					exist or no filename is passed.
	 * @throws IllegalArgumentException	If an unknown parameter is
	 *					passed.
	 * @throws IllegalStateException	If &lt;tmpl_include&gt; is
	 *					used when no_includes is in
	 *					effect.
	 * @throws IOException			If an input or output Exception 
	 *					occurred while reading the 
	 *					template.
	 */
	public Template(Object [] args)
			throws ArrayIndexOutOfBoundsException,
				FileNotFoundException,
				IllegalArgumentException,
				IllegalStateException,
				IOException
				
	{
		if(args.length%2 != 0)
			throw new ArrayIndexOutOfBoundsException("odd number " +
					"of arguments passed");

		for(int i=0; i<args.length; i+=2) {
			parseParam((String)args[i], args[i+1]);
		}

		init();
	}

	/**
	 * Initialises a new Template object, using the values in the
	 * Hashtable args as defaults.
	 * <p>
	 * The parameters passed are the same as in the Template(Object [])
	 * constructor. Each with its own value.  Any one of filename, 
	 * scalarref or arrayref must be passed.
	 * <p>
	 * Eg:
	 * <pre>
	 *	Hashtable args = new Hashtable();
	 *	args.put("filename", "my_template.tmpl");
	 *	args.put("case_sensitive", "true");
	 *	args.put("loop_context_vars", Boolean.TRUE);
	 *	// args.put("max_includes", "5");
	 *	args.put("max_includes", new Integer(5));
	 *
	 *	Template t = new Template(args);
	 * </pre>
	 * <p>
	 * The above code creates a new Template object, initialising
	 * its input file to my_template.tmpl, turning on case_sensitive
	 * parameter matching, and the loop context variables __FIRST__,
	 * __LAST__, __ODD__ and __INNER__, and restricting maximum depth of 
	 * includes to five.
	 * <p>
	 * Parameter values that take boolean values may either be a String
	 * containing the words true/false, or the Boolean values Boolean.TRUE
	 * and Boolean.FALSE.  Numeric values may be Strings, or Integers.
	 *
	 * @since 0.0.10
	 *
	 * @param args		a Hashtable of name/value pairs to initialise
	 *			this template with.  Valid values are the same
	 *			as in the Template(Object []) constructor.
	 *			
	 * @throws FileNotFoundException	If the file specified does not 
	 *					exist or no filename is passed.
	 * @throws IllegalArgumentException	If an unknown parameter is
	 *					passed.
	 * @throws IllegalStateException	If &lt;tmpl_include&gt; is
	 *					used when no_includes is in
	 *					effect.
	 * @throws IOException			If an input or output Exception 
	 *					occurred while reading the 
	 *					template.
	 *
	 * @see #Template(Object [])
	 */
	public Template(Hashtable args)
			throws FileNotFoundException,
				IllegalArgumentException,
				IllegalStateException,
				IOException
				
	{
		Enumeration e = args.keys();
		while(e.hasMoreElements()) {
			String key = (String)e.nextElement();
			Object value = args.get(key);

			parseParam(key, value);
		}

		init();
	}

	/**
	 * Prints the parsed template to the provided PrintWriter.
	 *
	 * @param out	the PrintWriter that this template will be printed
	 *		to
	 */
	public void printTo(PrintWriter out) 
	{
		out.print(output());
	}

	/**
	 * Returns the parsed template as a String.
	 *
	 * @return	a string containing the parsed template
	 */
	public String output()
	{
		return __template__.parse(params);
	}

	/**
	 * Sets the values of parameters in this template from a Hashtable.
	 *
	 * @param params	a Hashtable containing name/value pairs for
	 *			this template.  Keys in this hashtable must
	 *			be Strings and values may be either Strings
	 *			or Vectors.
	 *			<p>
	 *			Parameter names are currently not case
	 *			sensitive.
	 *			<p>
	 *			Parameter names can contain only letters, 
	 *			digits,	., /, +, - and _ characters.
	 *			<p>
	 *			Parameter names starting and ending with
	 *			a double underscore are not permitted.
	 *			eg: <code>__myparam__</code> is illegal.
	 *
	 * @return		the number of parameters actually set.
	 *			Illegal parameters will not be set, but
	 *			no error/exception will be thrown.
	 */
	public int setParams(Hashtable params) 
	{
		if(params == null || params.isEmpty())
			return 0;
		int count=0;
		for(Enumeration e = params.keys(); e.hasMoreElements();) {
			Object key = e.nextElement();
			if(key.getClass().getName().endsWith(".String")) {
				Object value = params.get(key);
				try {
					setParam((String)key, value);
					count++;
				} catch (Exception pe) {
					// key was not a String or Vector
					// or key was null
					// don't increment count
				}
			}
		}
		if(count>0) {
			dirty=true;
			Util.debug_print("Now dirty: set params");
		}

		return count;
	}

	/**
	 * Sets a single scalar parameter in this template.
	 *
	 * @param name	a String containing the name of this parameter.
	 *		Parameter names are currently not case sensitive.
	 * @param value	a String containing the value of this parameter	
	 *
	 * @return				the value of the parameter set
	 * @throws IllegalArgumentException 	if the parameter name contains
	 *					illegal characters
	 * @throws NullPointerException 	if the parameter name is null
	 *
	 * @see #setParams(Hashtable)
	 */
	public String setParam(String name, String value)
			throws IllegalArgumentException, NullPointerException
	{
		try {
			return (String)setParam(name, (Object)value);
		} catch(ClassCastException iae) {
			return null;
		}
	}

	/**
	 * Sets a single Integer parameter in this template.
	 *
	 * @param name	a String containing the name of this parameter.
	 *		Parameter names are currently not case sensitive.
	 * @param value	an Integer containing the value of this parameter	
	 *
	 * @return				the value of the parameter set
	 * @throws IllegalArgumentException 	if the parameter name contains
	 *					illegal characters
	 * @throws NullPointerException 	if the parameter name is null
	 *
	 * @see #setParams(Hashtable)
	 */
	public Integer setParam(String name, Integer value)
			throws IllegalArgumentException, NullPointerException
	{
		try {
			return (Integer)setParam(name, (Object)value);
		} catch(ClassCastException iae) {
			return null;
		}
	}

	/**
	 * Sets a single int parameter in this template.
	 *
	 * @param name	a String containing the name of this parameter.
	 *		Parameter names are currently not case sensitive.
	 * @param value	an int containing the value of this parameter	
	 *
	 * @return				the value of the parameter set
	 * @throws IllegalArgumentException 	if the parameter name contains
	 *					illegal characters
	 * @throws NullPointerException 	if the parameter name is null
	 *
	 * @see #setParams(Hashtable)
	 */
	public int setParam(String name, int value)
			throws IllegalArgumentException, NullPointerException
	{
		return setParam(name, new Integer(value)).intValue();
	}

	/**
	 * Sets a single boolean parameter in this template.
	 *
	 * @param name	a String containing the name of this parameter.
	 *		Parameter names are currently not case sensitive.
	 * @param value	a boolean containing the value of this parameter	
	 *
	 * @return				the value of the parameter set
	 * @throws IllegalArgumentException 	if the parameter name contains
	 *					illegal characters
	 * @throws NullPointerException 	if the parameter name is null
	 *
	 * @see #setParams(Hashtable)
	 */
	public boolean setParam(String name, boolean value)
			throws IllegalArgumentException, NullPointerException
	{
		return setParam(name, new Boolean(value)).booleanValue();
	}

	/**
	 * Sets a single Boolean parameter in this template.
	 *
	 * @param name	a String containing the name of this parameter.
	 *		Parameter names are currently not case sensitive.
	 * @param value	a Boolean containing the value of this parameter	
	 *
	 * @return				the value of the parameter set
	 * @throws IllegalArgumentException 	if the parameter name contains
	 *					illegal characters
	 * @throws NullPointerException 	if the parameter name is null
	 *
	 * @see #setParams(Hashtable)
	 */
	public Boolean setParam(String name, Boolean value)
			throws IllegalArgumentException, NullPointerException
	{
		try {
			return (Boolean)setParam(name, (Object)value);
		} catch(ClassCastException iae) {
			return null;
		}
	}


	/**
	 * Sets a single parameter in this template to a nested Template
	 *
	 * @param name	a String containing the name of this parameter.
	 *		Parameter names are currently not case sensitive.
	 * @param value	a Template object to be nested in
	 *
	 * @return				the value of the parameter set
	 * @throws IllegalArgumentException 	if the parameter name contains
	 *					illegal characters
	 * @throws NullPointerException 	if the parameter name is null
	 */
	public Template setParam(String name, Template value)
			throws IllegalArgumentException, NullPointerException
	{
		try {
			return (Template)setParam(name, (Object)value);
		} catch(ClassCastException iae) {
			return null;
		}
	}


	/**
	 * Sets a single list parameter in this template.
	 *
	 * @param name	a String containing the name of this parameter.
	 *		Parameter names are not currently case sensitive.
	 * @param value	a Vector containing a list of Hashtables of parameters
	 *
	 * @return				the value of the parameter set
	 * @throws IllegalArgumentException 	if the parameter name contains
	 *					illegal characters
	 * @throws NullPointerException 	if the parameter name is null
	 *
	 * @see #setParams(Hashtable)
	 */
	public Vector setParam(String name, Vector value) 
			throws IllegalArgumentException, NullPointerException
	{
		try {
			return (Vector)setParam(name, (Object)value);
		} catch(ClassCastException iae) {
			return null;
		}
	}

	/**
	 * Returns a parameter from this template identified by the given name.
	 *
	 * @param name	a String containing the name of the parameter to be 
	 *		returned.  Parameter names are not currently case
	 *		sensitive.
	 *
	 * @return	the value of the requested parameter.  If the parameter
	 *		is a scalar, the return value is a String, if the 
	 *		parameter is a list, the return value is a Vector.
	 *
	 * @throws NoSuchElementException	if the parameter does not exist
	 *					in the template
	 * @throws NullPointerException		if the parameter name is null
	 */
	public Object getParam(String name) 
			throws NoSuchElementException, NullPointerException
	{
		if(name == null)
			throw new NullPointerException("name cannot be null");
		if(!params.containsKey(name))
			throw new NoSuchElementException(name + 
				" is not a parameter in this template");

		if(case_sensitive)
			return params.get(name);
		else
			return params.get(name.toLowerCase());
	}


	private void parseParam(String key, Object value)
			throws IllegalStateException
	{
		if(key.equals("case_sensitive"))
		{
			this.case_sensitive=boolify(value);
			Util.debug_print("case_sensitive: "+value);
		}
		else if(key.equals("strict"))
		{
			this.strict=boolify(value);
			Util.debug_print("strict: "+value);
		}
		else if(key.equals("global_vars"))
		{
			this.global_vars=boolify(value);
			Util.debug_print("global_vars: "+value);
		}
		else if(key.equals("die_on_bad_params"))
		{
			this.die_on_bad_params=boolify(value);
			Util.debug_print("die_obp: "+value);
		}
		else if(key.equals("max_includes"))
		{
			this.max_includes=intify(value)+1;
			Util.debug_print("max_includes: "+value);
		}
		else if(key.equals("no_includes"))
		{
			this.no_includes=boolify(value);
			Util.debug_print("no_includes: "+value);
		}
		else if(key.equals("search_path_on_include"))
		{
			this.search_path_on_include=boolify(value);
			Util.debug_print("path_includes: "+value);
		}
		else if(key.equals("loop_context_vars"))
		{
			this.loop_context_vars=boolify(value);
			Util.debug_print("loop_c_v: "+value);
		}
		else if(key.equals("debug"))
		{
			this.debug=boolify(value);
			Util.debug=this.debug;
			Util.debug_print("debug: "+value);
		}
		else if(key.equals("filename"))
		{
			this.filename = (String)value;
			Util.debug_print("filename: "+value);
		}
		else if(key.equals("scalarref"))
		{
			this.scalarref = (String)value;
			Util.debug_print("scalarref");
		}
		else if(key.equals("arrayref"))
		{
			this.arrayref = (String [])value;
			Util.debug_print("arrayref");
		}
		else if(key.equals("path"))
		{
			if(value.getClass().getName().startsWith("["))
				this.path = (String [])value;
			else {
				this.path = new String[1];
				this.path[0] = (String)value;
			}
			Util.debug_print("path");
			for(int j=0; j<this.path.length; j++)
				Util.debug_print(this.path[j]);
		}
		else if(key.equals("filter"))
		{
			if(value.getClass().getName().startsWith("["))
				this.filters = (Filter [])value;
			else {
				this.filters = new Filter[1];
				this.filters[0] = (Filter)value;
			}
			Util.debug_print("filters set: " + filters.length);
		}
		else if(key.equals("filehandle"))
		{
			this.filehandle = (Reader)value;
			Util.debug_print("filehandle");
		}
		else
		{
			throw new IllegalArgumentException(
					(String)key);
		}

	}

	private void init()
			throws FileNotFoundException, 
				IllegalStateException,
				IOException
	{
		if(this.filename == null && 
				this.scalarref == null &&
				this.arrayref == null &&
				this.filehandle == null)
			throw new FileNotFoundException("template filename required");
		
		Util.debug = this.debug;

		params.put("__template__", "true");

		String [] parser_params = {
			"case_sensitive", 	stringify(case_sensitive),
			"strict", 		stringify(strict),
			"loop_context_vars", 	stringify(loop_context_vars),
			"global_vars", 		stringify(global_vars)
		};

		parser = new Parser(parser_params);

		if(this.filename != null)
			read_file(filename);
		else if(this.arrayref != null)
			read_line_array(this.arrayref);
		else if(this.scalarref != null)
			read_line(this.scalarref);
		else if(this.filehandle != null)
			read_fh(this.filehandle);

		if(!elements.empty())
			System.err.println("stack not empty");
	}


	private Element parseLine(String line, Element e)
			throws FileNotFoundException,
				IllegalStateException,
				IOException, 
				EmptyStackException
	{
		Vector parts;

		parts = parser.parseLine(line);
		Util.debug_print("Items: " + parts.size());

		for(Enumeration pt = parts.elements(); pt.hasMoreElements();) 
		{
			Object o = pt.nextElement();
			
			if(o.getClass().getName().endsWith(".String"))
			{
				if(((String)o).equals(""))
					continue;

				e.add((String)o);
				Util.debug_print("added: " +(String)o);
				continue;
			}
				
			// if we come here, then it is an element

			Properties p = (Properties)o;
			String type=p.getProperty("type");
			Util.debug_print("adding element: " + type);

			if(type.equals("include")) 
			{
				if(no_includes)
					throw new IllegalStateException(
						"<tmpl_include> not " +
						"allowed when " +
						"no_includes in effect"
					);
				if(max_includes == 0) {
					throw new IndexOutOfBoundsException(
						"include too deep");
				} else {
					// come here if positive 
					// or negative
					elements.push(e);
					read_file(p.getProperty("name"));
				}
			}
			else if(type.equals("var"))
			{
				String name = p.getProperty("name");
				String escape = p.getProperty("escape");
				String def = p.getProperty("default");
				Util.debug_print("name: " + name);
				Util.debug_print("escape: " + escape);
				Util.debug_print("default: " + def);
				e.add(new Var(name, escape, def));
			}
			else if(type.equals("else")) 
			{
				Util.debug_print("adding branch");
				((Conditional)e).addBranch();
			}
			else if(p.getProperty("close").equals("true")) 
			{
				Util.debug_print("closing tag");
				if(!type.equals(e.Type()))
					throw new EmptyStackException();

				e = (Element)elements.pop();
			} 
			else
			{
				Element t = parser.getElement(p);
				e.add(t);
				elements.push(e);
				e=t;
			}
		}
		return e;
	}

	private void read_file(String filename)
			throws FileNotFoundException, 
				IllegalStateException,
				IOException, 
				EmptyStackException
	{
		BufferedReader br=openFile(filename);

		String line;

		Element e = null;
		if(elements.empty())
			e = __template__;
		else
			e = (Element)elements.pop();

		max_includes--;
		while((line=br.readLine()) != null) {
			Util.debug_print("Line: " + line);
			e = parseLine(line+"\n", e);
		}
		max_includes++;

		br.close();
		br=null;

	}
	
	private void read_line_array(String [] lines)
			throws FileNotFoundException, 
				IllegalStateException,
				IOException, 
				EmptyStackException
	{

		Element e = __template__;

		max_includes--;
		for(int i=0; i<lines.length; i++) {
			Util.debug_print(lines[i]);
			e = parseLine(lines[i], e);
		}
		max_includes++;
	}

	private void read_line(String lines)
			throws FileNotFoundException, 
				IllegalStateException,
				IOException, 
				EmptyStackException
	{

		Element e = __template__;

		max_includes--;
		StringTokenizer st = new StringTokenizer(lines, "\n");
		while(st.hasMoreTokens()) {
			String line = st.nextToken();
			Util.debug_print(line);
			e = parseLine(line+"\n", e);
		}
		max_includes++;
	}

	private void read_fh(Reader handle)
			throws FileNotFoundException, 
				IllegalStateException,
				IOException, 
				EmptyStackException
	{
		BufferedReader br=new BufferedReader(handle);

		String line;

		Element e = null;
		if(elements.empty())
			e = __template__;
		else
			e = (Element)elements.pop();

		max_includes--;
		while((line=br.readLine()) != null) {
			Util.debug_print("Line: " + line);
			e = parseLine(line+"\n", e);
		}
		max_includes++;

		br.close();
		br=null;

	}
	
	private Object setParam(String name, Object value)
			throws ClassCastException,
				NullPointerException, 
				IllegalArgumentException
	{
		if(name==null)
			throw new NullPointerException(
					"parameter name cannot be null");


		if(!Util.isNameChar(name)) {
			throw new IllegalArgumentException("parameter name " +
				"may only contain letters, digits, ., /, +, " +
				"-, _");
		}

		if(name.startsWith("__") && name.endsWith("__"))
			throw new IllegalArgumentException("parameter name " +
				"may not start and end with a double " +
				"underscore");

		if(die_on_bad_params && !__template__.contains(name))
			throw new IllegalArgumentException(name + 
				"is not a valid template entity");

		if(value==null)
			value="";

		String type = value.getClass().getName();
		if(type.indexOf(".") > 0)
			type = type.substring(type.lastIndexOf(".")+1);

		String valid_types = ",String,Vector,Boolean,Integer,Template";

		if(valid_types.indexOf(type) < 0)
			throw new ClassCastException(
					"value is neither scalar nor list nor Template");

		name=case_sensitive?name:name.toLowerCase();

		if(!case_sensitive && type.equals("Vector")) {
			value = lowerCaseAll((Vector)value);
		}
			
		Util.debug_print("setting: " + name);
		params.put(name, value);

		dirty=true;
		return value;
	}

	private static Vector lowerCaseAll(Vector v)
	{
		Vector v2 = new Vector();
		for(Enumeration e = v.elements(); e.hasMoreElements(); ) {
			Hashtable h = (Hashtable)e.nextElement();
			if(h == null) {
				v2.addElement(h);
				continue;
			}
			Hashtable h2 = new Hashtable();
			for(Enumeration e2 = h.keys(); e2.hasMoreElements(); ) {
				String key = (String)e2.nextElement();
				Object value = h.get(key);
				String value_type = value.getClass().getName();
				Util.debug_print("to lower case: " + key + "(" + value_type + ")");
				if(value_type.endsWith(".Vector"))
					value = lowerCaseAll((Vector)value);
				h2.put(key.toLowerCase(), value);
			}
			v2.addElement(h2);
		}
		return v2;
	}

	private static boolean boolify(Object o)
	{
		String s;
		if(o.getClass().getName().endsWith(".Boolean"))
			return ((Boolean)o).booleanValue();
		else if(o.getClass().getName().endsWith(".String"))
			s = (String)o;
		else
			s = o.toString();

		if(s.equals("0") || s.equals("") || s.equals("false"))
			return false;
		return true;
	}

	private static int intify(Object o)
	{
		String s;
		if(o.getClass().getName().endsWith(".Integer"))
			return ((Integer)o).intValue();
		else if(o.getClass().getName().endsWith(".String"))
			s = (String)o;
		else
			s = o.toString();

		try {
			return Integer.parseInt(s);
		} catch(NumberFormatException nfe) {
			return 0;
		}
	}

	private static String stringify(boolean b)
	{
		if(b)
			return "1";
		else
			return "";
	}
	
	private BufferedReader openFile(String filename)
			throws FileNotFoundException
	{
		boolean add_path=true;

		if(!elements.empty() && !search_path_on_include)
			add_path=false;

		if(filename.startsWith("/"))
			add_path=false;

		if(this.path == null)
			add_path=false;

		Util.debug_print("open " + filename);
		if(!add_path)
			return new BufferedReader(new FileReader(filename));

		BufferedReader br=null;
		
		for(int i=0; i<this.path.length; i++) {
			try {
				Util.debug_print("trying " + this.path[i] +
						"/" + filename);
				br = new BufferedReader(
					new FileReader(
						this.path[i] + "/" + filename
					)
				);
				break;
			} catch (FileNotFoundException fnfe) {
			}
		}

		if(br == null)
			throw new FileNotFoundException(filename);

		return br;
	}
}
