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
package net.metanotion.io.block.index;

import java.io.IOException;
import java.util.NoSuchElementException;

import net.metanotion.util.skiplist.SkipIterator;
import net.metanotion.util.skiplist.SkipSpan;

/**
	I2P
	Overridden to load the span when required and null out the keys and values
	when the iterator leaves the span.
	If the caller does not iterate all the way through, the last span
	will remain in memory.
*/
public class IBSkipIterator extends SkipIterator {

	public IBSkipIterator(SkipSpan ss, int index) {
		super(ss, index);
	}

	/**
	 * @return the next value, and advances the index
	 * @throws NoSuchElementException
	 * @throws RuntimeException on IOE
	 */
	@Override
	public Object next() {
		Object o;
		if(index < ss.nKeys) {
			if (ss.vals == null) {
				try {
					((IBSkipSpan)ss).seekAndLoadData();
				} catch (IOException ioe) {
					throw new RuntimeException("Error in iterator", ioe);
				}
			}
			o = ss.vals[index];
		} else {
			throw new NoSuchElementException();
		}

		if(index < (ss.nKeys-1)) {
			index++;
		} else if(ss.next != null) {
			ss.keys = null;
			ss.vals = null;
			ss = ss.next;
			index = 0;
		} else {
			ss.keys = null;
			ss.vals = null;
			index = ss.nKeys;
		}
		return o;
	}

	/**
	 * The key. Does NOT advance the index.
	 * @return the key for which the value will be returned in the subsequent call to next()
	 * @throws NoSuchElementException
	 * @throws RuntimeException on IOE
	 */
	@Override
	public Comparable nextKey() {
		if(index < ss.nKeys) {
			if (ss.keys == null) {
				try {
					((IBSkipSpan)ss).seekAndLoadData();
				} catch (IOException ioe) {
					throw new RuntimeException("Error in iterator", ioe);
				}
			}
			return ss.keys[index];
		}
		throw new NoSuchElementException();
	}

	/**
	 * @return the previous value, and decrements the index
	 * @throws NoSuchElementException
	 * @throws RuntimeException on IOE
	 */
	@Override
	public Object previous() {
		if(index > 0) {
			index--;
		} else if(ss.prev != null) {
			ss.keys = null;
			ss.vals = null;
			ss = ss.prev;
			if(ss.nKeys <= 0) { throw new NoSuchElementException(); }
			index = (ss.nKeys - 1);
		} else {
			ss.keys = null;
			ss.vals = null;
			throw new NoSuchElementException();
		}
		if (ss.vals == null) {
			try {
				((IBSkipSpan)ss).seekAndLoadData();
			} catch (IOException ioe) {
				throw new RuntimeException("Error in iterator", ioe);
			}
		}
		return ss.vals[index];
	}
}
