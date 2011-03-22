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

import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.Base64;

public class AddressBean
{
	private String name, destination;

	public AddressBean()
	{
		
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

	public void setDestination(String destination)
	{
		this.destination = destination;
	}

	public String getName()
	{
		return name;
	}

	public void setName(String name)
	{
		this.name = name;
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
}
