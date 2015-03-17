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

import i2p.susi.util.ReadBuffer;

/**
 * @author user
 */
public class Attachment {
	private String fileName, contentType, transferEncoding;
	private ReadBuffer buffer;
	private String data;
	/**
	 * @return Returns the fileName.
	 */
	public String getFileName() {
		return fileName;
	}
	/**
	 * @param fileName The fileName to set.
	 */
	public void setFileName(String fileName) {
		this.fileName = fileName;
	}
	/**
	 * @return Returns the buffer.
	 */
	public ReadBuffer getBuffer() {
		return buffer;
	}
	/**
	 * @param buffer The buffer to set.
	 */
	public void setBuffer(ReadBuffer buffer) {
		this.buffer = buffer;
	}

	public String getTransferEncoding() {
		// TODO Auto-generated method stub
		return transferEncoding;
	}

	public String getContentType() {
		// TODO Auto-generated method stub
		return contentType;
	}
	/**
	 * @param contentType The contentType to set.
	 */
	public void setContentType(String contentType) {
		this.contentType = contentType;
	}
	/**
	 * @param transferEncoding The transferEncoding to set.
	 */
	public void setTransferEncoding(String transferEncoding) {
		this.transferEncoding = transferEncoding;
	}

	public void setData(String data ) {
		this.data = data;
	}
	/**
	 * @return Returns the data.
	 */
	public String getData() {
		return data;
	}
}
