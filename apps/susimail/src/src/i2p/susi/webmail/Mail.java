/*
 * Created on Nov 9, 2004
 * 
 *  This file is part of susimail project, see http://susi.i2p/
 *  
 *  Copyright (C) 2004-2005  <susi23@mail.i2p>
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
package i2p.susi.webmail;

import i2p.susi.util.Config;
import i2p.susi.util.ReadBuffer;
import i2p.susi.webmail.encoding.Encoding;
import i2p.susi.webmail.encoding.EncodingFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import net.i2p.I2PAppContext;

/**
 * data structure to hold a single message, mostly used with folder view and sorting
 * 
 * @author susi
 */
public class Mail {
	
	public static final String DATEFORMAT = "date.format";
	
	public static final String unknown = "unknown";

	public int id, size;
	public String sender, reply, subject, dateString,
		formattedSender, formattedSubject,
		formattedDate,  // US Locale, UTC
		localFormattedDate,  // Current Locale, local time zone
		shortSender, shortSubject,
		quotedDate,  // Current Locale, local time zone, longer format
		uidl;
	public Date date;
	public ReadBuffer header, body;
	public MailPart part;
	String[] to, cc;

	public String error;

	public boolean markForDeletion;

	public boolean deleted;
	
	public Mail() {
		formattedSender = unknown;
		formattedSubject = unknown;
		formattedDate = unknown;
		localFormattedDate = unknown;
		shortSender = unknown;
		shortSubject = unknown;
		quotedDate = unknown;
		error = "";
	}
	/**
	 * 
	 * @param address E-mail address to be validated
	 * @return Is the e-mail address valid?
	 */
	public static boolean validateAddress( String address )
	{
		if( address == null || address.length() == 0 )
			return false;
		
		address = address.trim();
		
		if( address.indexOf( "\n" ) != -1 ||
				address.indexOf( "\r" ) != -1 )
			return false;
		
		String[] tokens = address.split( "[ \t]+" );

		int addresses = 0;
		
		for( int i = 0; i < tokens.length; i++ ) {
			if( tokens[i].matches( "^[^@< \t]+@[^> \t]+$" ) ||
					tokens[i].matches( "^<[^@< \t]+@[^> \t]+>$" ) )
				addresses++;
		}
		return addresses == 1;
	}
	/**
	 * @param address
	 */
	public static String getAddress(String address )
	{
		String[] tokens = address.split( "[ \t]+" );

		for( int i = 0; i < tokens.length; i++ ) {
			if( tokens[i].matches( "^[^@< \t]+@[^> \t]+$" ) )
				return "<" + tokens[i] + ">";
			if( tokens[i].matches( "^<[^@< \t]+@[^> \t]+>$" ) )
				return tokens[i];
		}
		
		return null;
	}
	public static boolean getRecipientsFromList( ArrayList<String> recipients, String text, boolean ok )
	{
		if( text != null && text.length() > 0 ) {			
			String[] ccs = text.split( "," );
			for( int i = 0; i < ccs.length; i++ ) {
				String recipient = ccs[i].trim();
				if( validateAddress( recipient ) ) {
					String str = getAddress( recipient );
					if( str != null && str.length() > 0 ) {
						recipients.add( str );
					}
					else {
						ok = false;
					}
				}
				else {
					ok = false;
				}
			}
		}
		return ok;
	}
	public static void appendRecipients( StringBuilder buf, ArrayList<String> recipients, String prefix )
	{
		for( String recipient : recipients ) {
			buf.append( prefix );
			prefix ="\t";
			buf.append( recipient );
			buf.append( "\r\n" );
		}
	}
	public void parseHeaders()
	{
		DateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");
		DateFormat localDateFormatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
		DateFormat longLocalDateFormatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);
		// the router sets the JVM time zone to UTC but saves the original here so we can get it
		String systemTimeZone = I2PAppContext.getGlobalContext().getProperty("i2p.systemTimeZone");
		if (systemTimeZone != null) {
			TimeZone tz = TimeZone.getTimeZone(systemTimeZone);
			localDateFormatter.setTimeZone(tz);
			longLocalDateFormatter.setTimeZone(tz);
		}
		DateFormat mailDateFormatter = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH );
		
		error = "";
		if( header != null ) {

			boolean ok = true;
			
			Encoding html = EncodingFactory.getEncoding( "HTML" );
			
			if( html == null ) {
				error += "HTML encoder not found.<br>";
				ok = false;
			}
			
			Encoding hl = EncodingFactory.getEncoding( "HEADERLINE" );

			if( hl == null ) {
				error += "Header line encoder not found.<br>";
				ok = false;
			}

			if( ok ) {
				
				try {
					ReadBuffer decoded = hl.decode( header );
					BufferedReader reader = new BufferedReader( new InputStreamReader( new ByteArrayInputStream( decoded.content, decoded.offset, decoded.length ), "UTF-8" ) );
					String line;
					while( ( line = reader.readLine() ) != null ) {
						if( line.length() == 0 )
							break;

						if( line.startsWith( "From:" ) ) {
							sender = line.substring( 5 ).trim();
							formattedSender = getAddress( sender );
							shortSender = formattedSender.trim();
							if( shortSender.length() > 40 ) {
								shortSender = shortSender.substring( 0, 37 ).trim() + "&hellip;";
							}
							shortSender = html.encode( shortSender );
						}
						else if( line.startsWith( "Date:" ) ) {
							dateString = line.substring( 5 ).trim();
							try {
								date = mailDateFormatter.parse( dateString );
								formattedDate = dateFormatter.format( date );
								localFormattedDate = localDateFormatter.format( date );
								//quotedDate = html.encode( dateString );
								quotedDate = longLocalDateFormatter.format(date);
							}
							catch (ParseException e) {
								date = null;
								e.printStackTrace();
							}
						}
						else if( line.startsWith( "Subject:" ) ) {
							subject = line.substring( 8 ).trim();
							formattedSubject = subject;
							shortSubject = formattedSubject;
							if( formattedSubject.length() > 60 )
								shortSubject = formattedSubject.substring( 0, 57 ).trim() + "&hellip;";
							shortSubject = html.encode( shortSubject );
						}
						else if( line.toLowerCase(Locale.US).startsWith( "reply-to:" ) ) {
							reply = getAddress( line.substring( 9 ).trim() );
						}
						else if( line.startsWith( "To:" ) ) {
							ArrayList<String> list = new ArrayList<String>();
							getRecipientsFromList( list, line.substring( 3 ).trim(), true );
							to = list.toArray(new String[list.size()]);
						}
						else if( line.startsWith( "Cc:" ) ) {
							ArrayList<String> list = new ArrayList<String>();
							getRecipientsFromList( list, line.substring( 3 ).trim(), true );
							cc = list.toArray(new String[list.size()]);
						}
					}
				}
				catch( Exception e ) {
					error += "Error parsing mail header: " + e.getClass().getName() + "<br>";
				}		
			}
		}
	}
}

