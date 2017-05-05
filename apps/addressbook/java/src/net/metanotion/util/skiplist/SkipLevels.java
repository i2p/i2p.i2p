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

import java.io.Flushable;

import net.metanotion.io.block.BlockFile;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

public class SkipLevels<K extends Comparable<? super K>, V> implements Flushable {
	/** We can't have more than 2**32 pages */
	public static final int MAX_SIZE = 32;

	/*
	 *	"Next" pointers
	 *	The highest indexed level is the "highest" level in the list.
	 *	The "bottom" level is the direct pointer to a SkipSpan.
	 */
	// levels is almost final
	public SkipLevels<K, V>[] levels;
	// bottom is final
	public SkipSpan<K, V> bottom;
	private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(BlockFile.class);

	public SkipLevels<K, V> newInstance(int levels, SkipSpan<K, V> ss, SkipList<K, V> sl) {
		return new SkipLevels<K, V>(levels, ss);
	}

	public void killInstance() { }
	public void flush() { }

	protected SkipLevels() { }

	/*
	 *  @throws IllegalArgumentException if size too big or too small
	 */
	@SuppressWarnings("unchecked")
	public SkipLevels(int size, SkipSpan<K, V> span) {
		if(size < 1 || size > MAX_SIZE)
			throw new IllegalArgumentException("Invalid Level Skip size");
		levels = (SkipLevels<K, V>[]) new SkipLevels[size];
		bottom = span;
	}

	public String print() {
		StringBuilder buf = new StringBuilder(128);
		String k = (bottom.nKeys == 0) ? "empty" : (key() != null) ? key().toString() : "null";
		buf.append("LVLS: ").append(k).append(" :: ");
		for(int i=0;i<levels.length;i++) {
			buf.append(i);
			if(levels[i] != null) {
				buf.append("->").append(levels[i].key()).append(' ');
			} else {
				buf.append("->() ");
			}
		}
		buf.append('\n');
		return buf.toString();
	}

	public String printAll() {
		StringBuilder buf = new StringBuilder(128);
		buf.append(print());
		if(levels[0] != null) {
			buf.append('\n');
			buf.append(levels[0].print());
		}
		return buf.toString();
	}

	public SkipSpan<K, V> getEnd() {
		for(int i=(levels.length - 1);i>=0;i--) {
			if(levels[i] != null) { return levels[i].getEnd(); }
		}
		return bottom.getEnd();
	}

	public SkipSpan<K, V> getSpan(int start, K key, int[] search) {
		for(int i=Math.min(start, levels.length - 1);i>=0;i--) {
			if((levels[i] != null) && (levels[i].key().compareTo(key) <= 0)) {
				return levels[i].getSpan(i,key,search);
			}
		}
		return bottom.getSpan(key, search);
	}

	public K key() { return bottom.firstKey(); }

	public V get(int start, K key) {
		for(int i=Math.min(start, levels.length - 1);i>=0;i--) {
			if((levels[i] != null) && (levels[i].key().compareTo(key) <= 0)) {
				return levels[i].get(i,key);
			}
		}
		return bottom.get(key);
	}

	/**
	 *  @return An array of two objects or null.
	 *          rv[0] is the removed object.
	 *          rv[1] is the deleted SkipLevels if the removed object was the last in the SkipLevels,
	 *                and the deleted SkipLevels is taller than this SkipLevels.
	 *          rv is null if no object was removed.
	 */
	@SuppressWarnings("unchecked")
	public Object[] remove(int start, K key, SkipList<K, V> sl) {
		Object[] res = null;
		SkipLevels<K, V> slvls = null;
		for(int i = Math.min(start, levels.length - 1); i >= 0; i--) {
			if(levels[i] != null) {
				int cmp = levels[i].key().compareTo(key);
				if((cmp < 0) || ((i==0) && (cmp <= 0)))  {
					res = levels[i].remove(i, key, sl);
					if((res != null) && (res[1] != null)) {
						slvls = (SkipLevels<K, V>) res[1];
						if(levels.length >= slvls.levels.length) {
							res[1] = null;
						}
						for(int j = 0 ; j < Math.min(slvls.levels.length, levels.length); j++) {
							if(levels[j] == slvls) {
								levels[j] = slvls.levels[j];
							}
						}
						this.flush();
					}
					return res;
				}
			}
		}
		res = bottom.remove(key, sl);
		if((res!=null) && (res[1] != null)) {
			if(res[1] == bottom) {
				res[1] = this;
			} else {
				// Special handling required if we are the head SkipLevels to fix up our level pointers
				// if the returned SkipSpan was already copied to us
				boolean isFirst = sl.first == bottom;
				if (isFirst && levels[0] != null) {
					SkipSpan<K, V> ssres = (SkipSpan<K, V>)res[1];
					if (bottom.firstKey().equals(ssres.firstKey())) {
						// bottom copied the next span to itself
						if (_log.shouldLog(Log.INFO)) {
							_log.info("First Level, bottom.remove() copied and did not return itself!!!! in remove " + key);
							_log.info("Us:     " + print());
							_log.info("next:   " + levels[0].print());
							_log.info("ssres.firstKey():   " + ssres.firstKey());
							_log.info("ssres.keys[0]:   " + ssres.keys[0]);
							_log.info("FIXUP TIME");
						}
						
						SkipLevels<K, V> replace = levels[0];
						for (int i = 0; i < levels.length; i++) {
							if (levels[i] == null)
								break;
							if (i >= replace.levels.length)
								break;
							if (levels[i].key().equals(replace.key())) {
								if (_log.shouldLog(Log.INFO))
							        	_log.info("equal level " + i);
								levels[i] = replace.levels[i];
							} else if (_log.shouldLog(Log.INFO)) {
								_log.info("not equal level " + i + ' ' + levels[i].key());
							}
						}
						this.flush();
						if (_log.shouldLog(Log.INFO))
							_log.info("new Us: " + print());
						replace.killInstance();
					}
				}
				res[1] = null;
			}
		}
		if((bottom.nKeys == 0) && (sl.first != bottom)) {
			// from debugging other problems
			if (res == null) {
				_log.warn("killing with no return value " + print());
			} else if (res[1] == null) {
				_log.warn("killing with no return value 1 " + print());
			} else if (res[1] != this) {
				_log.warn("killing with return value not us " + res[1] + ' ' + print());
			}
			this.killInstance();
		}
		return res;
	}

	/**
	 *  @return the new level if it caused a split and we made a new level,
	 *          and the new level is taller than our level;
	 *          else null if it went in an existing level or the new level is our height or less.
	 */
	public SkipLevels<K, V> put(int start, K key, V val, SkipList<K, V> sl) {
		boolean modified = false;
		for(int i = Math.min(start, levels.length - 1); i >= 0; i--) {
			// is key equal to or after the start of the level?
			if((levels[i] != null) && (levels[i].key().compareTo(key) <= 0)) {
				SkipLevels<K, V> slvls = levels[i].put(i, key, val, sl);
				if(slvls != null) {
					for (int j = i + 1; j < Math.min(slvls.levels.length, levels.length); j++) {
						// he points to where we used to point
						// and we now point to him
						slvls.levels[j] = levels[j];
						levels[j] = slvls;
						modified = true;
					}
					if(levels.length < slvls.levels.length) {
						if (modified) {
							this.flush();
							slvls.flush();
						}
						return slvls;
					}
				}
				if (modified) {
					this.flush();
					if (slvls != null)
						slvls.flush();
				}
				return null;
			}
		}
		SkipSpan<K, V> ss = bottom.put(key,val,sl);
		if(ss!=null) {
			int height = sl.generateColHeight();
			if(height != 0) {
				SkipLevels<K, V> slvls = this.newInstance(height, ss, sl);
				for(int i=0;i<(Math.min(height,levels.length));i++) {
					// he points to where we used to point
					// and we now point to him
					slvls.levels[i] = levels[i];
					levels[i] = slvls;
					modified = true;
				}
				if (modified) {
					this.flush();
					slvls.flush();
				}
				if(levels.length < height)
					return slvls;
			}
		}
		return null;
	}

	public boolean blvlck(boolean fix) { return false; }
	public boolean blvlck(boolean fix, int width, SkipLevels<K, V>[] prevLevels) { return false; }
}

