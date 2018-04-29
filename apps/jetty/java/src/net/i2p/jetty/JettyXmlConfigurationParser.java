package net.i2p.jetty;

//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

import java.io.IOException;
import java.io.File;
import java.io.Writer;
import java.net.URL;
import java.util.Locale;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.security.Password;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.eclipse.jetty.xml.XmlParser;
import org.eclipse.jetty.xml.XmlParser.Attribute;
import org.eclipse.jetty.xml.XmlParser.Node;
import org.xml.sax.SAXException;

/**
 *  Parses a Jetty XML configuration file.
 *  Copied from Jetty XmlConfiguration.java, where the parser is private.
 *
 *  @since 0.9.35
 */
public class JettyXmlConfigurationParser
{
    private static XmlParser initParser()
    {
        XmlParser parser = new XmlParser();
        URL config60 = Loader.getResource(XmlConfiguration.class, "org/eclipse/jetty/xml/configure_6_0.dtd");
        URL config76 = Loader.getResource(XmlConfiguration.class,"org/eclipse/jetty/xml/configure_7_6.dtd");
        URL config90 = Loader.getResource(XmlConfiguration.class,"org/eclipse/jetty/xml/configure_9_0.dtd");
        parser.redirectEntity("configure.dtd",config90);
        parser.redirectEntity("configure_1_0.dtd",config60);
        parser.redirectEntity("configure_1_1.dtd",config60);
        parser.redirectEntity("configure_1_2.dtd",config60);
        parser.redirectEntity("configure_1_3.dtd",config60);
        parser.redirectEntity("configure_6_0.dtd",config60);
        parser.redirectEntity("configure_7_6.dtd",config76);
        parser.redirectEntity("configure_9_0.dtd",config90);

        parser.redirectEntity("http://jetty.mortbay.org/configure.dtd",config90);
        parser.redirectEntity("http://jetty.eclipse.org/configure.dtd",config90);
        parser.redirectEntity("http://www.eclipse.org/jetty/configure.dtd",config90);

        parser.redirectEntity("-//Mort Bay Consulting//DTD Configure//EN",config90);
        parser.redirectEntity("-//Jetty//Configure//EN",config90);

        return parser;
    }

    /**
     * Reads and parses the XML configuration file.
     *
     * @param f an XML configuration file
     * @throws IOException if the configuration could not be read
     * @throws SAXException if the configuration could not be parsed
     */
    public static XmlParser.Node parse(File f) throws SAXException, IOException {
        // we don't expect to need this very often,
        // so just make a new parser every time
        return initParser().parse(f);
    }

    /**
     *  Recursively go through the entire tree starting at node.
     *  Return the value for the first node with the name set,
     *  e.g. [Set name="name"]value[/Set]
     *  @param name case insensitive
     */
    public static String getValue(Node node, String name) {
        String nameLC = name.toLowerCase(Locale.US);
        for (Object o : node) {
            if (!(o instanceof Node))
                continue;
            Node n = (Node) o;
            String tag = n.getTag();
            if (tag != null && "set".equals(tag.toLowerCase(Locale.US))) {
                String aname = n.getAttribute("name");
                if (aname != null && aname.toLowerCase(Locale.US).equals(nameLC))
                    return n.toString(false);
            } else {
                String rv = getValue(n, name);
                if (rv != null)
                    return rv;
            }
        }
        return null;
    }

    /**
     *  Recursively go through the entire tree starting at node.
     *  Return the value for the first node with the name set,
     *  e.g. [Set name="name"]value[/Set]
     *  @param name case insensitive
     *  @return success
     */
    public static boolean setValue(Node node, String name, String value) {
        String nameLC = name.toLowerCase(Locale.US);
        for (Object o : node) {
            if (!(o instanceof Node))
                continue;
            Node n = (Node) o;
            String tag = n.getTag();
            if (tag != null && "set".equals(tag.toLowerCase(Locale.US))) {
                String aname = n.getAttribute("name");
                if (aname != null && aname.toLowerCase(Locale.US).equals(nameLC)) {
                    // Node doesn't support set() or remove() but it does have clear()
                    n.clear();
                    // work around bug in XmlParser.Node.add(int, Object)
                    // where it will AIOOBE when calling add(String) after clear() after add(String)
                    // because the _lastString field isn't reset to false
                    // so we need to add a non-String object and then clear again.
                    n.add(Integer.valueOf(0));
                    n.clear();
                    n.add(value);
                    return true;
                }
            } else {
                boolean rv = setValue(n, name, value);
                if (rv)
                    return rv;
            }
        }
        return false;
    }

    /**
     *  Write out the XML.
     *  Adapted from Node.toString().
     *  That synchronized method caused classpath issues when called from the webapp.
     *  Also add newlines here for readability.
     */
    public static void write(Node node, Writer out) throws IOException {
        out.write('<');
        String tag = node.getTag();
        out.write(tag);

        Attribute[] attrs = node.getAttributes();
        if (attrs != null) {
            for (int i = 0; i < attrs.length; i++) {
                out.write(' ');
                out.write(attrs[i].getName());
                out.write("=\"");
                out.write(attrs[i].getValue());
                out.write('"');
            }
        }

        int size = node.size();
        if (size > 0) {
            out.write(">");
            for (int i = 0; i < size; i++) {
                Object o = node.get(i);
                if (o == null)
                    continue;
                if (o instanceof Node) {
                    write((Node) o, out);
                } else {
                    out.write(o.toString());
                }
            }
            out.write("</");
            out.write(tag);
            out.write(">\n");
        } else {
            out.write("/>\n");
        }
    }

    /**
     *  Obfuscate a password for storage in the XML
     *  @return a string starting with "OBF:"
     */
    public static String obfuscate(String s) {
        if (s.startsWith("OBF:"))
            return s;
        return Password.obfuscate(s);
    }

    /**
     *  De-Obfuscate a password from the XML
     *  @param s a string starting with "OBF:"
     */
    public static String deobfuscate(String s) {
        if (!s.startsWith("OBF:"))
            return s;
        return Password.deobfuscate(s);
    }
}
