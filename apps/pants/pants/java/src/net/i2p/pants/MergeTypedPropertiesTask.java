/*
 * Ports + Ant = Pants, a simple Ant-based package manager
 * 
 * free (adj.): unencumbered; not under the control of others
 * 
 * Written by smeghead in 2005 and released into the public domain with no
 * warranty of any kind, either expressed or implied.  It probably won't make
 * your computer catch on fire, or eat your children, but it might.  Use at your
 * own risk.
 */

package net.i2p.pants;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Properties;
import java.util.StringTokenizer;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

/**
 * <p>Custom Ant task for loading properties from a
 * <code>java.util.Properties</code> file then merging them with lists of
 * expected properties. When an expected property is found in the properties
 * file it is set to the value given for it in the file. If an expected property
 * from a list isn't found in the properties file its value will be set to "" or
 * "false", depending on the property's data type.
 * </p>
 * <p>A property's data type is determined by membership in one of two lists
 * which can be passed into an instance of this class: a string-typed list and a
 * boolean-typed list. Values for string-typed properties may be any valid
 * string accepted by <code>java.util.Properties</code>, and values for
 * boolean-typed properties must be either "false" or "true".
 * </p>
 * <p>Lists holding more than one property must be comma-delimited.
 * </p>
 * <p>The output of this class is a temporary <code>java.util.Properties</code>
 * file which is suitable for reading by the standard Ant
 * <code>loadproperties</code> task.
 * </p>
 * <p>Note that if any properties in the given lists have already been defined
 * before the <code>mergetypedproperties</code> task is called, their values
 * cannot be changed since Ant properties are immutable.
 * </p>
 * <h4>Example</h4>
 * </p>
 * <p>Contents of a properties file <code>my.properties</code>:
 * <pre>
 *      some.property.exists=true
 *      hasValue=false
 *      some.property=this is a value
 *      property0=bork bork
 *      propertyX=this property wasn't passed in a list
 * </pre>
 * </p>
 * <p>Ant <code>mergetypedproperties</code> task and a <code>taskdef</code>
 * defining it:
 * <pre>
 *      &lt;taskdef name="mergetypedproperties" classname="net.i2p.pants.MergeTypedPropertiesTask" classpath="../../lib/pants.jar" /&gt;
 *      &lt;mergetypedproperties input="my.properties"
 *             output="merged-properties.temp"
 *             booleanList="some.property.exists,is.valid,hasValue"
 *             stringList="some.property,another.property,property0"
 *             /&gt;
 * </pre>
 * </p>
 * <p>Contents of properties file <code>merged-properties.temp</code> written by this task:
 * <pre>
 *      some.property.exists=true
 *      is.valid=false
 *      hasValue=false
 *      some.property=this is a value
 *      another.property=
 *      property0=bork bork
 *      propertyX=this property wasn't passed in a list
 * </pre>
 * </p>
 * <p>If you don't want this task's output to include properties which weren't
 * in the lists of expected properties, you can set the attribute
 * <code>onlyExpected</code> to <code>true</code>. In the example, this would
 * result in the file <code>merged-properties.temp</code> containing only the
 * following properties:
 * <pre>
 *      some.property.exists=true
 *      is.valid=false
 *      hasValue=false
 *      some.property=this is a value
 *      another.property=
 *      property0=bork bork
 * </pre>
 * </p>
 *  
 * @author smeghead
 */
public class MergeTypedPropertiesTask extends Task {

	private String     _booleanList   = "";
	private String     _inputFile;
	private boolean    _onlyExpected;
	private String     _outputFile;
	private Properties _propertiesIn  = new Properties();
	private Properties _propertiesOut = new Properties();
	private String     _stringList    = "";

	public void execute() throws BuildException {
		StringTokenizer strtokBoolean = new StringTokenizer(_booleanList, ",");
		StringTokenizer strtokString  = new StringTokenizer(_stringList, ",");
		String          property      = "";

		if (_inputFile == null)
			throw new BuildException("Error: 'mergetypedproperties' task requires 'input' attribute");

		if (_outputFile == null)
			throw new BuildException("Error: 'mergetypedproperties' task requires 'output' attribute");

		// Add some type-checking on the list elements

		try {
			_propertiesIn.load(new FileInputStream(_inputFile));

			while (strtokBoolean.hasMoreTokens())
				_propertiesOut.setProperty(strtokBoolean.nextToken().trim(), "false");

			while (strtokString.hasMoreTokens())
				_propertiesOut.setProperty(strtokString.nextToken().trim(), "");

			for (Enumeration enum = _propertiesIn.elements(); enum.hasMoreElements(); ) {
				property = (String) enum.nextElement();

				if (_onlyExpected && !_propertiesOut.containsKey(property))
					continue;
				else
					_propertiesOut.setProperty(property, _propertiesIn.getProperty(property));
			}

			_propertiesOut.store(new FileOutputStream(_inputFile), "This is a temporary file. It is safe to delete it.");
		} catch (IOException ioe) {
			throw new BuildException(ioe);
		}
	}

	public void setBooleanList(String booleanList) {
		_booleanList = booleanList;
	}

	public void setInput(String inputFile) {
		_inputFile = inputFile;
	}

	public void setOnlyExpected(boolean onlyExpected) {
		_onlyExpected = onlyExpected;
	}

	public void setOutput(String outputFile) {
		_outputFile = outputFile;
	}

	public void setStringList(String stringList) {
		_stringList = stringList;
	}
}
