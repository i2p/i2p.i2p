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


package HTML.Tmpl.Parsers;
import java.util.*;
import HTML.Tmpl.Element.*;
import HTML.Tmpl.Util;

public class Parser
{
	private boolean case_sensitive=false;
	private boolean strict=true;
	private boolean loop_context_vars=false;
	private boolean global_vars=false;

	public Parser()
	{
	}

	public Parser(String [] args)
			throws ArrayIndexOutOfBoundsException,
				IllegalArgumentException
	{
		if(args.length%2 != 0)
			throw new ArrayIndexOutOfBoundsException("odd number of arguments passed");

		for(int i=0; i<args.length; i+=2) {
			if(args[i].equals("case_sensitive")) {
				String cs = args[i+1];
				if(cs.equals("") || cs.equals("0"))
					this.case_sensitive=false;
				else
					this.case_sensitive=true;
			} else if(args[i].equals("strict")) {
				String s = args[i+1];
				if(s.equals("") || s.equals("0"))
					this.strict=false;
				else
					this.strict=true;
			} else if(args[i].equals("loop_context_vars")) {
				String s = args[i+1];
				if(s.equals("") || s.equals("0"))
					this.loop_context_vars=false;
				else
					this.loop_context_vars=true;

			} else if(args[i].equals("global_vars")) {
				String s = args[i+1];
				if(s.equals("") || s.equals("0"))
					this.global_vars=false;
				else
					this.global_vars=true;
			} else {
				throw new IllegalArgumentException(args[i]);
			}
		}
	}

	public Element getElement(Properties p)
			throws NoSuchElementException
	{
		String type = p.getProperty("type");

		if(type.equals("if"))
			return new If(p.getProperty("name"));
		else if(type.equals("unless"))
			return new Unless(p.getProperty("name"));
		else if(type.equals("loop"))
			return new Loop(p.getProperty("name"), 
						loop_context_vars, global_vars);
		else
			throw new NoSuchElementException(type);
	}

	public Vector parseLine(String line)
			throws IllegalArgumentException
	{
		int pos=0, endpos;
		Vector parts = new Vector();

		char [] c = line.toCharArray();
		int i=0;

		StringBuffer temp = new StringBuffer();

		for(i=0; i<c.length; i++) {
			if(c[i] != '<') {
				temp.append(c[i]);
			} else {
				// found a tag
				Util.debug_print("line so far: " + temp);
				StringBuffer tag = new StringBuffer();
				for(; i<c.length && c[i] != '>';  i++) {
					tag.append(c[i]);
				}
				// > is not allowed inside a template tag
				// so we can be sure that if this is a 
				// template tag, it ends with a >

				// add the closing > as well
				if(i<c.length)
					tag.append(c[i]);

				// if this contains more < inside it,
				// then it could possibly be a template
				// tag inside a html tag
				// so remove external tag parts

				while(tag.toString().substring(1).indexOf("<") 
						> -1)
				{
					do {
						temp.append(tag.charAt(0));
						tag=new StringBuffer(
							tag.toString().substring(1));
					} while(tag.charAt(0) != '<');
				}

				Util.debug_print("tag: " + tag);

				String test_tag = tag.toString().toLowerCase();
				// if it doesn't contain tmpl_ it is not
				// a template tag
				if(test_tag.indexOf("tmpl_") < 0) {
					temp.append(tag);
					continue;
				}

				// may be a template tag
				// check if it starts with tmpl_
				
				test_tag = cleanTag(test_tag);

				Util.debug_print("clean: " + test_tag);

				// check if it is a closing tag
				if(test_tag.startsWith("/"))
					test_tag = test_tag.substring(1);

				// if it still doesn't start with tmpl_
				// then it is not a template tag
				if(!test_tag.startsWith("tmpl_")) {
					temp.append(tag);
					continue;
				}

				// now it must be a template tag
				String tag_type=getTagType(test_tag);

				if(tag_type == null) {
					if(strict)
						throw new 
						IllegalArgumentException(
							tag.toString());
					else
						temp.append(tag);
				}

				Util.debug_print("type: " + tag_type);

				// if this was an invalid key and we've
				// reached so far, then next iteration
				if(tag_type == null)
					continue;
				
				// now, push the previous stuff
				// into the Vector
				if(temp.length()>0) {
					parts.addElement(temp.toString());
					temp = new StringBuffer();
				}

				// it is a valid template tag
				// get its properties

				Util.debug_print("Checking: " + tag);
				Properties tag_props = 
					getTagProps(tag.toString());

				if(tag_props.containsKey("name"))
					Util.debug_print("name: " + 
						tag_props.getProperty("name"));
				else
					Util.debug_print("no name");

				parts.addElement(tag_props);
			}
		}

		if(temp.length()>0)
			parts.addElement(temp.toString());

		return parts;
	}

	private String cleanTag(String tag)
			throws IllegalArgumentException
	{
		String test_tag = new String(tag);
		// first remove < and >
		if(test_tag.startsWith("<"))
			test_tag = test_tag.substring(1);
		if(test_tag.endsWith(">"))
			test_tag = test_tag.substring(0, test_tag.length()-1);
		else
			throw new IllegalArgumentException("Tags must start " +
					"and end on the same line");

		// remove any leading !-- and trailing
		// -- in case of comment style tags
		if(test_tag.startsWith("!--")) {
			test_tag=test_tag.substring(3);
		}
		if(test_tag.endsWith("--")) {
			test_tag=test_tag.substring(0, test_tag.length()-2);
		}
		// then leading and trailing spaces
		test_tag = test_tag.trim();

		return test_tag;
	}

	private String getTagType(String tag)
	{
		int sp = tag.indexOf(" ");
		String tag_type="";
		if(sp < 0) {
			tag_type = tag.toLowerCase();
		} else {
			tag_type = tag.substring(0, sp).toLowerCase();
		}
		if(tag_type.startsWith("tmpl_"))
			tag_type=tag_type.substring(5);

		Util.debug_print("tag_type: " + tag_type);

		if(tag_type.equals("var") ||
				tag_type.equals("if") ||
				tag_type.equals("unless") ||
				tag_type.equals("loop") ||
				tag_type.equals("include") ||
				tag_type.equals("else")) {
			return tag_type;
		} else {
			return null;
		}
	}

	private Properties getTagProps(String tag)
			throws IllegalArgumentException,
				NullPointerException
	{
		Properties p = new Properties();

		tag = cleanTag(tag);

		Util.debug_print("clean: " + tag);
		
		if(tag.startsWith("/")) {
			p.put("close", "true");
			tag=tag.substring(1);
		} else {
			p.put("close", "");
		}

		Util.debug_print("close: " + p.getProperty("close"));
		
		p.put("type", getTagType(tag));

		Util.debug_print("type: " + p.getProperty("type"));

		if(p.getProperty("type").equals("else") ||
				p.getProperty("close").equals("true"))
			return p;

		if(p.getProperty("type").equals("var"))
			p.put("escape", "");

		int sp = tag.indexOf(" ");
		// if we've got so far, this must succeed

		tag = tag.substring(sp).trim();
		Util.debug_print("checking params: " + tag);

		// now, we should have either name=value pairs
		// or name space escape in case of old style vars

		if(tag.indexOf("=") < 0) {
			// no = means old style
			// first will be var name
			// second if any will be escape

			sp = tag.toLowerCase().indexOf(" escape");
			if(sp < 0) {
				// no escape
				p.put("name", tag);
				p.put("escape", "0");
			} else {
				tag = tag.substring(0, sp);
				p.put("name", tag);
				p.put("escape", "html");
			}
		} else {
			// = means name=value pairs.
			// use a StringTokenizer
			StringTokenizer st = new StringTokenizer(tag, " =");
			while(st.hasMoreTokens()) {
				String key, value;
				key = st.nextToken().toLowerCase();
				if(st.hasMoreTokens())
					value = st.nextToken();
				else if(key.equals("escape"))
					value = "html";
				else
					throw new NullPointerException(
						"parameter " + key + " has no value");

				if(value.startsWith("\"") && 
						value.endsWith("\""))
					value = value.substring(1,
							value.length()-1);
				else if(value.startsWith("'") && 
						value.endsWith("'"))
					value = value.substring(1,
							value.length()-1);

				if(value.length()==0)
					throw new NullPointerException(
						"parameter " + key + " has no value");

				if(key.equals("escape"))
					value=value.toLowerCase();

				p.put(key, value);
			}
		}

		String name = p.getProperty("name");
		// if not case sensitive, and not special variable, flatten case
		// never flatten case for includes
		if(!case_sensitive && !p.getProperty("type").equals("include")
			&& !( name.startsWith("__") && name.endsWith("__") ))
		{
			p.put("name", name.toLowerCase());
		}

		if(!Util.isNameChar(name))
			throw new IllegalArgumentException(
				"parameter name may only contain " +
				"letters, digits, ., /, +, -, _");
		// __var__ is allowed in the template, but not in the
		// code.  this is so that people can reference __FIRST__,
		// etc

		return p;
	}
}
