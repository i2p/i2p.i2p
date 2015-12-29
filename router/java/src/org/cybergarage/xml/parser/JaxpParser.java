/******************************************************************
*
*	CyberXML for Java
*
*	Copyright (C) Satoshi Konno 2004
*
*   Author: Markus Thurner (http://thoean.com)
*
*	File: JaxpParser.java
*
*	Revision;
*
*	06/15/04
*		- first revision.
*	01/08/08
*		- Fixed parse() not to occur null exception when the NamedNodeMap is null on Android.
*	02/08/08
*		- Change parse() to use Node::addValue() instead of the setValue().
*
******************************************************************/

package org.cybergarage.xml.parser;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.cybergarage.xml.Node;
import org.cybergarage.xml.Parser;
import org.cybergarage.xml.ParserException;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;


public class JaxpParser extends Parser
{

	public JaxpParser()
	{
		super();
	}
	
	////////////////////////////////////////////////
	//	parse (Node)
	////////////////////////////////////////////////

	public org.cybergarage.xml.Node parse(org.cybergarage.xml.Node parentNode, org.w3c.dom.Node domNode, int rank)
	{
		int domNodeType = domNode.getNodeType();
//		if (domNodeType != Node.ELEMENT_NODE)
//			return;
			
		String domNodeName = domNode.getNodeName();
		String domNodeValue = domNode.getNodeValue();
		NamedNodeMap attrs = domNode.getAttributes(); 
		int arrrsLen = (attrs != null) ? attrs.getLength() : 0;

//		Debug.message("[" + rank + "] ELEM : " + domNodeName + ", " + domNodeValue + ", type = " + domNodeType + ", attrs = " + arrrsLen);

		if (domNodeType == org.w3c.dom.Node.TEXT_NODE) {
			// Change to use Node::addValue() instead of the setValue(). (2008/02/07)
			//parentNode.setValue(domNodeValue);
			parentNode.addValue(domNodeValue);
			return parentNode;
		}

		if (domNodeType != org.w3c.dom.Node.ELEMENT_NODE)
			return parentNode;

		org.cybergarage.xml.Node node = new org.cybergarage.xml.Node();
		node.setName(domNodeName);
		node.setValue(domNodeValue);

		if (parentNode != null)
			parentNode.addNode(node);

		NamedNodeMap attrMap = domNode.getAttributes(); 
		if (attrMap != null) {
			int attrLen = attrMap.getLength();
			//Debug.message("attrLen = " + attrLen);
			for (int n = 0; n<attrLen; n++) {
				org.w3c.dom.Node attr = attrMap.item(n);
				String attrName = attr.getNodeName();
				String attrValue = attr.getNodeValue();
				node.setAttribute(attrName, attrValue);
			}
		}
		
		org.w3c.dom.Node child = domNode.getFirstChild();
		if(child==null){ 
			node.setValue(""); 
			return node; 
		}
		do{
			parse(node, child, rank+1);
			child = child.getNextSibling();
		}while (child != null);		
		
		return node;
	}

	public org.cybergarage.xml.Node parse(org.cybergarage.xml.Node parentNode, org.w3c.dom.Node domNode)
	{
		return parse(parentNode, domNode, 0);
	}

	/* (non-Javadoc)
	 * @see org.cybergarage.xml.Parser#parse(java.io.InputStream)
	 */
	public Node parse(InputStream inStream) throws ParserException
	{
		org.cybergarage.xml.Node root = null;
		
		try {
			// https://www.owasp.org/index.php/XML_External_Entity_%28XXE%29_Processing
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setValidating(false);
			factory.setNamespaceAware(true);
			factory.setExpandEntityReferences(false);
			try {
				try {
				    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
				} catch (ParserConfigurationException pce) {}
				try {
				    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
				} catch (ParserConfigurationException pce) {}
				try {
				    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
				} catch (ParserConfigurationException pce) {}
				try {
				    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
				} catch (ParserConfigurationException pce) {}
			} catch (AbstractMethodError ame) {}   // FreeBSD
			DocumentBuilder builder = factory.newDocumentBuilder();
			builder.setEntityResolver(new BlankingResolver());
			InputSource inSrc = new InputSource(new NullFilterInputStream(inStream));
			Document doc = builder.parse(inSrc);

			org.w3c.dom.Element docElem = doc.getDocumentElement();

			if (docElem != null)
				root = parse(root, docElem);
/*
			NodeList rootList = doc.getElementsByTagName("root");
			Debug.message("rootList = " + rootList.getLength());
			
			if (0 < rootList.getLength())
				root = parse(root, rootList.item(0));
*/
		}
		catch (Exception e) {
			throw new ParserException(e);
		}
		
		return root;
	}

	/**
	 *  I2P -
	 *  Filter out nulls, hopefully to avoid
	 *  SAXParserException "Content not allowed in trailing section",
	 *  which is apparently caused by nulls.
	 *  Alternative is to remove all stuff between '>' and '<',
         *  which isn't so hard if we assume no CDATA.
	 */
	private static class NullFilterInputStream extends FilterInputStream {

		public NullFilterInputStream(InputStream is) {
			super(is);
		}

		@Override
		public int read() throws IOException {
			int rv;
			while ((rv = super.read()) == 0) {
				// try again
			}
			return rv;
		}

		/** @since 0.9.22 */
		@Override
		public int read(byte[] b) throws IOException {
			return this.read(b, 0, b.length);
		}

		/** @since 0.9.22 */
		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (b == null) {
				throw new NullPointerException();
			} else if (off < 0 || len < 0 || len > b.length - off) {
				throw new IndexOutOfBoundsException();
			} else if (len == 0) {
				return 0;
			}

			int rv = this.read();
			if (-1 == rv) {
				return -1;
			}

			int i = 1;
			b[off] = (byte) rv;
			for (; i < len; i++) {
				rv = this.read();
				if (-1 == rv) {
					break;
				}
				b[off + i] = (byte) rv;
			}
			return i;
		}
	}

	/**
	 *  I2P -
	 *  http://stackoverflow.com/questions/5883542/disable-xml-validation-based-on-external-dtd-xsd
	 */
	private static class BlankingResolver implements EntityResolver {
                private static final byte[] DUMMY = new byte[0];

		public InputSource resolveEntity(String arg0, String arg1) {
			return new InputSource(new ByteArrayInputStream(DUMMY));
		}
	}
}
