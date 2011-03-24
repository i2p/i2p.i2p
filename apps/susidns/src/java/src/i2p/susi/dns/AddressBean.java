/*
 * Created on Sep 02, 2005
 * 
 *  This file is part of susidns project, see http://susi.i2p/
 *  
 *  Copyright (C) 2005 <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *  
 * $Revision: 1.1 $
 */

package i2p.susi.dns;

import java.net.IDN;
import java.util.Date;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.Certificate;

public class AddressBean
{
	private final String name, destination;
	private Properties props;
	/** available as of Java 6 */
	static final boolean haveIDN;

	static {
		boolean h;
		try {
			Class.forName("java.net.IDN", false, ClassLoader.getSystemClassLoader());
			h = true;
		} catch (ClassNotFoundException cnfe) {
			h = false;
		}
		haveIDN = h;
	}

	public AddressBean(String name, String destination)
	{
		this.name = name;
		this.destination = destination;
	}

	public String getDestination() 
	{
		return destination;
	}

	/**
	 * The ASCII (Punycode) name
	 */
	public String getName()
	{
		return name;
	}

	/**
	 * The Unicode name, translated from Punycode
	 * @since 0.8.6
	 */
	public String getDisplayName()
	{
		if (haveIDN)
			return IDN.toUnicode(name);
		return name;
	}

	/**
	 * Is the ASCII name Punycode-encoded?
	 * @since 0.8.6
	 */
	public boolean isIDN()
	{
		return haveIDN && !IDN.toUnicode(name).equals(name);
	}

	/** @since 0.8.6 */
	public String getB32() 
	{
		byte[] dest = Base64.decode(destination);
		if (dest == null)
			return "";
		byte[] hash = I2PAppContext.getGlobalContext().sha().calculateHash(dest).getData();
		return Base32.encode(hash) + ".b32.i2p";
	}

	/** @since 0.8.6 */
	public void setProperties(Properties p) {
		props = p;
	}

	/** @since 0.8.6 */
	public String getSource() {
		String rv = getProp("s");
                if (rv.startsWith("http://"))
                    rv = "<a href=\"" + rv + "\">" + rv + "</a>";
		return rv;
	}

	/** @since 0.8.6 */
	public String getAdded() {
		return getDate("a");
	}

	/** @since 0.8.6 */
	public String getModded() {
		return getDate("m");
	}


	/** @since 0.8.6 */
	public String getNotes() {
		return getProp("notes");
	}

	/**
	 * Do this the easy way
	 * @since 0.8.6
	 */
	public String getCert() {
		// (4 / 3) * (pubkey length + signing key length)
		String cert = destination.substring(512);
                if (cert.equals("AAAA"))
			return _("None");
		byte[] enc = Base64.decode(cert);
		if (enc == null)
			// shouldn't happen
			return "invalid";
		int type = enc[0] & 0xff;
		switch (type) {
			case Certificate.CERTIFICATE_TYPE_HASHCASH:
				return _("Hashcash");
			case Certificate.CERTIFICATE_TYPE_HIDDEN:
				return _("Hidden");
			case Certificate.CERTIFICATE_TYPE_SIGNED:
				return _("Signed");
			default:
				return _("Type {0}", type);
		}
	}

	/** @since 0.8.6 */
	private String getProp(String p) {
		if (props == null)
                    return "";
		String rv = props.getProperty(p);
		return rv != null ? rv : "";
	}

	/** @since 0.8.6 */
	private String getDate(String key) {
		String d = getProp(key);
		if (d.length() > 0) {
			try {
				d = FormatDate.format(Long.parseLong(d));
			} catch (NumberFormatException nfe) {}
		}
		return d;
	}

	/** translate */
	private static String _(String s) {
		return Messages.getString(s);
	}

	/** translate */
	private static String _(String s, Object o) {
		return Messages.getString(s, o);
	}
}
