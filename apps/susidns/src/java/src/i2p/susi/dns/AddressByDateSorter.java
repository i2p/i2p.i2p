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

import java.io.Serializable;
import java.util.Comparator;

/**
 *  Newest first, then alphabetical
 *  @since 0.9.66
 */
public class AddressByDateSorter implements Comparator<AddressBean>, Serializable
{
	public int compare(AddressBean a, AddressBean b)
	{
		String ad = a.getProp("a");
		String bd = b.getProp("a");
		long al;
		long bl;
		if (ad.length() > 0)
			al = Long.parseLong(ad);
		else
			al = 0;
		if (bd.length() > 0)
			bl = Long.parseLong(bd);
		else
			bl = 0;
		if (al < bl)
			return 1;
		if (al > bl)
			return -1;
		return a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
	}	
}
