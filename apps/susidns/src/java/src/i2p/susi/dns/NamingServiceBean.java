/*
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
 */

package i2p.susi.dns;

import java.net.IDN;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.NamingService;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;

/**
 *  Talk to the NamingService API instead of modifying the hosts.txt files directly,
 *  except for the 'published' addressbook.
 *
 *  @since 0.8.7
 */
public class NamingServiceBean extends AddressbookBean
{
	private static final String DEFAULT_NS = "BlockfileNamingService";
	private String detail;

	private boolean isDirect() {
		return getBook().equals("published");
	}

	@Override
	protected boolean isPrefiltered() {
		if (isDirect())
			return super.isPrefiltered();
		return (search == null || search.length() <= 0) &&
		       (filter == null || filter.length() <= 0) &&
		       getNamingService().getName().equals(DEFAULT_NS);
	}

	@Override
	protected int resultSize() {
		if (isDirect())
			return super.resultSize();
		return isPrefiltered() ? totalSize() : entries.length;
	}

	@Override
	protected int totalSize() {
		if (isDirect())
			return super.totalSize();
		// only blockfile needs the list property
		Properties props = new Properties();
		props.setProperty("list", getFileName());
		return getNamingService().size(props);
	}

	@Override
	public boolean isNotEmpty()
	{
		if (isDirect())
			return super.isNotEmpty();
		return totalSize() > 0;
	}

	@Override
	public String getFileName()
	{
		if (isDirect())
			return super.getFileName();
		loadConfig();
		String filename = properties.getProperty( getBook() + "_addressbook" );
		return basename(filename);
	}

	@Override
	public String getDisplayName()
	{
		if (isDirect())
			return super.getDisplayName();
		loadConfig();
		return _("{0} address book in {1} database", getFileName(), getNamingService().getName());
	}

	/** depth-first search */
	private static NamingService searchNamingService(NamingService ns, String srch)
	{
		String name = ns.getName();
		if (name.equals(srch) || basename(name).equals(srch) || name.equals(DEFAULT_NS))
			return ns;
		List<NamingService> list = ns.getNamingServices();
		if (list != null) {
			for (NamingService nss : list) {
				NamingService rv = searchNamingService(nss, srch);
				if (rv != null)
					return rv;
			}
		}
		return null;		
	}

	private static String basename(String filename) {
		int slash = filename.lastIndexOf('/');
		if (slash >= 0)
			filename = filename.substring(slash + 1);
		return filename;
	}

	/** @return the NamingService for the current file name, or the root NamingService */
	private NamingService getNamingService()
	{
		NamingService root = I2PAppContext.getGlobalContext().namingService();
		NamingService rv = searchNamingService(root, getFileName());		
		return rv != null ? rv : root;		
	}

	/**
	 *  Load addressbook and apply filter, returning messages about this.
	 *  To control memory, don't load the whole addressbook if we can help it...
	 *  only load what is searched for.
	 */
	@Override
	public String getLoadBookMessages()
	{
		if (isDirect())
			return super.getLoadBookMessages();
		NamingService service = getNamingService();
		Debug.debug("Searching within " + service + " with filename=" + getFileName() + " and with filter=" + filter + " and with search=" + search);
		String message = "";
		try {
			LinkedList<AddressBean> list = new LinkedList();
			Map<String, Destination> results;
			Properties searchProps = new Properties();
			// only blockfile needs this
			searchProps.setProperty("list", getFileName());
			if (filter != null) {
				String startsAt = filter.equals("0-9") ? "[0-9]" : filter;
				searchProps.setProperty("startsWith", startsAt);
			}
			if (isPrefiltered()) {
				// Only limit if we not searching or filtering, so we will
				// know the total number of results
				if (beginIndex > 0)
					searchProps.setProperty("skip", Integer.toString(beginIndex));
				int limit = 1 + endIndex - beginIndex;
				if (limit > 0)
					searchProps.setProperty("limit", Integer.toString(limit));
			}
			if (search != null && search.length() > 0)
				searchProps.setProperty("search", search.toLowerCase());
			results = service.getEntries(searchProps);

			Debug.debug("Result count: " + results.size());
			for (Map.Entry<String, Destination> entry : results.entrySet()) {
				String name = entry.getKey();
				if( filter != null && filter.length() > 0 ) {
					if( filter.compareTo( "0-9" ) == 0 ) {
						char first = name.charAt(0);
						if( first < '0' || first > '9' )
							continue;
					}
					else if( ! name.toLowerCase().startsWith( filter.toLowerCase() ) ) {
						continue;
					}
				}
				if( search != null && search.length() > 0 ) {
					if( name.indexOf( search ) == -1 ) {
						continue;
					}
				}
				String destination = entry.getValue().toBase64();
				if (destination != null) {
					list.addLast( new AddressBean( name, destination ) );
				} else {
					// delete it too?
					System.err.println("Bad entry " + name + " in database " + service.getName());
				}
			}
			AddressBean array[] = list.toArray(new AddressBean[list.size()]);
			Arrays.sort( array, sorter );
			entries = array;

			message = generateLoadMessage();
		}
		catch (Exception e) {
			Debug.debug( e.getClass().getName() + ": " + e.getMessage() );
		}
		if( message.length() > 0 )
			message = "<p>" + message + "</p>";
		return message;
	}

	/** Perform actions, returning messages about this. */
	@Override
	public String getMessages()
	{
		if (isDirect())
			return super.getMessages();
		// Loading config and addressbook moved into getLoadBookMessages()
		String message = "";
		
		if( action != null ) {
			Properties nsOptions = new Properties();
			// only blockfile needs this
			nsOptions.setProperty("list", getFileName());
			if( lastSerial != null && serial != null && serial.compareTo( lastSerial ) == 0 ) {
				boolean changed = false;
				if (action.equals(_("Add")) || action.equals(_("Replace"))) {
					if(hostname != null && destination != null) {
						try {
							// throws IAE with translated message
							String host = AddressBean.toASCII(hostname);
							String displayHost = host.equals(hostname) ? hostname :
							                                             hostname + " (" + host + ')';

							Properties outProperties= new Properties();
							Destination oldDest = getNamingService().lookup(host, nsOptions, outProperties);
							if (oldDest != null && destination.equals(oldDest.toBase64())) {
								message = _("Host name {0} is already in address book, unchanged.", displayHost);
							} else if (oldDest != null && !action.equals(_("Replace"))) {
								message = _("Host name {0} is already in address book with a different destination. Click \"Replace\" to overwrite.", displayHost);
							} else {
								try {
									Destination dest = new Destination(destination);
									if (oldDest != null) {
										nsOptions.putAll(outProperties);
							                        nsOptions.setProperty("m", Long.toString(I2PAppContext.getGlobalContext().clock().now()));
									}
						                        nsOptions.setProperty("s", _("Manually added via SusiDNS"));
									boolean success = getNamingService().put(host, dest, nsOptions);
									if (success) {
										changed = true;
										if (oldDest == null)
											message = _("Destination added for {0}.", displayHost);
										else
											message = _("Destination changed for {0}.", displayHost);
										if (!host.endsWith(".i2p"))
											message += "<br>" + _("Warning - host name does not end with \".i2p\"");
										// clear form
										hostname = null;
										destination = null;
									} else {
										message = _("Failed to add Destination for {0} to naming service {1}", displayHost, getNamingService().getName()) + "<br>";
									}
								} catch (DataFormatException dfe) {
									message = _("Invalid Base 64 destination.");
								}
							}
						} catch (IllegalArgumentException iae) {
							message = iae.getMessage();
							if (message == null)
								message = _("Invalid host name \"{0}\".", hostname);
						}
					} else {
						message = _("Please enter a host name and destination");
					}
					// clear search when adding
					search = null;
				} else if (action.equals(_("Delete Selected")) || action.equals(_("Delete Entry"))) {
					String name = null;
					int deleted = 0;
					for (String n : deletionMarks) {
						boolean success = getNamingService().remove(n, nsOptions);
						String uni = AddressBean.toUnicode(n);
						String displayHost = uni.equals(n) ? n :  uni + " (" + n + ')';
						if (!success) {
							message += _("Failed to delete Destination for {0} from naming service {1}", displayHost, getNamingService().getName()) + "<br>";
						} else if (deleted++ == 0) {
							changed = true;
							name = displayHost;
						}
					}
					if( changed ) {
						if (deleted == 1)
							// parameter is a host name
							message += _("Destination {0} deleted.", name);
						else
							// parameter will always be >= 2
							message = ngettext("1 destination deleted.", "{0} destinations deleted.", deleted);
					} else {
						message = _("No entries selected to delete.");
					}
					// clear search when deleting
					if (action.equals(_("Delete Entry")))
						search = null;
				}
				if( changed ) {
					message += "<br>" + _("Address book saved.");
				}
			}			
			else {
				message = _("Invalid form submission, probably because you used the \"back\" or \"reload\" button on your browser. Please resubmit.");
			}
		}
		
		action = null;
		
		if( message.length() > 0 )
			message = "<p class=\"messages\">" + message + "</p>";
		return message;
	}

	public void setH(String h) {
		this.detail = h;
	}

	public AddressBean getLookup() {
		if (this.detail == null)
			return null;
		if (isDirect()) {
			// go to some trouble to make this work for the published addressbook
			this.filter = this.detail.substring(0, 1);
			this.search = this.detail;
			// we don't want the messages, we just want to populate entries
			super.getLoadBookMessages();
			for (int i = 0; i < this.entries.length; i++) {
				if (this.search.equals(this.entries[i].getName()))
					return this.entries[i];
			}
			return null;
		}
		Properties nsOptions = new Properties();
		Properties outProps = new Properties();
		nsOptions.setProperty("list", getFileName());
		Destination dest = getNamingService().lookup(this.detail, nsOptions, outProps);
		if (dest == null)
			return null;
		AddressBean rv = new AddressBean(this.detail, dest.toBase64());
		rv.setProperties(outProps);
		return rv;
	}
}
