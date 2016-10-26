/*******************************************************************************
 * Copyright (c) 2001-2005 Sasa Markovic and Ciaran Treanor.
 * Copyright (c) 2011 The OpenNMS Group, Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *******************************************************************************/

package org.jrobin.core;

import java.awt.*;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Stack;

/**
 * Extremely simple utility class used to create XML documents.
 */
public class XmlWriter {
	static final String INDENT_STR = "   ";

	private PrintWriter writer;
	private StringBuffer indent = new StringBuffer("");
	private Stack<String> openTags = new Stack<String>();

	/**
	 * Creates XmlWriter with the specified output stream to send XML code to.
	 *
	 * @param stream Output stream which receives XML code
	 */
	public XmlWriter(OutputStream stream) {
		writer = new PrintWriter(stream, true);
	}

	/**
	 * Opens XML tag
	 *
	 * @param tag XML tag name
	 */
	public void startTag(String tag) {
		writer.println(indent + "<" + tag + ">");
		openTags.push(tag);
		indent.append(INDENT_STR);
	}

	/**
	 * Closes the corresponding XML tag
	 */
	public void closeTag() {
		String tag = openTags.pop();
		indent.setLength(indent.length() - INDENT_STR.length());
		writer.println(indent + "</" + tag + ">");
	}

	/**
	 * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
	 *
	 * @param tag   XML tag name
	 * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
	 */
	public void writeTag(String tag, Object value) {
		if (value != null) {
			writer.println(indent + "<" + tag + ">" +
					escape(value.toString()) + "</" + tag + ">");
		}
		else {
			writer.println(indent + "<" + tag + "></" + tag + ">");
		}
	}

	/**
	 * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
	 *
	 * @param tag   XML tag name
	 * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
	 */
	public void writeTag(String tag, int value) {
		writeTag(tag, "" + value);
	}

	/**
	 * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
	 *
	 * @param tag   XML tag name
	 * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
	 */
	public void writeTag(String tag, long value) {
		writeTag(tag, "" + value);
	}

	/**
	 * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
	 *
	 * @param tag   XML tag name
	 * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
	 * @param nanString string to display if the value is NaN.
	 */
	public void writeTag(String tag, double value, String nanString) {
		writeTag(tag, Util.formatDouble(value, nanString, true));
	}

	/**
	 * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
	 *
	 * @param tag   XML tag name
	 * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
	 */
	public void writeTag(String tag, double value) {
		writeTag(tag, Util.formatDouble(value, true));
	}

	/**
	 * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
	 *
	 * @param tag   XML tag name
	 * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
	 */
	public void writeTag(String tag, boolean value) {
		writeTag(tag, "" + value);
	}

	/**
	 * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
	 *
	 * @param tag   XML tag name
	 * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
	 */
	public void writeTag(String tag, Color value) {
		int rgb = value.getRGB() & 0xFFFFFF;
		writeTag(tag, "#" + Integer.toHexString(rgb).toUpperCase());
	}

	/**
	 * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
	 *
	 * @param tag   XML tag name
	 * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
	 */
	public void writeTag(String tag, Font value) {
		startTag(tag);
		writeTag("name", value.getName());
		int style = value.getStyle();
		if ((style & Font.BOLD) != 0 && (style & Font.ITALIC) != 0) {
			writeTag("style", "BOLDITALIC");
		}
		else if ((style & Font.BOLD) != 0) {
			writeTag("style", "BOLD");
		}
		else if ((style & Font.ITALIC) != 0) {
			writeTag("style", "ITALIC");
		}
		else {
			writeTag("style", "PLAIN");
		}
		writeTag("size", value.getSize());
		closeTag();
	}

	/**
	 * Writes &lt;tag&gt;value&lt;/tag&gt; to output stream
	 *
	 * @param tag   XML tag name
	 * @param value value to be placed between <code>&lt;tag&gt;</code> and <code>&lt;/tag&gt;</code>
	 */
	public void writeTag(String tag, File value) {
		writeTag(tag, value.getPath());
	}

	/**
	 * Flushes the output stream
	 */
	public void flush() {
		writer.flush();
	}

	protected void finalize() throws Throwable {
		super.finalize();
		writer.close();
	}

	/**
	 * Writes XML comment to output stream
	 *
	 * @param comment comment string
	 */
	public void writeComment(Object comment) {
		writer.println(indent + "<!-- " + escape(comment.toString()) + " -->");
	}

	private static String escape(String s) {
		return s.replaceAll("<", "&lt;").replaceAll(">", "&gt;");
	}
}
