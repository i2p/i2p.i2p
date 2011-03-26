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

import net.metanotion.io.block.BlockFile;

public class SkipLevels {
	/*
	 *	"Next" pointers
	 *	The highest indexed level is the "highest" level in the list.
	 *	The "bottom" level is the direct pointer to a SkipSpan.
	 */
	// levels is almost final
	public SkipLevels[] levels;
	// bottom is final
	public SkipSpan bottom;

	public SkipLevels newInstance(int levels, SkipSpan ss, SkipList sl) { return new SkipLevels(levels, ss); }
	public void killInstance() { }
	public void flush() { }

	protected SkipLevels() { }
	public SkipLevels(int size, SkipSpan span) {
		if(size < 1) { throw new RuntimeException("Invalid Level Skip size"); }
		levels = new SkipLevels[size];
		bottom = span;
	}

	public void print() {
		System.out.print("SL:" + key() + "::");
		for(int i=0;i<levels.length;i++) {
			if(levels[i] != null) {
				System.out.print(i + "->" + levels[i].key() + " ");
			} else {
				System.out.print(i + "->() ");
			}
		}
		System.out.print("\n");
		if(levels[0] != null) {
			levels[0].print();
		}
	}

	public SkipSpan getEnd() {
		for(int i=(levels.length - 1);i>=0;i--) {
			if(levels[i] != null) { return levels[i].getEnd(); }
		}
		return bottom.getEnd();
	}

	public SkipSpan getSpan(int start, Comparable key, int[] search) {
		for(int i=Math.min(start, levels.length - 1);i>=0;i--) {
			if((levels[i] != null) && (levels[i].key().compareTo(key) <= 0)) {
				return levels[i].getSpan(i,key,search);
			}
		}
		return bottom.getSpan(key, search);
	}

	public Comparable key() { return bottom.firstKey(); }

	public Object get(int start, Comparable key) {
		for(int i=Math.min(start, levels.length - 1);i>=0;i--) {
			if((levels[i] != null) && (levels[i].key().compareTo(key) <= 0)) {
				return levels[i].get(i,key);
			}
		}
		return bottom.get(key);
	}

	public Object[] remove(int start, Comparable key, SkipList sl) {
		Object[] res = null;
		SkipLevels slvls = null;
		for(int i=Math.min(start, levels.length - 1);i>=0;i--) {
			if(levels[i] != null) {
				int cmp = levels[i].key().compareTo(key);
				if((cmp < 0) || ((i==0) && (cmp <= 0)))  {
					res = levels[i].remove(i, key, sl);
					if((res != null) && (res[1] != null)) {
						slvls = (SkipLevels) res[1];
						if(levels.length >= slvls.levels.length) { res[1] = null; }
						for(int j=0;j<(Math.min(slvls.levels.length,levels.length));j++) {
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
				res[1] = null;
			}
		}
		if((bottom.nKeys == 0) && (sl.first != bottom)) { this.killInstance(); }
		return res;
	}

	/**
	 *  @return the new level if it caused a split and we made a new level,
	 *          and the new level is taller than our level;
	 *          else null if it went in an existing level or the new level is our height or less.
	 */
	public SkipLevels put(int start, Comparable key, Object val, SkipList sl) {
		boolean modified = false;
		for(int i = Math.min(start, levels.length - 1); i >= 0; i--) {
			// is key equal to or after the start of the level?
			if((levels[i] != null) && (levels[i].key().compareTo(key) <= 0)) {
				SkipLevels slvls = levels[i].put(i, key, val, sl);
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
				if (modified)
					this.flush();
				return null;
			}
		}
		SkipSpan ss = bottom.put(key,val,sl);
		if(ss!=null) {
			int height = sl.generateColHeight();
			if(height != 0) {
				SkipLevels slvls = this.newInstance(height, ss, sl);
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

	public void blvlck(boolean fix, int depth) {}
}

