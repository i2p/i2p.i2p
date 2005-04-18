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


package HTML.Tmpl;

/**
 * Pre-parse filters for HTML.Template templates.
 * <p>
 * The HTML.Tmpl.Filter interface allows you to write Filters
 * for your templates.  The filter is called after the template
 * is read and before it is parsed.
 * <p>
 * You can use a filter to make changes in the template file before
 * it is parsed by HTML.Template, so for example, use it to replace
 * constants, or to translate your own tags to HTML.Template tags.
 * <p>
 * A common usage would be to do what you think you're doing when you
 * do <code>&lt;TMPL_INCLUDE file="&lt;TMPL_VAR name="the_file"&gt;"&gt;</code>:
 * <p>
 * myTemplate.tmpl:
 * <pre>
 *	&lt;TMPL_INCLUDE file="&lt;%the_file%&gt;"&gt;
 * </pre>
 * <p>
 * myFilter.java:
 * <pre>
 *	class myFilter implements HTML.Tmpl.Filter
 *	{
 *		private String myFile;
 *		private int type=SCALAR
 *
 *		public myFilter(String myFile) {
 *			this.myFile = myFile;
 *		}
 *
 *		public int format() {
 *			return this.type;
 *		}
 *
 *		public String parse(String t) {
 *			// replace all &lt;%the_file%&gt; with myFile
 *			return t;
 *		}
 *
 *		public String [] parse(String [] t) {
 *			throw new UnsupportedOperationException();
 *		}
 *	}
 * </pre>
 * <p>
 * myClass.java:
 * <pre>
 *	Hashtable params = new Hashtable();
 *	params.put("filename", "myTemplate.tmpl");
 *	params.put("filter", new myFilter("myFile.tmpl"));
 *	Template t = new Template(params);
 * </pre>
 *
 * @author	Philip S Tellis
 * @version	0.0.1
 */
public interface Filter
{
	/**
	 * Tells HTML.Template to call the parse(String) method of this filter.
	 */
	public final static int SCALAR=1;

	/**
	 * Tells HTML.Template to call the parse(String []) method of this 
	 * filter.
	 */
	public final static int ARRAY=2;

	/**
	 * Tells HTML.Template what kind of filter this is.
	 * Should return either SCALAR or ARRAY to indicate which parse method
	 * must be called.
	 *
	 * @return	the values SCALAR or ARRAY indicating which parse method
	 *		is to be called
	 */
	public int format();

	/**
	 * parses the template as a single string, and returns the parsed 
	 * template as a single string.
	 * <p>
	 * Should throw an UnsupportedOperationException if it isn't implemented
	 *
	 * @param t	a string containing the entire template
	 *
	 * @return	a string containing the template after you've parsed it
	 *
	 * @throws UnsupportedOperationException	if this method isn't 
	 *						implemented
	 */
	public String parse(String t);

	/**
	 * parses the template as an array of strings, and returns the parsed 
	 * template as an array of strings.
	 * <p>
	 * Should throw an UnsupportedOperationException if it isn't implemented
	 *
	 * @param t	an array of strings containing the template - one line 
	 *		at a time
	 *
	 * @return	an array of strings containing the parsed template - 
	 *		one line at a time
	 *
	 * @throws UnsupportedOperationException	if this method isn't 
	 *						implemented
	 */
	public String [] parse(String [] t);
}

