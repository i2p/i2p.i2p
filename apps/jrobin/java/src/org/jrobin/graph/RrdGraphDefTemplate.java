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
package org.jrobin.graph;

import org.jrobin.core.RrdException;
import org.jrobin.core.Util;
import org.jrobin.core.XmlTemplate;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import java.awt.*;
import java.io.File;
import java.io.IOException;

/**
 * Class used to create an arbitrary number of RrdGraphDef (graph definition) objects
 * from a single XML template. XML template can be supplied as an XML InputSource,
 * XML file or XML formatted string.
 * <p>
 * Here is an example of a properly formatted XML template with all available options in it
 * (unwanted options can be removed/ignored):
 * <p>
 * <pre>
 * &lt;rrd_graph_def&gt;
 *     &lt;!-- use '-' to represent in-memory graph --&gt;
 *     &lt;filename&gt;test.png&lt;/filename&gt;
 *     &lt;!--
 *         starting and ending timestamps can be specified by
 *         using at-style time specification, or by specifying
 *         exact timestamps since epoch (without milliseconds)
 *     --&gt;
 *     &lt;span&gt;
 *         &lt;start&gt;now - 1d&lt;/start&gt;
 *         &lt;end&gt;now&lt;/end&gt;
 *     &lt;/span&gt;
 *     &lt;options&gt;
 *         &lt;!--
 *             specify 'true' if you want to use RrdDbPool while
 *             creating graph
 *         --&gt;
 *         &lt;use_pool&gt;false&lt;/use_pool&gt;
 *         &lt;anti_aliasing&gt;true&lt;/anti_aliasing&gt;
 *         &lt;time_grid&gt;
 *             &lt;show_grid&gt;true&lt;/show_grid&gt;
 *             &lt;!-- allowed units: second, minute, hour, day, week, month, year --&gt;
 *             &lt;minor_grid_unit&gt;minute&lt;/minor_grid_unit&gt;
 *             &lt;minor_grid_unit_count&gt;60&lt;/minor_grid_unit_count&gt;
 *             &lt;major_grid_unit&gt;hour&lt;/major_grid_unit&gt;
 *             &lt;major_grid_unit_count&gt;2&lt;/major_grid_unit_count&gt;
 *             &lt;label_unit&gt;hour&lt;/label_unit&gt;
 *             &lt;label_unit_count&gt;2&lt;/label_unit_count&gt;
 *             &lt;label_span&gt;1200&lt;/label_span&gt;
 *             &lt;!-- use SimpleDateFormat or strftime-like format to format labels --&gt;
 *             &lt;label_format&gt;dd-MMM-yy&lt;/label_format&gt;
 *         &lt;/time_grid&gt;
 *         &lt;value_grid&gt;
 *             &lt;show_grid&gt;true&lt;/show_grid&gt;
 *             &lt;grid_step&gt;100.0&lt;/grid_step&gt;
 *             &lt;label_factor&gt;5&lt;/label_factor&gt;
 *         &lt;/value_grid&gt;
 *         &lt;no_minor_grid&gt;true&lt;/no_minor_grid&gt;
 *         &lt;alt_y_grid&gt;true&lt;/alt_y_grid&gt;
 *         &lt;alt_y_mrtg&gt;true&lt;/alt_y_mrtg&gt;
 *         &lt;alt_autoscale&gt;true&lt;/alt_autoscale&gt;
 *         &lt;alt_autoscale_max&gt;true&lt;/alt_autoscale_max&gt;
 *         &lt;units_exponent&gt;3&lt;/units_exponent&gt;
 *         &lt;units_length&gt;13&lt;/units_length&gt;
 *         &lt;vertical_label&gt;Speed (kbits/sec)&lt;/vertical_label&gt;
 *         &lt;width&gt;444&lt;/width&gt;
 *         &lt;height&gt;222&lt;/height&gt;
 *         &lt;interlaced&gt;true&lt;/interlaced&gt;
 *         &lt;image_info&gt;filename = %s, width=%d, height=%d&lt;/image_info&gt;
 *         &lt;image_format&gt;png&lt;/image_format&gt;
 *         &lt;image_quality&gt;0.8&lt;/image_quality&gt;
 *         &lt;background_image&gt;luka.png&lt;/background_image&gt;
 *         &lt;overlay_image&gt;luka.png&lt;/overlay_image&gt;
 *         &lt;unit&gt;kilos&lt;/unit&gt;
 *         &lt;lazy&gt;false&lt;/lazy&gt;
 *         &lt;min_value&gt;0&lt;/min_value&gt;
 *         &lt;max_value&gt;5000&lt;/max_value&gt;
 *         &lt;rigid&gt;true&lt;/rigid&gt;
 *         &lt;base&gt;1000&lt;/base&gt;
 *         &lt;logarithmic&gt;false&lt;/logarithmic&gt;
 *         &lt;colors&gt;
 *             &lt;canvas&gt;#FFFFFF&lt;/canvas&gt;
 *             &lt;back&gt;#FFFFFF&lt;/back&gt;
 *             &lt;shadea&gt;#AABBCC&lt;/shadea&gt;
 *             &lt;shadeb&gt;#DDDDDD&lt;/shadeb&gt;
 *             &lt;grid&gt;#FF0000&lt;/grid&gt;
 *             &lt;mgrid&gt;#00FF00&lt;/mgrid&gt;
 *             &lt;font&gt;#FFFFFF&lt;/font&gt;
 *             &lt;frame&gt;#EE00FF&lt;/frame&gt;
 *             &lt;arrow&gt;#FF0000&lt;/arrow&gt;
 *         &lt;/colors&gt;
 *         &lt;no_legend&gt;false&lt;/no_legend&gt;
 *         &lt;only_graph&gt;false&lt;/only_graph&gt;
 *         &lt;force_rules_legend&gt;false&lt;/force_rules_legend&gt;
 *         &lt;title&gt;This is a title&lt;/title&gt;
 *         &lt;step&gt;300&lt;/step&gt;
 *         &lt;fonts&gt;
 *             &lt;small_font&gt;
 *                 &lt;name&gt;Courier&lt;/name&gt;
 *                 &lt;style&gt;bold italic&lt;/style&gt;
 *                 &lt;size&gt;12&lt;/size&gt;
 *             &lt;/small_font&gt;
 *             &lt;large_font&gt;
 *                 &lt;name&gt;Courier&lt;/name&gt;
 *                 &lt;style&gt;plain&lt;/style&gt;
 *                 &lt;size&gt;11&lt;/size&gt;
 *             &lt;/large_font&gt;
 *         &lt;/fonts&gt;
 *         &lt;first_day_of_week&gt;SUNDAY&lt;/first_day_of_week&gt;
 *     &lt;/options&gt;
 *     &lt;datasources&gt;
 *         &lt;def&gt;
 *             &lt;name&gt;x&lt;/name&gt;
 *             &lt;rrd&gt;test.rrd&lt;/rrd&gt;
 *             &lt;source&gt;sun&lt;/source&gt;
 *             &lt;cf&gt;AVERAGE&lt;/cf&gt;
 *             &lt;backend&gt;FILE&lt;/backend&gt;
 *         &lt;/def&gt;
 *         &lt;def&gt;
 *             &lt;name&gt;y&lt;/name&gt;
 *             &lt;rrd&gt;test.rrd&lt;/rrd&gt;
 *             &lt;source&gt;shade&lt;/source&gt;
 *             &lt;cf&gt;AVERAGE&lt;/cf&gt;
 *         &lt;/def&gt;
 *         &lt;cdef&gt;
 *             &lt;name&gt;x_plus_y&lt;/name&gt;
 *             &lt;rpn&gt;x,y,+&lt;/rpn&gt;
 *         &lt;/cdef&gt;
 *         &lt;cdef&gt;
 *             &lt;name&gt;x_minus_y&lt;/name&gt;
 *             &lt;rpn&gt;x,y,-&lt;/rpn&gt;
 *         &lt;/cdef&gt;
 *         &lt;sdef&gt;
 *             &lt;name&gt;x_avg&lt;/name&gt;
 *             &lt;source&gt;x&lt;/source&gt;
 *             &lt;cf&gt;AVERAGE&lt;/cf&gt;
 *         &lt;/sdef&gt;
 *         &lt;sdef&gt;
 *             &lt;name&gt;y_max&lt;/name&gt;
 *             &lt;source&gt;y&lt;/source&gt;
 *             &lt;cf&gt;MAX&lt;/cf&gt;
 *         &lt;/sdef&gt;
 *     &lt;/datasources&gt;
 *     &lt;graph&gt;
 *         &lt;area&gt;
 *             &lt;datasource&gt;x&lt;/datasource&gt;
 *             &lt;color&gt;#FF0000&lt;/color&gt;
 *             &lt;legend&gt;X value\r&lt;/legend&gt;
 *         &lt;/area&gt;
 *         &lt;stack&gt;
 *             &lt;datasource&gt;y&lt;/datasource&gt;
 *             &lt;color&gt;#00FF00&lt;/color&gt;
 *             &lt;legend&gt;Y value\r&lt;/legend&gt;
 *         &lt;/stack&gt;
 *         &lt;line&gt;
 *             &lt;datasource&gt;x&lt;/datasource&gt;
 *             &lt;color&gt;#FF0000&lt;/color&gt;
 *             &lt;legend&gt;X value\r&lt;/legend&gt;
 *             &lt;width&gt;2&lt;/width&gt;
 *         &lt;/line&gt;
 *         &lt;print&gt;
 *             &lt;datasource&gt;x&lt;/datasource&gt;
 *             &lt;cf&gt;AVERAGE&lt;/cf&gt;
 *             &lt;format&gt;Average is %7.3f\c&lt;/format&gt;
 *         &lt;/print&gt;
 *         &lt;gprint&gt;
 *             &lt;datasource&gt;y&lt;/datasource&gt;
 *             &lt;cf&gt;MAX&lt;/cf&gt;
 *             &lt;format&gt;Max is %7.3f\c&lt;/format&gt;
 *         &lt;/gprint&gt;
 *         &lt;hrule&gt;
 *             &lt;value&gt;1250&lt;/value&gt;
 *             &lt;color&gt;#0000FF&lt;/color&gt;
 *             &lt;legend&gt;This is a horizontal rule&lt;/legend&gt;
 *         &lt;/hrule&gt;
 *         &lt;vrule&gt;
 *             &lt;time&gt;now-6h&lt;/time&gt;
 *             &lt;color&gt;#0000FF&lt;/color&gt;
 *             &lt;legend&gt;This is a vertical rule&lt;/legend&gt;
 *         &lt;/vrule&gt;
 *         &lt;comment&gt;Simple comment&lt;/comment&gt;
 *         &lt;comment&gt;One more comment\c&lt;/comment&gt;
 *     &lt;/graph&gt;
 * &lt;/rrd_graph_def&gt;
 * </pre>
 * Notes on the template syntax:
 * <p>
 * <ul>
 * <li>There is a strong relation between the XML template syntax and the syntax of
 * {@link RrdGraphDef} class methods. If you are not sure what some XML tag means, check javadoc
 * for the corresponding class method.
 * <li>hard-coded timestamps in templates should be long integeres
 * (like: 1000243567) or at-style formatted strings
 * <li>whitespaces are not harmful
 * <li>use <code>true</code>, <code>on</code>, <code>yes</code>, <code>y</code>,
 * or <code>1</code> to specify boolean <code>true</code> value (anything else will
 * be treated as <code>false</code>).
 * <li>floating point values: anything that cannot be parsed will be treated as Double.NaN
 * (like: U, unknown, 12r.23)
 * <li>use #RRGGBB or #RRGGBBAA format to specify colors.
 * <li>valid font styles are: PLAIN, ITALIC, BOLD, BOLDITALIC
 * <li>comments are allowed.
 * </ul>
 * Any template value (text between <code>&lt;some_tag&gt;</code> and
 * <code>&lt;/some_tag&gt;</code>) can be replaced with
 * a variable of the following form: <code>${variable_name}</code>. Use
 * {@link XmlTemplate#setVariable(String, String) setVariable()}
 * methods from the base class to replace
 * template variables with real values at runtime.
 * <p>
 * Typical usage scenario:
 * <p>
 * <ul>
 * <li>Create your XML template and save it to a file (template.xml, for example)
 * <li>Replace template values with variables if you want to change them during runtime.
 * For example, time span should not be hard-coded in the template - you probably want to create
 * many different graphs with different time spans from the same XML template.
 * For example, your XML template could start with:
 * <pre>
 * &lt;rrd_graph_def&gt;
 *     ...
 *     &lt;span&gt;
 *         &lt;start&gt;${start}&lt;/start&gt;
 *         &lt;end&gt;${end}&lt;/end&gt;
 *     &lt;/span&gt;
 *     ...
 * </pre>
 * <li>In your Java code, create RrdGraphDefTemplate object using your XML template file:
 * <pre>
 * RrdGraphDefTemplate t = new RrdGraphDefTemplate(new File(template.xml));
 * </pre>
 * <li>Then, specify real values for template variables:
 * <pre>
 * t.setVariable("start", new GregorianCalendar(2004, 2, 25));
 * t.setVariable("end", new GregorianCalendar(2004, 2, 26));
 * </pre>
 * <li>Once all template variables are set, just use the template object to create RrdGraphDef
 * object. This object is actually used to create JRobin grahps:
 * <pre>
 * RrdGraphDef gdef = t.getRrdGraphDef();
 * RrdGraph g = new RrdGraph(gdef);
 * </pre>
 * </ul>
 * You should create new RrdGraphDefTemplate object only once for each XML template. Single template
 * object can be reused to create as many RrdGraphDef objects as needed, with different values
 * specified for template variables. XML synatax check is performed only once - the first graph
 * definition object gets created relatively slowly, but it will be created much faster next time.
 */
public class RrdGraphDefTemplate extends XmlTemplate implements RrdGraphConstants {
	static final Color BLIND_COLOR = new Color(0, 0, 0, 0);

	private RrdGraphDef rrdGraphDef;

	/**
	 * Creates template object from any parsable XML source
	 *
	 * @param inputSource XML source
	 * @throws IOException  thrown in case of I/O error
	 * @throws RrdException usually thrown in case of XML related error
	 */
	public RrdGraphDefTemplate(InputSource inputSource) throws IOException, RrdException {
		super(inputSource);
	}

	/**
	 * Creates template object from the file containing XML template code
	 *
	 * @param xmlFile file containing XML template
	 * @throws IOException  thrown in case of I/O error
	 * @throws RrdException usually thrown in case of XML related error
	 */
	public RrdGraphDefTemplate(File xmlFile) throws IOException, RrdException {
		super(xmlFile);
	}

	/**
	 * Creates template object from the string containing XML template code
	 *
	 * @param xmlString string containing XML template
	 * @throws IOException  thrown in case of I/O error
	 * @throws RrdException usually thrown in case of XML related error
	 */
	public RrdGraphDefTemplate(String xmlString) throws IOException, RrdException {
		super(xmlString);
	}

	/**
	 * Creates RrdGraphDef object which can be used to create RrdGraph
	 * object (actual JRobin graphs). Before this method is called, all template variables (if any)
	 * must be resolved (replaced with real values).
	 * See {@link XmlTemplate#setVariable(String, String) setVariable()} method information to
	 * understand how to supply values for template variables.
	 *
	 * @return Graph definition which can be used to create RrdGraph object (actual JRobin graphs)
	 * @throws RrdException Thrown if parsed XML template contains invalid (unrecognized) tags
	 */
	public RrdGraphDef getRrdGraphDef() throws RrdException {
		// basic check
		if (!root.getTagName().equals("rrd_graph_def")) {
			throw new RrdException("XML definition must start with <rrd_graph_def>");
		}
		validateTagsOnlyOnce(root, new String[] {"filename", "span", "options", "datasources", "graph"});
		rrdGraphDef = new RrdGraphDef();
		// traverse all nodes
		Node[] childNodes = getChildNodes(root);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("filename")) {
				resolveFilename(childNode);
			}
			// SPAN
			else if (nodeName.equals("span")) {
				resolveSpan(childNode);
			}
			// OPTIONS
			else if (nodeName.equals("options")) {
				resolveOptions(childNode);
			}
			// DATASOURCES
			else if (nodeName.equals("datasources")) {
				resolveDatasources(childNode);
			}
			// GRAPH ELEMENTS
			else if (nodeName.equals("graph")) {
				resolveGraphElements(childNode);
			}
		}
		return rrdGraphDef;
	}

	private void resolveGraphElements(Node graphNode) throws RrdException {
		validateTagsOnlyOnce(graphNode, new String[] {"area*", "line*", "stack*",
				"print*", "gprint*", "hrule*", "vrule*", "comment*"});
		Node[] childNodes = getChildNodes(graphNode);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("area")) {
				resolveArea(childNode);
			}
			else if (nodeName.equals("line")) {
				resolveLine(childNode);
			}
			else if (nodeName.equals("stack")) {
				resolveStack(childNode);
			}
			else if (nodeName.equals("print")) {
				resolvePrint(childNode, false);
			}
			else if (nodeName.equals("gprint")) {
				resolvePrint(childNode, true);
			}
			else if (nodeName.equals("hrule")) {
				resolveHRule(childNode);
			}
			else if (nodeName.equals("vrule")) {
				resolveVRule(childNode);
			}
			else if (nodeName.equals("comment")) {
				rrdGraphDef.comment(getValue(childNode));
			}
		}
	}

	private void resolveVRule(Node parentNode) throws RrdException {
		validateTagsOnlyOnce(parentNode, new String[] {"time", "color", "legend"});
		long timestamp = Long.MIN_VALUE;
		Paint color = null;
		String legend = null;
		Node[] childNodes = getChildNodes(parentNode);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("time")) {
				timestamp = Util.getTimestamp(getValue(childNode));
			}
			else if (nodeName.equals("color")) {
				color = getValueAsColor(childNode);
			}
			else if (nodeName.equals("legend")) {
				legend = getValue(childNode);
			}
		}
		if (timestamp != Long.MIN_VALUE && color != null) {
			rrdGraphDef.vrule(timestamp, color, legend);
		}
		else {
			throw new RrdException("Incomplete VRULE settings");
		}
	}

	private void resolveHRule(Node parentNode) throws RrdException {
		validateTagsOnlyOnce(parentNode, new String[] {"value", "color", "legend"});
		double value = Double.NaN;
		Paint color = null;
		String legend = null;
		Node[] childNodes = getChildNodes(parentNode);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("value")) {
				value = getValueAsDouble(childNode);
			}
			else if (nodeName.equals("color")) {
				color = getValueAsColor(childNode);
			}
			else if (nodeName.equals("legend")) {
				legend = getValue(childNode);
			}
		}
		if (!Double.isNaN(value) && color != null) {
			rrdGraphDef.hrule(value, color, legend);
		}
		else {
			throw new RrdException("Incomplete HRULE settings");
		}
	}

	private void resolvePrint(Node parentNode, boolean isInGraph) throws RrdException {
		validateTagsOnlyOnce(parentNode, new String[] {"datasource", "cf", "format"});
		String datasource = null, cf = null, format = null;
		Node[] childNodes = getChildNodes(parentNode);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("datasource")) {
				datasource = getValue(childNode);
			}
			else if (nodeName.equals("cf")) {
				cf = getValue(childNode);
			}
			else if (nodeName.equals("format")) {
				format = getValue(childNode);
			}
		}
		if (datasource != null && cf != null && format != null) {
			if (isInGraph) {
				rrdGraphDef.gprint(datasource, cf, format);
			}
			else {
				rrdGraphDef.print(datasource, cf, format);
			}
		}
		else {
			throw new RrdException("Incomplete " + (isInGraph ? "GRPINT" : "PRINT") + " settings");
		}
	}

	private void resolveStack(Node parentNode) throws RrdException {
		validateTagsOnlyOnce(parentNode, new String[] {"datasource", "color", "legend"});
		String datasource = null, legend = null;
		Paint color = null;
		Node[] childNodes = getChildNodes(parentNode);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("datasource")) {
				datasource = getValue(childNode);
			}
			else if (nodeName.equals("color")) {
				color = getValueAsColor(childNode);
			}
			else if (nodeName.equals("legend")) {
				legend = getValue(childNode);
			}
		}
		if (datasource != null) {
			if (color != null) {
				rrdGraphDef.stack(datasource, color, legend);
			}
			else {
				rrdGraphDef.stack(datasource, BLIND_COLOR, legend);
			}
		}
		else {
			throw new RrdException("Incomplete STACK settings");
		}
	}

	private void resolveLine(Node parentNode) throws RrdException {
		validateTagsOnlyOnce(parentNode, new String[] {"datasource", "color", "legend", "width"});
		String datasource = null, legend = null;
		Paint color = null;
		float width = 1.0F;
		Node[] childNodes = getChildNodes(parentNode);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("datasource")) {
				datasource = getValue(childNode);
			}
			else if (nodeName.equals("color")) {
				color = getValueAsColor(childNode);
			}
			else if (nodeName.equals("legend")) {
				legend = getValue(childNode);
			}
			else if (nodeName.equals("width")) {
				width = (float) getValueAsDouble(childNode);
			}
		}
		if (datasource != null) {
			if (color != null) {
				rrdGraphDef.line(datasource, color, legend, width);
			}
			else {
				rrdGraphDef.line(datasource, BLIND_COLOR, legend, width);
			}
		}
		else {
			throw new RrdException("Incomplete LINE settings");
		}
	}

	private void resolveArea(Node parentNode) throws RrdException {
		validateTagsOnlyOnce(parentNode, new String[] {"datasource", "color", "legend"});
		String datasource = null, legend = null;
		Paint color = null;
		Node[] childNodes = getChildNodes(parentNode);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("datasource")) {
				datasource = getValue(childNode);
			}
			else if (nodeName.equals("color")) {
				color = getValueAsColor(childNode);
			}
			else if (nodeName.equals("legend")) {
				legend = getValue(childNode);
			}
		}
		if (datasource != null) {
			if (color != null) {
				rrdGraphDef.area(datasource, color, legend);
			}
			else {
				rrdGraphDef.area(datasource, BLIND_COLOR, legend);
			}
		}
		else {
			throw new RrdException("Incomplete AREA settings");
		}
	}

	private void resolveDatasources(Node datasourcesNode) throws RrdException {
		validateTagsOnlyOnce(datasourcesNode, new String[] {"def*", "cdef*", "sdef*"});
		Node[] childNodes = getChildNodes(datasourcesNode);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("def")) {
				resolveDef(childNode);
			}
			else if (nodeName.equals("cdef")) {
				resolveCDef(childNode);
			}
			else if (nodeName.equals("sdef")) {
				resolveSDef(childNode);
			}
		}
	}

	private void resolveSDef(Node parentNode) throws RrdException {
		validateTagsOnlyOnce(parentNode, new String[] {"name", "source", "cf"});
		String name = null, source = null, cf = null;
		Node[] childNodes = getChildNodes(parentNode);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("name")) {
				name = getValue(childNode);
			}
			else if (nodeName.equals("source")) {
				source = getValue(childNode);
			}
			else if (nodeName.equals("cf")) {
				cf = getValue(childNode);
			}
		}
		if (name != null && source != null && cf != null) {
			rrdGraphDef.datasource(name, source, cf);
		}
		else {
			throw new RrdException("Incomplete SDEF settings");
		}
	}

	private void resolveCDef(Node parentNode) throws RrdException {
		validateTagsOnlyOnce(parentNode, new String[] {"name", "rpn"});
		String name = null, rpn = null;
		Node[] childNodes = getChildNodes(parentNode);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("name")) {
				name = getValue(childNode);
			}
			else if (nodeName.equals("rpn")) {
				rpn = getValue(childNode);
			}
		}
		if (name != null && rpn != null) {
			rrdGraphDef.datasource(name, rpn);
		}
		else {
			throw new RrdException("Incomplete CDEF settings");
		}
	}

	private void resolveDef(Node parentNode) throws RrdException {
		validateTagsOnlyOnce(parentNode, new String[] {"name", "rrd", "source", "cf", "backend"});
		String name = null, rrd = null, source = null, cf = null, backend = null;
		Node[] childNodes = getChildNodes(parentNode);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("name")) {
				name = getValue(childNode);
			}
			else if (nodeName.equals("rrd")) {
				rrd = getValue(childNode);
			}
			else if (nodeName.equals("source")) {
				source = getValue(childNode);
			}
			else if (nodeName.equals("cf")) {
				cf = getValue(childNode);
			}
			else if (nodeName.equals("backend")) {
				backend = getValue(childNode);
			}
		}
		if (name != null && rrd != null && source != null && cf != null) {
			rrdGraphDef.datasource(name, rrd, source, cf, backend);
		}
		else {
			throw new RrdException("Incomplete DEF settings");
		}
	}

	private void resolveFilename(Node filenameNode) {
		String filename = getValue(filenameNode);
		rrdGraphDef.setFilename(filename);
	}

	private void resolveSpan(Node spanNode) throws RrdException {
		validateTagsOnlyOnce(spanNode, new String[] {"start", "end"});
		String startStr = getChildValue(spanNode, "start");
		String endStr = getChildValue(spanNode, "end");
		long[] span = Util.getTimestamps(startStr, endStr);
		rrdGraphDef.setStartTime(span[0]);
		rrdGraphDef.setEndTime(span[1]);
	}

	private void resolveOptions(Node rootOptionNode) throws RrdException {
		validateTagsOnlyOnce(rootOptionNode, new String[] {
				"anti_aliasing", "use_pool", "time_grid", "value_grid", "alt_y_grid", "alt_y_mrtg",
				"no_minor_grid", "alt_autoscale", "alt_autoscale_max", "units_exponent", "units_length",
				"vertical_label", "width", "height", "interlaced", "image_info", "image_format",
				"image_quality", "background_image", "overlay_image", "unit", "lazy",
				"min_value", "max_value", "rigid", "base", "logarithmic", "colors",
				"no_legend", "only_graph", "force_rules_legend", "title", "step", "fonts",
				"first_day_of_week", "signature"
		});
		Node[] optionNodes = getChildNodes(rootOptionNode);
		for (Node optionNode : optionNodes) {
			String option = optionNode.getNodeName();
			if (option.equals("use_pool")) {
				rrdGraphDef.setPoolUsed(getValueAsBoolean(optionNode));
			}
			else if (option.equals("anti_aliasing")) {
				rrdGraphDef.setAntiAliasing(getValueAsBoolean(optionNode));
			}
			else if (option.equals("time_grid")) {
				resolveTimeGrid(optionNode);
			}
			else if (option.equals("value_grid")) {
				resolveValueGrid(optionNode);
			}
			else if (option.equals("no_minor_grid")) {
				rrdGraphDef.setNoMinorGrid(getValueAsBoolean(optionNode));
			}
			else if (option.equals("alt_y_grid")) {
				rrdGraphDef.setAltYGrid(getValueAsBoolean(optionNode));
			}
			else if (option.equals("alt_y_mrtg")) {
				rrdGraphDef.setAltYMrtg(getValueAsBoolean(optionNode));
			}
			else if (option.equals("alt_autoscale")) {
				rrdGraphDef.setAltAutoscale(getValueAsBoolean(optionNode));
			}
			else if (option.equals("alt_autoscale_max")) {
				rrdGraphDef.setAltAutoscaleMax(getValueAsBoolean(optionNode));
			}
			else if (option.equals("units_exponent")) {
				rrdGraphDef.setUnitsExponent(getValueAsInt(optionNode));
			}
			else if (option.equals("units_length")) {
				rrdGraphDef.setUnitsLength(getValueAsInt(optionNode));
			}
			else if (option.equals("vertical_label")) {
				rrdGraphDef.setVerticalLabel(getValue(optionNode));
			}
			else if (option.equals("width")) {
				rrdGraphDef.setWidth(getValueAsInt(optionNode));
			}
			else if (option.equals("height")) {
				rrdGraphDef.setHeight(getValueAsInt(optionNode));
			}
			else if (option.equals("interlaced")) {
				rrdGraphDef.setInterlaced(getValueAsBoolean(optionNode));
			}
			else if (option.equals("image_info")) {
				rrdGraphDef.setImageInfo(getValue(optionNode));
			}
			else if (option.equals("image_format")) {
				rrdGraphDef.setImageFormat(getValue(optionNode));
			}
			else if (option.equals("image_quality")) {
				rrdGraphDef.setImageQuality((float) getValueAsDouble(optionNode));
			}
			else if (option.equals("background_image")) {
				rrdGraphDef.setBackgroundImage(getValue(optionNode));
			}
			else if (option.equals("overlay_image")) {
				rrdGraphDef.setOverlayImage(getValue(optionNode));
			}
			else if (option.equals("unit")) {
				rrdGraphDef.setUnit(getValue(optionNode));
			}
			else if (option.equals("lazy")) {
				rrdGraphDef.setLazy(getValueAsBoolean(optionNode));
			}
			else if (option.equals("min_value")) {
				rrdGraphDef.setMinValue(getValueAsDouble(optionNode));
			}
			else if (option.equals("max_value")) {
				rrdGraphDef.setMaxValue(getValueAsDouble(optionNode));
			}
			else if (option.equals("rigid")) {
				rrdGraphDef.setRigid(getValueAsBoolean(optionNode));
			}
			else if (option.equals("base")) {
				rrdGraphDef.setBase(getValueAsDouble(optionNode));
			}
			else if (option.equals("logarithmic")) {
				rrdGraphDef.setLogarithmic(getValueAsBoolean(optionNode));
			}
			else if (option.equals("colors")) {
				resolveColors(optionNode);
			}
			else if (option.equals("no_legend")) {
				rrdGraphDef.setNoLegend(getValueAsBoolean(optionNode));
			}
			else if (option.equals("only_graph")) {
				rrdGraphDef.setOnlyGraph(getValueAsBoolean(optionNode));
			}
			else if (option.equals("force_rules_legend")) {
				rrdGraphDef.setForceRulesLegend(getValueAsBoolean(optionNode));
			}
			else if (option.equals("title")) {
				rrdGraphDef.setTitle(getValue(optionNode));
			}
			else if (option.equals("step")) {
				rrdGraphDef.setStep(getValueAsLong(optionNode));
			}
			else if (option.equals("fonts")) {
				resolveFonts(optionNode);
			}
			else if (option.equals("first_day_of_week")) {
				int dayIndex = resolveFirstDayOfWeek(getValue(optionNode));
				rrdGraphDef.setFirstDayOfWeek(dayIndex);
			}
			else if (option.equals("signature")) {
				rrdGraphDef.setShowSignature(getValueAsBoolean(optionNode));
			}
		}
	}

	private int resolveFirstDayOfWeek(String firstDayOfWeek) throws RrdException {
		if (firstDayOfWeek.equalsIgnoreCase("sunday")) {
			return SUNDAY;
		}
		else if (firstDayOfWeek.equalsIgnoreCase("monday")) {
			return MONDAY;
		}
		else if (firstDayOfWeek.equalsIgnoreCase("tuesday")) {
			return TUESDAY;
		}
		else if (firstDayOfWeek.equalsIgnoreCase("wednesday")) {
			return WEDNESDAY;
		}
		else if (firstDayOfWeek.equalsIgnoreCase("thursday")) {
			return THURSDAY;
		}
		else if (firstDayOfWeek.equalsIgnoreCase("friday")) {
			return FRIDAY;
		}
		else if (firstDayOfWeek.equalsIgnoreCase("saturday")) {
			return SATURDAY;
		}
		throw new RrdException("Never heard for this day of week: " + firstDayOfWeek);
	}

	private void resolveFonts(Node parentNode) throws RrdException {
		validateTagsOnlyOnce(parentNode, new String[] {"small_font", "large_font"});
		Node[] childNodes = getChildNodes(parentNode);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("small_font")) {
				rrdGraphDef.setSmallFont(resolveFont(childNode));
			}
			else if (nodeName.equals("large_font")) {
				rrdGraphDef.setLargeFont(resolveFont(childNode));
			}
		}
	}

	private Font resolveFont(Node parentNode) throws RrdException {
		validateTagsOnlyOnce(parentNode, new String[] {"name", "style", "size"});
		String name = null, style = null;
		int size = 0;
		Node[] childNodes = getChildNodes(parentNode);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("name")) {
				name = getValue(childNode);
			}
			else if (nodeName.equals("style")) {
				style = getValue(childNode).toLowerCase();
			}
			else if (nodeName.equals("size")) {
				size = getValueAsInt(childNode);
			}
		}
		if (name != null && style != null && size > 0) {
			boolean isItalic = style.contains("italic"), isBold = style.contains("bold");
			int fstyle = Font.PLAIN;
			if (isItalic && isBold) {
				fstyle = Font.BOLD + Font.ITALIC;
			}
			else if (isItalic) {
				fstyle = Font.ITALIC;
			}
			else if (isBold) {
				fstyle = Font.BOLD;
			}
			return new Font(name, fstyle, size);
		}
		else {
			throw new RrdException("Incomplete font specification");
		}
	}

	private void resolveColors(Node parentNode) throws RrdException {
		validateTagsOnlyOnce(parentNode, COLOR_NAMES);
		Node[] childNodes = getChildNodes(parentNode);
		for (Node childNode : childNodes) {
			String colorName = childNode.getNodeName();
			rrdGraphDef.setColor(colorName, getValueAsColor(childNode));
		}
	}

	private void resolveValueGrid(Node parentNode) throws RrdException {
		validateTagsOnlyOnce(parentNode, new String[] {"show_grid", "grid_step", "label_factor"});
		boolean showGrid = true;
		double gridStep = Double.NaN;
		int NOT_SET = Integer.MIN_VALUE, labelFactor = NOT_SET;
		Node[] childNodes = getChildNodes(parentNode);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("show_grid")) {
				showGrid = getValueAsBoolean(childNode);
			}
			else if (nodeName.equals("grid_step")) {
				gridStep = getValueAsDouble(childNode);
			}
			else if (nodeName.equals("label_factor")) {
				labelFactor = getValueAsInt(childNode);
			}
		}
		rrdGraphDef.setDrawYGrid(showGrid);
		if (!Double.isNaN(gridStep) && labelFactor != NOT_SET) {
			rrdGraphDef.setValueAxis(gridStep, labelFactor);
		}
		else if (!Double.isNaN(gridStep) || labelFactor != NOT_SET) {
			throw new RrdException("Incomplete value axis settings");
		}
	}

	private void resolveTimeGrid(Node parentNode) throws RrdException {
		validateTagsOnlyOnce(parentNode, new String[] {
				"show_grid", "minor_grid_unit",
				"minor_grid_unit_count", "major_grid_unit",
				"major_grid_unit_count", "label_unit", "label_unit_count",
				"label_span", "label_format"
		});
		boolean showGrid = true;
		final int NOT_SET = Integer.MIN_VALUE;
		int minorGridUnit = NOT_SET, minorGridUnitCount = NOT_SET,
				majorGridUnit = NOT_SET, majorGridUnitCount = NOT_SET,
				labelUnit = NOT_SET, labelUnitCount = NOT_SET, labelSpan = NOT_SET;
		String labelFormat = null;
		Node[] childNodes = getChildNodes(parentNode);
		for (Node childNode : childNodes) {
			String nodeName = childNode.getNodeName();
			if (nodeName.equals("show_grid")) {
				showGrid = getValueAsBoolean(childNode);
			}
			else if (nodeName.equals("minor_grid_unit")) {
				minorGridUnit = resolveTimeUnit(getValue(childNode));
			}
			else if (nodeName.equals("minor_grid_unit_count")) {
				minorGridUnitCount = getValueAsInt(childNode);
			}
			else if (nodeName.equals("major_grid_unit")) {
				majorGridUnit = resolveTimeUnit(getValue(childNode));
			}
			else if (nodeName.equals("major_grid_unit_count")) {
				majorGridUnitCount = getValueAsInt(childNode);
			}
			else if (nodeName.equals("label_unit")) {
				labelUnit = resolveTimeUnit(getValue(childNode));
			}
			else if (nodeName.equals("label_unit_count")) {
				labelUnitCount = getValueAsInt(childNode);
			}
			else if (nodeName.equals("label_span")) {
				labelSpan = getValueAsInt(childNode);
			}
			else if (nodeName.equals("label_format")) {
				labelFormat = getValue(childNode);
			}
		}
		rrdGraphDef.setDrawXGrid(showGrid);
		if (minorGridUnit != NOT_SET && minorGridUnitCount != NOT_SET &&
				majorGridUnit != NOT_SET && majorGridUnitCount != NOT_SET &&
				labelUnit != NOT_SET && labelUnitCount != NOT_SET && labelSpan != NOT_SET && labelFormat != null) {
			rrdGraphDef.setTimeAxis(minorGridUnit, minorGridUnitCount, majorGridUnit, majorGridUnitCount,
					labelUnit, labelUnitCount, labelSpan, labelFormat);
		}
		else if (minorGridUnit != NOT_SET || minorGridUnitCount != NOT_SET ||
				majorGridUnit != NOT_SET || majorGridUnitCount != NOT_SET ||
				labelUnit != NOT_SET || labelUnitCount != NOT_SET || labelSpan != NOT_SET || labelFormat != null) {
			throw new RrdException("Incomplete time axis settings");
		}
	}

	private int resolveTimeUnit(String unit) throws RrdException {
		if (unit.equalsIgnoreCase("second")) {
			return RrdGraphConstants.SECOND;
		}
		else if (unit.equalsIgnoreCase("minute")) {
			return RrdGraphConstants.MINUTE;
		}
		else if (unit.equalsIgnoreCase("hour")) {
			return RrdGraphConstants.HOUR;
		}
		else if (unit.equalsIgnoreCase("day")) {
			return RrdGraphConstants.DAY;
		}
		else if (unit.equalsIgnoreCase("week")) {
			return RrdGraphConstants.WEEK;
		}
		else if (unit.equalsIgnoreCase("month")) {
			return RrdGraphConstants.MONTH;
		}
		else if (unit.equalsIgnoreCase("year")) {
			return RrdGraphConstants.YEAR;
		}
		throw new RrdException("Unknown time unit specified: " + unit);
	}
}
