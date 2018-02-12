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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.i2p.data.DataHelper;

/**
 * Folder object manages a array Object[] to support
 * paging and sorting.
 * 
 * You create a folder object, set the contents with setElements(),
 * add Comparators with addSorter(), choose one with setSortBy() and
 * and then fetch the content of the current page with
 * currentPageIterator().
 * 
 * All public methods are synchronized.
 * 
 * @author susi
 */
public class Folder<O extends Object> {
	
	public static final String PAGESIZE = "pager.pagesize";
	public static final int DEFAULT_PAGESIZE = 10;

	public enum SortOrder {
		/** lowest to highest */
		DOWN,
		/** reverse sort, highest  to lowest */
		UP;
	}

	private int pages, pageSize, currentPage;
	private O[] elements;
	private final Map<String, Comparator<O>> sorter;
	private SortOrder sortingDirection;
	private Comparator<O> currentSorter;
	private String currentSortID;
	
	public Folder()
	{
		pages = 1;
		currentPage = 1;
		sorter = new HashMap<String, Comparator<O>>();
		sortingDirection = SortOrder.DOWN;
	}
	
	/**
	 * Returns the current page.
	 * Starts at 1, even if empty.
	 * 
	 * @return Returns the current page.
	 */
	public synchronized int getCurrentPage() {
		return currentPage;
	}

	/**
	 * Sets the current page to the given parameter.
	 * Starts at 1.
	 * 
	 * @param currentPage The current page to set.
	 */
	public synchronized void setCurrentPage(int currentPage) {
		if( currentPage >= 1 && currentPage <= pages )
			this.currentPage = currentPage;
	}

	/**
	 * Returns the size of the folder.
	 * 
	 * @return Returns the size of the folder.
	 */
	public synchronized int getSize() {
		return elements != null ? elements.length : 0;
	}

	/**
	 * Returns the number of pages in the folder.
         * Minimum of 1 even if empty.
	 * @return Returns the number of pages.
	 */
	public synchronized int getPages() {
		return pages;
	}

	/**
	 * Returns page size. If no page size has been set, it returns property @link PAGESIZE.
	 * If no property is set @link DEFAULT_PAGESIZE is returned.
	 * 
	 * @return Returns the pageSize.
	 */
	public synchronized int getPageSize() {
		return pageSize > 0 ? pageSize : Config.getProperty( PAGESIZE, DEFAULT_PAGESIZE );
	}

	/**
	 * Set page size.
	 * 
	 * @param pageSize The page size to set.
	 */
	public synchronized void setPageSize(int pageSize) {
		if( pageSize > 0 )
			this.pageSize = pageSize;
		update();
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
	 * and @link setSortBy().
         *
         * @since public since 0.9.33
	 */
	public synchronized void sort()
	{
		if (currentSorter != null && elements != null && elements.length > 1)
			DataHelper.sort(elements, currentSorter);
	}
	
	/**
	 * Set the array of objects the folder should manage.
	 * Does NOT copy the array.
	 * Sorts the array if a sorter set.
	 * 
	 * @param elements Array of Os.
	 */
	public synchronized void setElements( O[] elements )
	{
		if (elements.length > 0) {
			this.elements = elements;
			sort();
		} else {
			this.elements = null;
		}
		update();
	}

	/**
	 * Remove an element
	 * 
	 * @param element to remove
	 */
	public void removeElement(O element) {
		removeElements(Collections.singleton(element));
	}
	
	/**
	 * Remove elements
	 * 
	 * @param elems to remove
	 */
	@SuppressWarnings("unchecked")
	public synchronized void removeElements(Collection<O> elems) {
		if (elements != null) {
			List<O> list = new ArrayList<O>(Arrays.asList(elements));
			boolean shouldUpdate = false;
			for (O e : elems) {
				if (list.remove(e))
					shouldUpdate = true;
			}
			if (shouldUpdate) {
				elements = (O[]) list.toArray(new Object[list.size()]);
				update();  // will still be sorted
			}
		}
	}

	/**
	 * Add an element only if it does not already exist
	 * 
	 * @param element to add
	 * @return true if added
	 */
	public boolean addElement(O element) {
		return addElements(Collections.singletonList(element)) > 0;
	}
	
	/**
	 * Add elements only if they do not already exist
	 * Re-sorts the array if a sorter is set and any elements are actually added.
	 * 
	 * @param elems to add
	 * @return number added
	 */
	@SuppressWarnings("unchecked")
	public synchronized int addElements(List<O> elems) {
		int added = 0;
		if (elements != null) {
			// delay copy until required
			List<O> list = null;
			for (O e : elems) {
				boolean found = false;
				for (int i = 0; i < elements.length; i++) {
					if (e.equals(elements[i])) {
						found = true;
						break;
					}
				}
				if (!found) {
					if (list == null) {
						list = new ArrayList<O>(Arrays.asList(elements));
					}
					list.add(e);
				}
			}
			if (list != null) {
				added = list.size() - elements.length;
				setElements((O[]) list.toArray(new Object[list.size()]));
			}
		} else if (!elems.isEmpty()) {
			added = elems.size();
			setElements((O[]) (elems.toArray(new Object[added])));
		}
		return added;
	}
	
	/**
	 * Returns an iterator containing the elements on the current page.
         * This iterator is over a copy of the current page, and so
         * is thread safe w.r.t. other operations on this folder,
         * but will not reflect subsequent changes, and iter.remove()
         * will not change the folder.
         *
	 * @return Iterator containing the elements on the current page.
	 */
	public synchronized Iterator<O> currentPageIterator()
	{
		ArrayList<O> list = new ArrayList<O>();
		if( elements != null ) {
			int pageSize = getPageSize();
			int offset = ( currentPage - 1 ) * pageSize;
			for( int i = 0; i < pageSize && offset >= 0 && offset < elements.length; i++ ) {
				list.add( elements[offset] );
				offset++;
			}			
		}
		return list.iterator();
	}

	/**
	 * Turns folder to next page.
	 */
	public synchronized void nextPage()
	{
		currentPage++;
		if( currentPage > pages )
			currentPage = pages;
	}
	
	/**
	 * Turns folder to previous page.
	 */
	public synchronized void previousPage()
	{
		currentPage--;
		if( currentPage < 1 )
			currentPage = 1;		
	}
	
	/**
	 * Sets folder to display first page.
	 */
	public synchronized void firstPage()
	{
		currentPage = 1;
	}
	
	/**
	 * Sets folder to display last page.
	 */
	public synchronized void lastPage()
	{
		currentPage = pages;
	}
	
	/**
	 * Adds a new sorter to the folder. You can sort the folder by
	 * calling setSortBy() and choose the given id there.
	 * 
	 * @param id ID to identify the Comparator with @link setSortBy()
	 * @param sorter a Comparator to sort the Array given by @link setElements()
	 */
	public synchronized void addSorter( String id, Comparator<O> sorter )
	{
		this.sorter.put( id, sorter );
	}
	
	/**
	 * Activates sorting by the choosen Comparator. The id must
	 * match the one, which the Comparator has been stored in the
	 * folder with @link addSorter().
	 * Sets the sorting direction of the folder.
	 * 
	 * Warning, this does not do the actual sort, only addElements() and setElements() does a sort.
	 * 
	 * @param id ID to identify the Comparator stored with @link addSorter()
	 * @param direction UP or DOWN. UP is reverse sort.
	 */
	public synchronized void setSortBy(String id, SortOrder direction)
	{
		sortingDirection = direction;
		currentSorter = sorter.get( id );
		if (currentSorter != null) {
			if (sortingDirection == SortOrder.UP)
				currentSorter = Collections.reverseOrder(currentSorter);
			currentSortID = id;
		} else {
			currentSortID = null;
		}
	}
	
	/**
	 * @since 0.9.13
	 */
	public synchronized String getCurrentSortBy() {
		return currentSortID;
	}
	
	/**
	 * @since 0.9.13
	 */
	public synchronized SortOrder getCurrentSortingDirection() {
		return sortingDirection;
	}
	
	/**
	 * Returns the element on the current page on the given position.
	 *
	 * @param x Position of the element on the current page.
	 * @return Element on the current page on the given position.
	 */
/****  unused, we now fetch by UIDL, not position
	public synchronized O getElementAtPosXonCurrentPage( int x )
	{
		O result = null;
		if( elements != null ) {
			int pageSize = getPageSize();
			int offset = ( currentPage - 1 ) * pageSize;
			offset += x;			
			if( offset >= 0 && offset < elements.length )
				result = elements[offset];
		}
		return result;
	}
****/

	/**
	 * Returns the first element of the sorted folder.
	 * 
	 * @return First element.
	 */
	public synchronized O getFirstElement()
	{
		return elements == null ? null : getElement( 0 );
	}

	/**
	 * Returns the last element of the sorted folder.
	 * 
	 * @return Last element.
	 */
	public synchronized O getLastElement()
	{
		return elements == null ? null : getElement(  elements.length - 1 );
	}
	
	/**
	 * Gets index of an element in the array regardless of sorting direction.
	 * 
	 * @param element
	 * @return index
	 */
	private int getIndexOf( O element )
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
	 * 
	 * @param element
	 * @return The next element
	 */
	public synchronized O getNextElement( O element )
	{
		O result = null;
		
		int i = getIndexOf( element );

		if( i != -1 && elements != null ) {
			i++;
			if( i >= 0 && i < elements.length )
				result = elements[i];
		}
		return result;
	}
	
	/**
	 * Retrieves the previous element in the sorted array.
	 * 
	 * @param element
	 * @return The previous element
	 */
	public synchronized O getPreviousElement( O element )
	{
		O result = null;
		
		int i = getIndexOf( element );

		if( i != -1 && elements != null ) {
			i--;
			if( i >= 0 && i < elements.length )
				result = elements[i];
		}
		return result;
	}
	/**
	 * Retrieves element at index i.
	 * 
	 * @param i
	 * @return Element at index i
	 */
	private O getElement( int i )
	{
		O result = null;
		
		if( elements != null ) {
			result = elements[i];
		}
		return result;
	}
	
	/**
	 * Returns true, if folder shows points to the last page.
	 */
	public synchronized boolean isLastPage()
	{
		return currentPage == pages;
	}
	
	/**
	 * Returns true, if folder shows points to the first page.
	 */
	public synchronized boolean isFirstPage()
	{
		return currentPage == 1;
	}

	/**
	 * Returns true, if elements.equals( lastElementOfTheSortedArray ).
	 * 
	 * @param element
	 */
	public synchronized boolean isLastElement( O element )
	{
		if( elements == null )
			return false;
		return elements[elements.length - 1].equals( element );
	}
	
	/**
	 * Returns true, if elements.equals( firstElementOfTheSortedArray ).
	 * 
	 * @param element
	 */
	public synchronized boolean isFirstElement( O element )
	{
		if( elements == null )
			return false;
		return elements[0].equals( element );
	}
	
	/**
	 * Returns the page this element is on, using the current sort, or 1 if not found
	 * 
	 * @param element
	 * @since 0.9.33
	 */
	public synchronized int getPageOf(O element)
	{
		if (pages <= 1)
			return 1;
		if (elements == null)
			return 1;
		int i = getIndexOf(element);
		if (i < 0)
			return 1;
		return 1 + (i / getPageSize());
	}
}
