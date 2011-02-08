/*
Copyright (c) 2006, Matthew Estes
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

	* Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
	* Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.
	* Neither the name of Metanotion Software nor the names of its
contributors may be used to endorse or promote products derived from this
software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package net.metanotion.util.skiplist;

import java.util.ListIterator;
import java.util.NoSuchElementException;

/**	A basic iterator for a skip list.
 	This is not a complete ListIterator, in particular, since the
 	skip list is a map and is therefore indexed by Comparable objects instead
 	of int's, the nextIndex and previousIndex methods are not really relevant.
*/
public class SkipIterator implements ListIterator {
	SkipSpan ss;
	int index;

	protected SkipIterator() { }
	public SkipIterator(SkipSpan ss, int index) {
		if(ss==null) { throw new NullPointerException(); }
		this.ss = ss;
		this.index = index;
	}

	public boolean hasNext() {
		if(index < ss.nKeys) { return true; }
		return false;
	}

	public Object next() {
		Object o;
		if(index < ss.nKeys) {
			o = ss.vals[index];
		} else {
			throw new NoSuchElementException();
		}

		if(index < (ss.nKeys-1)) {
			index++;
		} else if(ss.next != null) {
			ss = ss.next;
			index = 0;
		} else {
			index = ss.nKeys;
		}
		return o;
	}

	public Comparable nextKey() {
		Comparable c;
		if(index < ss.nKeys) { return ss.keys[index]; }
		throw new NoSuchElementException();
	}

	public boolean hasPrevious() {
		if(index > 0) { return true; }
		if((ss.prev != null) && (ss.prev.nKeys > 0)) { return true; }
		return false;
	}

	public Object previous() {
		if(index > 0) {
			index--;
		} else if(ss.prev != null) {
			ss = ss.prev;
			if(ss.nKeys <= 0) { throw new NoSuchElementException(); }
			index = (ss.nKeys - 1);
		}
		return ss.vals[index];
	}


	// Optional methods
	public void add(Object o)	{ throw new UnsupportedOperationException(); }
	public void remove()		{ throw new UnsupportedOperationException(); }
	public void set(Object o)	{ throw new UnsupportedOperationException(); }
	public int nextIndex()		{ throw new UnsupportedOperationException(); }
	public int previousIndex()	{ throw new UnsupportedOperationException(); }

}
