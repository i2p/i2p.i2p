/*
 * Created on Nov 23, 2004
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
 *  $Revision: 1.2 $
 */
package i2p.susi.util;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.Iterator;

/**
 * Folder object manages a array Object[] to support
 * paging and sorting.
 * 
 * You create a folder object, set the contents with setElements(),
 * add Comparators with addSorter(), choose one with sortBy() and
 * and then fetch the content of the current page with
 * currentPageIterator().
 * 
 * @author susi
 */
public class Folder {
	
	public static final String PAGESIZE = "pager.pagesize";
	public static final int DEFAULT_PAGESIZE = 10;

	public static final boolean DOWN = false;
	public static final boolean UP = true;

	private int pages, pageSize, currentPage;
	private Object[] unsortedElements, elements;
	private Hashtable sorter;
	private boolean sortingDirection;
	Comparator currentSorter;
	
	public Folder()
	{
		pages = 1;
		pageSize = 0;
		currentPage = 1;
		unsortedElements = null;
		sorter = new Hashtable();
		sortingDirection = UP;
		currentSorter = null;
	}
	
	/**
	 * Returns the current page.
	 * 
	 * @return Returns the current page.
	 */
	public int getCurrentPage() {
		return currentPage;
	}

	/**
	 * Sets the current page to the given parameter.
	 * 
	 * @param currentPage The current page to set.
	 */
	public void setCurrentPage(int currentPage) {
		if( currentPage >= 1 && currentPage <= pages )
			this.currentPage = currentPage;
	}

	/**
	 * Returns the size of the folder.
	 * 
	 * @return Returns the size of the folder.
	 */
	public int getSize() {
		return elements != null ? elements.length : 0;
	}

	/**
	 * Returns the number of pages in the folder.
	 * @return Returns the number of pages.
	 */
	public int getPages() {
		return pages;
	}

	/**
	 * Returns page size. If no page size has been set, it returns property @link PAGESIZE.
	 * If no property is set @link DEFAULT_PAGESIZE is returned.
	 * 
	 * @return Returns the pageSize.
	 */
	public int getPageSize() {
		return pageSize > 0 ? pageSize : Config.getProperty( PAGESIZE, DEFAULT_PAGESIZE );
	}

	/**
	 * Set page size.
	 * 
	 * @param pageSize The page size to set.
	 */
	public void setPageSize(int pageSize) {
		if( pageSize > 0 )
			this.pageSize = pageSize;
		update();
	}

	/**
	 * Creates a copy of an array by copying its elements.
	 * 
	 * @param source Array to copy.
	 * @return Copy of source.
	 */
	private Object[] copyArray( Object[] source )
	{
		Object[] destination = new Object[source.length];
		for( int i = 0; i < source.length; i++ )
			destination[i] = source[i];
		return destination;
	}
	/**
	 * Recalculates variables.
	 */
	private void update() {
		if( elements != null ) {
			pages = elements.length / getPageSize();
			if( pages * getPageSize() < elements.length )
				pages++;
			if( currentPage > pages )
				currentPage = pages;
		}
		else {
			pages = 1;
			currentPage = 1;
		}
	}

	/**
	 * Sorts the elements according the order given by @link addSorter()
	 * and @link sortBy().
	 */
	private void sort()
	{
		if( currentSorter != null ) {
			elements = copyArray( unsortedElements );
			Arrays.sort( elements, currentSorter );
		}
		else {
			elements = unsortedElements;
		}
	}
	
	/**
	 * Set the array of objects the folder should manage.
	 * 
	 * @param elements Array of Objects.
	 */
	public void setElements( Object[] elements )
	{
		this.unsortedElements = elements;
		if( currentSorter != null )
			sort();
		else
			this.elements = elements;
		update();
	}
	
	/**
	 * Returns an iterator containing the elements on the current page.
	 * @return Iterator containing the elements on the current page.
	 */
	public Iterator currentPageIterator()
	{
		ArrayList list = new ArrayList();
		if( elements != null ) {
			int pageSize = getPageSize();
			int offset = ( currentPage - 1 ) * pageSize;
			int step = 1;
			if( sortingDirection == DOWN ) {
				offset = elements.length - offset - 1;
				step = -1;
			}
			for( int i = 0; i < pageSize && offset >= 0 && offset < elements.length; i++ ) {
				list.add( elements[offset] );
				offset += step;
			}			
		}
		return list.iterator();
	}

	/**
	 * Turns folder to next page.
	 */
	public void nextPage()
	{
		currentPage++;
		if( currentPage > pages )
			currentPage = pages;
	}
	
	/**
	 * Turns folder to previous page.
	 */
	public void previousPage()
	{
		currentPage--;
		if( currentPage < 1 )
			currentPage = 1;		
	}
	
	/**
	 * Sets folder to display first page.
	 */
	public void firstPage()
	{
		currentPage = 1;
	}
	
	/**
	 * Sets folder to display last page.
	 */
	public void lastPage()
	{
		currentPage = pages;
	}
	
	/**
	 * Adds a new sorter to the folder. You can sort the folder by
	 * calling sortBy() and choose the given id there.
	 * 
	 * @param id ID to identify the Comparator with @link sortBy()
	 * @param sorter a Comparator to sort the Array given by @link setElements()
	 */
	public void addSorter( String id, Comparator sorter )
	{
		this.sorter.put( id, sorter );
	}
	
	/**
	 * Activates sorting by the choosen Comparator. The id must
	 * match the one, which the Comparator has been stored in the
	 * folder with @link addSorter().
	 * 
	 * @param id ID to identify the Comparator stored with @link addSorter()
	 */
	public void sortBy( String id )
	{
		currentSorter = (Comparator)sorter.get( id );
		sort();
	}
	
	/**
	 * Returns the element on the current page on the given position.
	 *
	 * @param x Position of the element on the current page.
	 * @return Element on the current page on the given position.
	 */
	public Object getElementAtPosXonCurrentPage( int x )
	{
		Object result = null;
		if( elements != null ) {
			int pageSize = getPageSize();
			int offset = ( currentPage - 1 ) * pageSize;
			int step = 1;
			if( sortingDirection == DOWN ) {
				offset = elements.length - offset - 1;
				step = -1;
			}
			offset += x * step;			
			if( offset >= 0 && offset < elements.length )
				result = elements[offset];
		}
		return result;
	}

	/**
	 * Sets the sorting direction of the folder.
	 * 
	 * @param direction @link UP or @link DOWN
	 */
	public void setSortingDirection( boolean direction )
	{
		sortingDirection = direction;
	}
	
	/**
	 * Returns the first element of the sorted folder.
	 * 
	 * @return First element.
	 */
	public Object getFirstElement()
	{
		/*
		 * sorting direction is taken into account from getElement
		 */
		return elements == null ? null : getElement( 0 );
	}

	/**
	 * Returns the last element of the sorted folder.
	 * 
	 * @return Last element.
	 */
	public Object getLastElement()
	{
		/*
		 * sorting direction is taken into account from getElement
		 */
		return elements == null ? null : getElement(  elements.length - 1 );
	}
	
	/**
	 * Gets index of an element in the array regardless of sorting direction.
	 * 
	 * @param element
	 * @return index
	 */
	private int getIndexOf( Object element )
	{
		if( elements != null ) {
			for( int i = 0; i < elements.length; i++ )
				if( elements[i].equals( element ) )
					return i;
		}
		return -1;
	}
	
	/**
	 * Retrieves the next element in the sorted array.
	 * Sorting direction is taken into account.
	 * 
	 * @param element
	 * @return The next element
	 */
	public Object getNextElement( Object element )
	{
		Object result = null;
		
		int i = getIndexOf( element );

		if( i != -1 && elements != null ) {
			i += sortingDirection == UP ? 1 : -1;
			if( i >= 0 && i < elements.length )
				result = elements[i];
		}
		return result;
	}
	
	/**
	 * Retrieves the previous element in the sorted array.
	 * Sorting direction is taken into account.
	 * 
	 * @param element
	 * @return The previous element
	 */
	public Object getPreviousElement( Object element )
	{
		Object result = null;
		
		int i = getIndexOf( element );

		if( i != -1 && elements != null ) {
			i += sortingDirection == DOWN ? 1 : -1;
			if( i >= 0 && i < elements.length )
				result = elements[i];
		}
		return result;
	}
	/**
	 * Retrieves element at index i. Depends on sorting direction.
	 * 
	 * @param i
	 * @return Element at index i
	 */
	private Object getElement( int i )
	{
		Object result = null;
		
		if( elements != null ) {
			if( sortingDirection == DOWN )
				i = elements.length - i - 1;
			result = elements[i];
		}
		return result;
	}
	
	/**
	 * Returns true, if folder shows points to the last page.
	 */
	public boolean isLastPage()
	{
		return currentPage == pages;
	}
	
	/**
	 * Returns true, if folder shows points to the first page.
	 */
	public boolean isFirstPage()
	{
		return currentPage == 1;
	}

	/**
	 * Returns true, if elements.equals( lastElementOfTheSortedArray ).
	 * The sorting direction influences which element is taken for comparison.
	 * 
	 * @param element
	 */
	public boolean isLastElement( Object element )
	{
		if( elements == null )
			return false;
		return elements[ sortingDirection == DOWN ? 0 : elements.length - 1 ].equals( element );
	}
	
	/**
	 * Returns true, if elements.equals( firstElementOfTheSortedArray ).
	 * The sorting direction influences which element is taken for comparison.
	 * 
	 * @param element
	 */
	public boolean isFirstElement( Object element )
	{
		if( elements == null )
			return false;
		return elements[ sortingDirection == UP ? 0 : elements.length - 1 ].equals( element );
	}
}
