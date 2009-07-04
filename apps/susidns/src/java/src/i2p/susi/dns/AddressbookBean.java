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
 * $Revision: 1.2 $
 */

package i2p.susi.dns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Properties;

public class AddressbookBean
{
	private String book, action, serial, lastSerial, filter, search, hostname, destination;
	private int beginIndex, endIndex;
	private Properties properties, addressbook;
	private int trClass;
	private LinkedList deletionMarks;
	private static Comparator sorter;
	private static final int DISPLAY_SIZE=100;

	static {
		sorter = new AddressByNameSorter();
	}
	public String getSearch() {
		return search;
	}
	public void setSearch(String search) {
		this.search = search;
	}
	public boolean isHasFilter()
	{
		return filter != null && filter.length() > 0;
	}
	public void setTrClass(int trClass) {
		this.trClass = trClass;
	}
	public int getTrClass() {
		trClass = 1 - trClass;
		return trClass;
	}
	public boolean isIsEmpty()
	{
		return ! isNotEmpty();
	}
	public boolean isNotEmpty()
	{
		return addressbook != null && addressbook.size() > 0;
	}
	public AddressbookBean()
	{
		properties = new Properties();
		deletionMarks = new LinkedList();
		beginIndex = 0;
		endIndex = DISPLAY_SIZE - 1;
	}
	private long configLastLoaded = 0;
	private static final String PRIVATE_BOOK = "private_addressbook";
	private static final String DEFAULT_PRIVATE_BOOK = "../privatehosts.txt";
	private void loadConfig()
	{
		long currentTime = System.currentTimeMillis();
		
		if( properties.size() > 0 &&  currentTime - configLastLoaded < 10000 )
			return;
		
		FileInputStream fis = null;
		try {
			properties.clear();
			fis = new FileInputStream( ConfigBean.configFileName );
			properties.load( fis );
			// added in 0.5, for compatibility with 0.4 config.txt
			if( properties.getProperty(PRIVATE_BOOK) == null)
				properties.setProperty(PRIVATE_BOOK, DEFAULT_PRIVATE_BOOK);
			configLastLoaded = currentTime;
		}
		catch (Exception e) {
			Debug.debug( e.getClass().getName() + ": " + e.getMessage() );
		} finally {
			if (fis != null)
				try { fis.close(); } catch (IOException ioe) {}
		}	
	}
	public String getFileName()
	{
		loadConfig();
		String filename = properties.getProperty( getBook() + "_addressbook" );
		// clean up the ../ with getCanonicalPath()
		File path = new File(ConfigBean.addressbookPrefix, filename);
		try {
			return path.getCanonicalPath();
		} catch (IOException ioe) {}
		return filename;
	}
	private Object[] entries;
	public Object[] getEntries()
	{
		return entries;
	}
	public String getAction() {
		return action;
	}
	public void setAction(String action) {
		this.action = action;
	}
	public String getBook()
	{
		if( book == null || ( book.compareToIgnoreCase( "master" ) != 0 &&
				book.compareToIgnoreCase( "router" ) != 0 &&
				book.compareToIgnoreCase( "private" ) != 0 &&
				book.compareToIgnoreCase( "published" ) != 0  ))
			book = "master";
		
		return book;
	}
	public void setBook(String book) {
		this.book = book;
	}
	public String getSerial() {
		lastSerial = "" + Math.random();
		action = null;
		return lastSerial;
	}
	public void setSerial(String serial) {
		this.serial = serial;
	}
	/** Load addressbook and apply filter, returning messages about this. */
	public String getLoadBookMessages()
	{
		// Config and addressbook now loaded here, hence not needed in getMessages()
		loadConfig();
		addressbook = new Properties();
		
		String message = "";
		FileInputStream fis = null;
		try {
			fis =  new FileInputStream( getFileName() );
			addressbook.load( fis );
			LinkedList list = new LinkedList();
			Enumeration e = addressbook.keys();
			while( e.hasMoreElements() ) {
				String name = (String)e.nextElement();
				String destination = addressbook.getProperty( name );
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
				list.addLast( new AddressBean( name, destination ) );
			}
			Object array[] = list.toArray();
			Arrays.sort( array, sorter );
			entries = array;

			// Format a message about filtered addressbook size, and the number of displayed entries
			// addressbook.jsp catches the case where the whole book is empty.
			String filterArg = "";
			if( search != null && search.length() > 0 ) {
				message = "Search ";
			}
			if( filter != null && filter.length() > 0 ) {
				if( search != null && search.length() > 0 )
					message += "within ";
				message += "Filtered list ";
				filterArg = "&filter=" + filter;
			}
			if (entries.length == 0) {
				message += "- no matches";
			} else if (getBeginInt() == 0 && getEndInt() == entries.length - 1) {
				if (message.length() == 0)
					message = "Addressbook ";
				message += "contains " + entries.length + " entries";
			} else {
				if (getBeginInt() > 0) {
					int newBegin = Math.max(0, getBeginInt() - DISPLAY_SIZE);
					int newEnd = Math.max(0, getBeginInt() - 1);
			       		message += "<a href=\"addressbook.jsp?book=" + getBook() + filterArg +
					           "&begin=" + newBegin + "&end=" + newEnd + "\">" + newBegin +
					           '-' + newEnd + "</a> | ";
		       		}
				message += "Showing " + getBegin() + '-' + getEnd() + " of " + entries.length;
				if (getEndInt() < entries.length - 1) {
					int newBegin = Math.min(entries.length - 1, getEndInt() + 1);
					int newEnd = Math.min(entries.length, getEndInt() + DISPLAY_SIZE);
			       		message += " | <a href=\"addressbook.jsp?book=" + getBook() + filterArg +
					           "&begin=" + newBegin + "&end=" + newEnd + "\">" + newBegin +
					           '-' + newEnd + "</a>";
				}
			}
		}
		catch (Exception e) {
			Debug.debug( e.getClass().getName() + ": " + e.getMessage() );
		} finally {
			if (fis != null)
				try { fis.close(); } catch (IOException ioe) {}
		}
		if( message.length() > 0 )
			message = "<p>" + message + "</p>";
		return message;
	}
	/** Perform actions, returning messages about this. */
	public String getMessages()
	{
		// Loading config and addressbook moved into getLoadBookMessages()
		String message = "";
		
		if( action != null ) {
			if( lastSerial != null && serial != null && serial.compareTo( lastSerial ) == 0 ) {
				boolean changed = false;
				if( action.compareToIgnoreCase( "add") == 0 ) {
					if( addressbook != null && hostname != null && destination != null ) {
						addressbook.put( hostname, destination );
						changed = true;
						message += "Destination added.<br/>";
					}
				}
				if( action.compareToIgnoreCase( "delete" ) == 0 ) {
					Iterator it = deletionMarks.iterator();
					int deleted = 0;
					while( it.hasNext() ) {
						String name = (String)it.next();
						addressbook.remove( name );
						changed = true;
						deleted++;
					}
					if( changed ) {
						message += "" + deleted + " destination(s) deleted.<br/>";
					}
				}
				if( changed ) {
					try {
						save();
						message += "Addressbook saved.<br/>";
					} catch (Exception e) {
						Debug.debug( e.getClass().getName() + ": " + e.getMessage() );
						message += "ERROR: Could not write addressbook file.<br/>";
					}
				}
			}			
			else {
				message = "Invalid form submission, probably because you used the 'back' or 'reload' button on your browser. Please resubmit.";
			}
		}
		
		action = null;
		
		if( message.length() > 0 )
			message = "<p class=\"messages\">" + message + "</p>";
		return message;
	}

	private void save() throws IOException
	{
		String filename = properties.getProperty( getBook() + "_addressbook" );
		
		FileOutputStream fos = new FileOutputStream( ConfigBean.addressbookPrefix + filename  );
		addressbook.store( fos, null );
		try {
			fos.close();
		} catch (IOException ioe) {}
	}
	public String getFilter() {
		return filter;
	}

	public boolean isMaster()
	{
		return getBook().compareToIgnoreCase( "master" ) == 0;
	}
	public boolean isRouter()
	{
		return getBook().compareToIgnoreCase( "router" ) == 0;
	}
	public boolean isPublished()
	{
		return getBook().compareToIgnoreCase( "published" ) == 0;
	}
	public boolean isPrivate()
	{
		return getBook().compareToIgnoreCase( "private" ) == 0;
	}
	public void setFilter(String filter) {
		if( filter != null && ( filter.length() == 0 || filter.compareToIgnoreCase( "none" ) == 0 ) ) {
			filter = null;
			search = null;
		}
		this.filter = filter;
	}
	public String getDestination() {
		return destination;
	}
	public void setDestination(String destination) {
		this.destination = destination;
	}
	public String getHostname() {
		return hostname;
	}
	public void setResetDeletionMarks( String dummy ) {
		deletionMarks.clear();
	}
	public void setMarkedForDeletion( String name ) {
		deletionMarks.addLast( name );
	}
	public void setHostname(String hostname) {
		this.hostname = hostname;
	}
	private int getBeginInt() {
		return Math.max(0, Math.min(entries.length - 1, beginIndex));
	}
	public String getBegin() {
		return "" + getBeginInt();
	}
	public void setBegin(String s) {
		try {
			beginIndex = Integer.parseInt(s);
		} catch (NumberFormatException nfe) {}
	}
	private int getEndInt() {
		return Math.max(0, Math.max(getBeginInt(), Math.min(entries.length - 1, endIndex)));
	}
	public String getEnd() {
		return "" + getEndInt();
	}
	public void setEnd(String s) {
		try {
			endIndex = Integer.parseInt(s);
		} catch (NumberFormatException nfe) {}
	}
}
