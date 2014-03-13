package org.mortbay.util;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 *  A multi valued Map.
 *  Simple I2P replacement for org.eclipse.jetty.util.MultiMap
 *  so we don't depend on Jetty utils.
 *
 *  Contains only the methods required by MultiPartRequest.
 *  Does not implement Map. Unsynchronized.
 *
 *  @since 0.9.12
 */
public class MultiMap<T>
{
	private final HashMap<T, LinkedList<Object>> data;
     
	public MultiMap(int capacity)
	{
		data = new HashMap<T, LinkedList<Object>>(capacity);
	}

	public Set<T> keySet()
	{
		return data.keySet();
	}

	/**
	 *  This returns the first item or null.
	 *  The Jetty version appears to return the item if only one,
	 *  or the entire list if more than one.
	 *  Only used by MultiPartRequest.contains() which is unused.
	 *  contains() would fail with a ClassCastException if we returned a list here,
	 *  which is a bug in MultiPartRequest?
	 */
	public Object get(T key)
	{
		List<Object> tmp = getValues(key);
		
		return tmp != null ? tmp.get( 0 ) : null;
	}

	public List<Object> getValues(T key)
	{
		return data.get( key );
	}

	public void add(T key, Object value )
	{
		LinkedList<Object> list = data.get( key );
		
		if( list == null ) {
			list = new LinkedList<Object>();
			data.put( key, list );
		}
		
		list.add( value );
	}
}
