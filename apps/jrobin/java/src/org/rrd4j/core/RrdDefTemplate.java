package org.rrd4j.core;

import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.rrd4j.DsType;
import org.rrd4j.ConsolFun;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Calendar;

/**
 * <p>Class used to create an arbitrary number of {@link org.rrd4j.core.RrdDef} (RRD definition) objects
 * from a single XML template. XML template can be supplied as an XML InputSource,
 * XML file or XML formatted string.</p>
 *
 * <p>Here is an example of a properly formatted XML template with all available
 * options in it (unwanted options can be removed):</p>
 * <pre>
 * &lt;rrd_def&gt;
 *     &lt;path&gt;test.rrd&lt;/path&gt;
 *     &lt;!-- not mandatory --&gt;
 *     &lt;start&gt;1000123456&lt;/start&gt;
 *     &lt;!-- not mandatory --&gt;
 *     &lt;step&gt;300&lt;/step&gt;
 *     &lt;!-- at least one datasource must be supplied --&gt;
 *     &lt;datasource&gt;
 *         &lt;name&gt;input&lt;/name&gt;
 *         &lt;type&gt;COUNTER&lt;/type&gt;
 *         &lt;heartbeat&gt;300&lt;/heartbeat&gt;
 *         &lt;min&gt;0&lt;/min&gt;
 *         &lt;max&gt;U&lt;/max&gt;
 *     &lt;/datasource&gt;
 *     &lt;datasource&gt;
 *         &lt;name&gt;temperature&lt;/name&gt;
 *         &lt;type&gt;GAUGE&lt;/type&gt;
 *         &lt;heartbeat&gt;400&lt;/heartbeat&gt;
 *         &lt;min&gt;U&lt;/min&gt;
 *         &lt;max&gt;1000&lt;/max&gt;
 *     &lt;/datasource&gt;
 *     &lt;!-- at least one archive must be supplied --&gt;
 *     &lt;archive&gt;
 *         &lt;cf&gt;AVERAGE&lt;/cf&gt;
 *         &lt;xff&gt;0.5&lt;/xff&gt;
 *         &lt;steps&gt;1&lt;/steps&gt;
 *         &lt;rows&gt;600&lt;/rows&gt;
 *     &lt;/archive&gt;
 *     &lt;archive&gt;
 *         &lt;cf&gt;MAX&lt;/cf&gt;
 *         &lt;xff&gt;0.6&lt;/xff&gt;
 *         &lt;steps&gt;6&lt;/steps&gt;
 *         &lt;rows&gt;7000&lt;/rows&gt;
 *     &lt;/archive&gt;
 * &lt;/rrd_def&gt;
 * </pre>
 * <p>Notes on the template syntax:</p>
 * <ul>
 * <li>There is a strong relation between the XML template syntax and the syntax of
 * {@link org.rrd4j.core.RrdDef} class methods. If you are not sure what some XML tag means, check javadoc
 * for the corresponding class.
 * <li>the path to the rrd can be specified either as a file path using path element or as an uri using a url tag</li>
 * <li>starting timestamp can be supplied either as a long integer
 * (like: 1000243567) or as an ISO formatted string (like: 2004-02-21 12:25:45)
 * <li>whitespaces are not harmful
 * <li>floating point values: anything that cannot be parsed will be treated as Double.NaN
 * (like: U, unknown, 12r.23)
 * <li>comments are allowed.
 * </ul>
 * <p>Any template value (text between <code>&lt;some_tag&gt;</code> and
 * <code>&lt;/some_tag&gt;</code>) can be replaced with
 * a variable of the following form: <code>${variable_name}</code>. Use
 * {@link org.rrd4j.core.XmlTemplate#setVariable(String, String) setVariable()}
 * methods from the base class to replace template variables with real values
 * at runtime.</p>
 *
 * <p>Typical usage scenario:</p>
 * <ul>
 * <li>Create your XML template and save it to a file (template.xml, for example)
 * <li>Replace hardcoded template values with variables if you want to change them during runtime.
 * For example, RRD path should not be hardcoded in the template - you probably want to create
 * many different RRD files from the same XML template. For example, your XML
 * template could start with:
 * <pre>
 * &lt;rrd_def&gt;
 *     &lt;path&gt;${path}&lt;/path&gt;
 *     &lt;step&gt;300&lt;/step&gt;
 *     ...
 * </pre>
 * <li>In your Java code, create RrdDefTemplate object using your XML template file:
 * <pre>
 * RrdDefTemplate t = new RrdDefTemplate(new File(template.xml));
 * </pre>
 * <li>Then, specify real values for template variables:
 * <pre>
 * t.setVariable("path", "demo/test.rrd");
 * </pre>
 * <li>Once all template variables are set, just use the template object to create RrdDef
 * object. This object is actually used to create Rrd4j RRD files:
 * <pre>
 * RrdDef def = t.getRrdDef();
 * RrdDb rrd = new RrdDb(def);
 * rrd.close();
 * </pre>
 * </ul>
 * You should create new RrdDefTemplate object only once for each XML template. Single template
 * object can be reused to create as many RrdDef objects as needed, with different values
 * specified for template variables. XML syntax check is performed only once - the first
 * definition object gets created relatively slowly, but it will be created much faster next time.
 *
 */
public class RrdDefTemplate extends XmlTemplate {
    /**
     * Creates RrdDefTemplate object from any parsable XML input source. Read general information
     * for this class to find an example of a properly formatted RrdDef XML source.
     *
     * @param xmlInputSource Xml input source
     * @throws java.io.IOException              Thrown in case of I/O error
     * @throws java.lang.IllegalArgumentException Thrown in case of XML related error (parsing error, for example)
     */
    public RrdDefTemplate(InputSource xmlInputSource) throws IOException {
        super(xmlInputSource);
    }

    /**
     * Creates RrdDefTemplate object from the string containing XML template.
     * Read general information for this class to see an example of a properly formatted XML source.
     *
     * @param xmlString String containing XML template
     * @throws java.io.IOException              Thrown in case of I/O error
     * @throws java.lang.IllegalArgumentException Thrown in case of XML related error (parsing error, for example)
     */
    public RrdDefTemplate(String xmlString) throws IOException {
        super(xmlString);
    }

    /**
     * Creates RrdDefTemplate object from the file containing XML template.
     * Read general information for this class to see an example of a properly formatted XML source.
     *
     * @param xmlFile File object representing file with XML template
     * @throws java.io.IOException              Thrown in case of I/O error
     * @throws java.lang.IllegalArgumentException Thrown in case of XML related error (parsing error, for example)
     */
    public RrdDefTemplate(File xmlFile) throws IOException {
        super(xmlFile);
    }

    /**
     * Returns RrdDef object constructed from the underlying XML template. Before this method
     * is called, values for all non-optional placeholders must be supplied. To specify
     * placeholder values at runtime, use some of the overloaded
     * {@link org.rrd4j.core.XmlTemplate#setVariable(String, String) setVariable()} methods. Once this method
     * returns, all placeholder values are preserved. To remove them all, call inherited
     * {@link org.rrd4j.core.XmlTemplate#clearValues() clearValues()} method explicitly.<p>
     *
     * @return RrdDef object constructed from the underlying XML template,
     *         with all placeholders replaced with real values. This object can be passed to the constructor
     *         of the new RrdDb object.
     * @throws java.lang.IllegalArgumentException Thrown (in most cases) if the value for some placeholder
     *                                  was not supplied through {@link org.rrd4j.core.XmlTemplate#setVariable(String, String) setVariable()}
     *                                  method call
     */
    public RrdDef getRrdDef() {
        if (!"rrd_def".equals(root.getTagName())) {
            throw new IllegalArgumentException("XML definition must start with <rrd_def>");
        }
        validateTagsOnlyOnce(root, new String[]{
                "path*", "uri*", "start", "step", "datasource*", "archive*"
        });
        // PATH must be supplied or exception is thrown
        RrdDef rrdDef;
        if (hasChildNode(root, "path")) {
            String path = getChildValue(root, "path");
            rrdDef = new RrdDef(path);
        } else if (hasChildNode(root, "uri")) {
            String uri = getChildValue(root, "uri");
            try {
                rrdDef = new RrdDef(new URI(uri));
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Wrong URI: " + uri);
            }
        } else {
            throw new IllegalArgumentException("Neither path or URI defined");
        }
        try {
            String startStr = getChildValue(root, "start");
            Calendar startGc = Util.getCalendar(startStr);
            rrdDef.setStartTime(startGc);
        }
        catch (Exception e) {
            // START is not mandatory
        }
        try {
            long step = getChildValueAsLong(root, "step");
            rrdDef.setStep(step);
        }
        catch (Exception e) {
            // STEP is not mandatory
        }
        // datsources
        Node[] dsNodes = getChildNodes(root, "datasource");
        for (Node dsNode : dsNodes) {
            validateTagsOnlyOnce(dsNode, new String[]{
                    "name", "type", "heartbeat", "min", "max"
            });
            String name = getChildValue(dsNode, "name");
            DsType type = DsType.valueOf(getChildValue(dsNode, "type"));
            long heartbeat = getChildValueAsLong(dsNode, "heartbeat");
            double min = getChildValueAsDouble(dsNode, "min");
            double max = getChildValueAsDouble(dsNode, "max");
            rrdDef.addDatasource(name, type, heartbeat, min, max);
        }
        // archives
        Node[] arcNodes = getChildNodes(root, "archive");
        for (Node arcNode : arcNodes) {
            validateTagsOnlyOnce(arcNode, new String[]{
                    "cf", "xff", "steps", "rows"
            });
            ConsolFun consolFun = ConsolFun.valueOf(getChildValue(arcNode, "cf"));
            double xff = getChildValueAsDouble(arcNode, "xff");
            int steps = getChildValueAsInt(arcNode, "steps");
            int rows = getChildValueAsInt(arcNode, "rows");
            rrdDef.addArchive(consolFun, xff, steps, rows);
        }
        return rrdDef;
    }
}
