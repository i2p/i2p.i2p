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

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

/**
 * <p>Custom Ant task for matching the contents of a file against a given
 * regular expression and writing any matching groups to a file in
 * <code>java.util.Properties</code> format.
 * </p>
 * <p>Each key in the properties file is named after the number corresponding to
 * its matching group and its value is the contents of the matching group.
 * </p>
 * <p>Regular expressions passed to this task must conform to the specification
 * used by Sun's <code>java.util.regex</code> package and thus are mostly
 * compatible with Perl 5 regular expressions.
 * </p>
 * <p>When calling the <code>match</code> task, the attributes
 * <code>input</code>, <code>output</code>, and <code>regex</code> are required.
 * </p>
 * <p>Optional boolean attributes may be used to toggle various modes for the
 * regular expression engine (all are set to <code>false</code> by default):
 * </p>
 * <table>
 * <tr><td><code>canonicaleq</code></td><td>Enable canonical equivalence</td></tr>
 * <tr><td><code>caseinsensitive</code></td><td>Enable case-insensitive matching</td></tr>
 * <tr><td><code>comments</code></td><td>Permit whitespace and comments in pattern</td></tr>
 * <tr><td><code>dotall</code></td><td>Enable dotall mode</td></tr>
 * <tr><td><code>multiline</code></td><td>Enable multi-line mode</td></tr>
 * <tr><td><code>unicodecase</code></td><td>Enable Unicode-aware case folding</td></tr>
 * <tr><td><code>unixlines</code></td><td>Enable Unix lines mode</td></tr>
 * </table>
 * <p>There is one additional optional boolean attribute,
 * <code>failOnNoMatch</code>. If this attribute is <code>true</code> it causes
 * the <code>match</code> task to throw a
 * <code>org.apache.tools.ant.BuildException</code> and fail if no matches for
 * the regular expression are found. The default value is <code>false</code>,
 * meaning a failed match will simply result in a warning message to
 * <code>STDERR</code> and an empty (0 byte) <code>output</code> file being
 * created. 
 * </p>
 * <p>
 * <h4>Example</h4>
 * </p>
 * <p>Contents of input file <code>letter.txt</code>:
 * <pre>
 *      Dear Alice,
 * 
 *      How's about you and me gettin' together for some anonymous foo action?
 * 
 *      Kisses,
 *      Bob
 * </pre>
 * </p>
 * <p>Ant <code>match</code> task and a <code>taskdef</code> defining it:
 * <pre>
 *      &lt;taskdef name="match" classname="net.i2p.pants.MatchTask" classpath="../../lib/pants.jar" /&gt;
 *      &lt;match input="letter.txt"
 *             output="matches.txt"
 *             regex="about (\S*?) and (\S*?) .+anonymous (\S*?)"
 *             /&gt;
 * </pre>
 * </p>
 * <p>Contents of properties file <code>matches.txt</code> written by this task:
 * <pre>
 *      group.0=about you and me gettin' together for some anonymous foo
 *      group.1=you
 *      group.2=me
 *      group.3=foo
 * </pre>
 * </p>
 * <p>These values can be loaded from <code>matches.txt</code> into Ant
 * properties like so:
 * <pre>
 *      &lt;loadproperties srcFile="matches.txt" /&gt;
 * </pre>
 * </p>
 *  
 * @author smeghead
 */
public class MatchTask extends Task {

	private boolean _failOnNoMatch;
	private String  _inputFile;
	private String  _outputFile;
	private String  _regex;
	private int     _regexFlags;

	public void execute() throws BuildException {
		int               charRead    = 0;
		FileReader        fileReader  = null;
		FileWriter        fileWriter  = null;
		Matcher           matcher     = null;
		Pattern           pattern     = null;
		PrintWriter       printWriter = null;
		StringBuffer      text        = new StringBuffer();

		if (_inputFile == null)
			throw new BuildException("Error: 'match' task requires 'input' attribute");

		if (_outputFile == null)
			throw new BuildException("Error: 'match' task requires 'output' attribute");

		if (_regex == null)
			throw new BuildException("Error: 'match' task requires 'regex' attribute");

		pattern = Pattern.compile(_regex, _regexFlags);

		try {
			fileReader = new FileReader(_inputFile);

			while ((charRead = fileReader.read()) != -1)
				text.append((char) charRead);

			fileReader.close();
			matcher = pattern.matcher(text);

			if (matcher.find()) {
				printWriter = new PrintWriter(new FileWriter(_outputFile));

				for (int i = 0; i <= matcher.groupCount(); i++)
					printWriter.println("group." + Integer.toString(i) + "=" + matcher.group(i));

				printWriter.flush();
				printWriter.close();
			} else {
				if (_failOnNoMatch) {
					throw new BuildException("Error: No matches found in " + _inputFile);
				} else {
					System.err.println("Warning: No matches found in " + _inputFile);
					// Create 0 byte output file.
					fileWriter = new FileWriter(_outputFile);
					fileWriter.close();
				}
			}
		} catch (FileNotFoundException fnfe) {
			throw new BuildException("File " + _inputFile + " not found", fnfe);
		} catch (IOException ioe) {
			throw new BuildException(ioe);
		}
	}

	public void setCanonicalEq(boolean enableCanonicalEq) {
		if (enableCanonicalEq)
			_regexFlags |= Pattern.CANON_EQ;
	}

	public void setCaseInsensitive(boolean enableCaseInsensitive) {
		if (enableCaseInsensitive)
			_regexFlags |= Pattern.CASE_INSENSITIVE;
	}

	public void setComments(boolean enableComments) {
		if (enableComments)
			_regexFlags |= Pattern.COMMENTS;
	}

	public void setDotall(boolean enableDotall) {
		if (enableDotall)
			_regexFlags |= Pattern.DOTALL;
	}

	public void setFailOnNoMatch(boolean failOnNoMatch) {
		_failOnNoMatch = failOnNoMatch;
	}

	public void setInput(String inputFile) {
		_inputFile = inputFile;
	}

	public void setMultiLine(boolean enableMultiLine) {
		if (enableMultiLine)
			_regexFlags |= Pattern.MULTILINE;
	}

	public void setOutput(String outputFile) {
		_outputFile = outputFile;
	}

	public void setRegex(String regex) {
		_regex = regex;
	}

	public void setUnicodeCase(boolean enableUnicodeCase) {
		if (enableUnicodeCase)
			_regexFlags |= Pattern.UNICODE_CASE;
	}

	public void setUnixLines(boolean enableUnixLines) {
		if (enableUnixLines)
			_regexFlags |= Pattern.UNIX_LINES;
	}
}
