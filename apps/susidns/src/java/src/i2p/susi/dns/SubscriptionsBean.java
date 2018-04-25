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
 * $Revision: 1.3 $
 */

package i2p.susi.dns;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.i2p.data.DataHelper;
import net.i2p.util.PortMapper;
import net.i2p.util.SecureFileOutputStream;

public class SubscriptionsBean extends BaseBean
{
	private String fileName, content;
	private static final String SUBS_FILE = "subscriptions.txt";
	// If you change this, change in Addressbook Daemon also
	private static final String DEFAULT_SUB = "http://i2p-projekt.i2p/hosts.txt";
	
	public String getFileName()
	{
		loadConfig();
		fileName = subsFile().toString();
		return fileName;
	}

	/**
	 * @since 0.9.13
	  */
	private File subsFile() {
		return new File(addressbookDir(), SUBS_FILE);
	}

	private void reloadSubs() {
		synchronized(SubscriptionsBean.class) {
			locked_reloadSubs();
		}
	}

	private void locked_reloadSubs()
	{
		File file = subsFile();
		if(file.isFile()) {
			StringBuilder buf = new StringBuilder();
			BufferedReader br = null;
			try {
				br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
				String line;
				while( ( line = br.readLine() ) != null ) {
					buf.append( line );
					buf.append( "\n" );
				}
				content = buf.toString();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				if (br != null)
					try { br.close(); } catch (IOException ioe) {}
			}
		} else {
			content = DEFAULT_SUB;
		}
	}
	
	private void save() {
		synchronized(SubscriptionsBean.class) {
			locked_save();
		}
	}

	private void locked_save()
	{
		File file = subsFile();
		try {
			// trim and sort
			List<String> urls = new ArrayList<String>();
                        InputStream in = new ByteArrayInputStream(content.getBytes("UTF-8"));
                        String line;
                        while ((line = DataHelper.readLine(in)) != null) {
				line = line.trim();
                                if (line.length() > 0)
                                    urls.add(line);
			}
			Collections.sort(urls);
			PrintWriter out = new PrintWriter(new OutputStreamWriter(new SecureFileOutputStream(file), "UTF-8"));
			for (String url : urls) {
				out.println(url);
			}
			out.close();
                        if (out.checkError())
                            throw new IOException("Failed write to " + file);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public String getMessages() {
		String message = "";
		if( action != null ) {
                        if (_context.getBooleanProperty(PROP_PW_ENABLE) ||
			    (serial != null && serial.equals(lastSerial))) {
				if (action.equals(_t("Save"))) {
					save();
				/*******
					String nonce = System.getProperty("addressbook.nonce");
					if (nonce != null) {	
						// Yes this is a hack.
						// No it doesn't work on a text-mode browser.
						// Fetching from the addressbook servlet
						// with the correct parameters will kick off a
						// config reload and fetch.
				*******/
					if (content != null && content.length() > 2 &&
					    _context.portMapper().isRegistered(PortMapper.SVC_HTTP_PROXY)) {
						message = _t("Subscriptions saved, updating addressbook from subscription sources now.");
						          // + "<img height=\"1\" width=\"1\" alt=\"\" " +
						          // "src=\"/addressbook/?wakeup=1&nonce=" + nonce + "\">";
						_context.namingService().requestUpdate(null);
					} else {
						message = _t("Subscriptions saved.");
					}
				} else if (action.equals(_t("Reload"))) {
					reloadSubs();
					message = _t("Subscriptions reloaded.");
				}
			}			
			else {
				message = _t("Invalid form submission, probably because you used the \"back\" or \"reload\" button on your browser. Please resubmit.")
                                          + ' ' +
                                          _t("If the problem persists, verify that you have cookies enabled in your browser.");
			}
		}
		if( message.length() > 0 )
			message = "<p class=\"messages\">" + message + "</p>";
		return message;
	}

	public void setContent(String content) {
		// will come from form with \r\n line endings
		this.content = DataHelper.stripHTML(content);
	}

	public String getContent()
	{
		if( content != null )
			return content;
		
		reloadSubs();
		
		return content;
	}
}
