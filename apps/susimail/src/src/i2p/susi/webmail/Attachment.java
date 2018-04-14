/*
 * Created on 01.12.2004
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
 * $Revision: 1.4 $
 */
package i2p.susi.webmail;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author user
 * @since public since 0.9.33, was package private
 */
public class Attachment {
	private final String fileName, contentType, transferEncoding;
	private final File data;

	/**
	 * @param type the content type
	 * @param encoding the transfer encoding, non-null
	 */
	Attachment(String name, String type, String encoding, File data) {
		fileName = name;
		contentType = type;
		transferEncoding = encoding;
		this.data = data;
	}

	/**
	 * @return Returns the fileName.
	 */
	public String getFileName() {
		return fileName;
	}

	/**
	 * @return non-null
	 */
	public String getTransferEncoding() {
		return transferEncoding;
	}

	public String getContentType() {
		return contentType;
	}

	/**
	 * @return Returns the data.
	 */
	public InputStream getData() throws IOException {
		return new FileInputStream(data);
	}

	/**
	 * @return absolute path to the data file
	 * @since 0.9.35
	 */
	public String getPath() {
		return data.getAbsolutePath();
	}

	/**
	 * The unencoded size
	 * @since 0.9.33
	 */
	public long getSize() {
		return data.length();
	}

	/**
	 * Delete the data file
	 * @since 0.9.33
	 */
	public void deleteData() {
		data.delete();
	}
}
