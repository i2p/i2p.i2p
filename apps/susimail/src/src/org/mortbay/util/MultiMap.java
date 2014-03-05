package org.mortbay.util;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class MultiMap<T>
{
    HashMap<T, LinkedList<Object>> data;
     
	public MultiMap(int i)
	{
	    data = new HashMap<T, LinkedList<Object>>();
	}

	public Set<T> keySet()
	{
		return data.keySet();
	}

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
