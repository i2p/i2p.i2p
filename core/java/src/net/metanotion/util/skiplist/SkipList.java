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

import java.util.Random;

public class SkipList {
	protected SkipSpan first;
	protected SkipLevels stack;
	public Random rng;

	public int size=0;
	public int spans=0;

	public void flush() { }
	protected SkipList() { }

	public SkipList(int span) {
		if(span < 1) { throw new Error("Span size too small"); }
		first = new SkipSpan(span);
		stack = new SkipLevels(1, first);
		spans = 1;
		rng = new Random(System.currentTimeMillis());
	}

	public int size() { return size; }

	public int maxLevels() {
		int hob = 0, s = spans;
		while(spans > 0) {
			hob++;
			spans = spans / 2;
		}
		return (hob > 4) ? hob : 4;
	}

	public int generateColHeight() {
		int bits = rng.nextInt();
		boolean cont = true;
		int res=0;
		for(res=0; cont; res++) {
			cont = ((bits % 2) == 0) ? true : false;
			bits = bits / 2;
		}
		return Math.max(0, Math.min(res, maxLevels()));
	}

	public void put(Comparable key, Object val)	{
		if(key == null) { throw new NullPointerException(); }
		if(val == null) { throw new NullPointerException(); }
		SkipLevels slvls = stack.put(stack.levels.length - 1, key, val, this);
		if(slvls != null) {
			SkipLevels[] levels = new SkipLevels[slvls.levels.length];
			for(int i=0;i < slvls.levels.length; i++) {
				if(i < stack.levels.length) {
					levels[i] = stack.levels[i];
				} else {
					levels[i] = slvls;
				}
			}
			stack.levels = levels;
			stack.flush();
			flush();
		}
	}

	public Object remove(Comparable key) {
		if(key == null) { throw new NullPointerException(); }
		Object[] res = stack.remove(stack.levels.length - 1, key, this);
		if(res != null) {
			if(res[1] != null) {
				SkipLevels slvls = (SkipLevels) res[1];
				for(int i=0;i < slvls.levels.length; i++) {
					if(stack.levels[i] == slvls) {
						stack.levels[i] = slvls.levels[i];
					}
				}
				stack.flush();
			}
			flush();
			return res[0];
		}
		return null;
	}

	public void printSL() {
		System.out.println("List size " + size + " spans " + spans);
		stack.print();
	}

	public void print() {
		System.out.println("List size " + size + " spans " + spans);
		first.print();
	}

	public Object get(Comparable key) {
		if(key == null) { throw new NullPointerException(); }
		return stack.get(stack.levels.length - 1, key);
	}

	public SkipIterator iterator() { return new SkipIterator(first, 0); }

	public SkipIterator min() { return new SkipIterator(first, 0); }

	public SkipIterator max() {
		SkipSpan ss = stack.getEnd();
		return new SkipIterator(ss, ss.nKeys - 1);
	}

	public SkipIterator find(Comparable key) {
		int[] search = new int[1];
		SkipSpan ss = stack.getSpan(stack.levels.length - 1, key, search);
		if(search[0] < 0) { search[0] = -1 * (search[0] + 1); }
		return new SkipIterator(ss, search[0]);
	}


	// Levels adjusted to guarantee O(log n) search
	// This is expensive proportional to the number of spans.
	public void balance() {
		// TODO Skip List Balancing Algorithm
	}



/*
	Basic Error generating conditions to test
		insert into empty
		insert into non empty
		remove from empty
		remove from non-empty a non-existant key
		get from empty
		get from non-empty a non-existant key

		Repeat, with splits induced, and collapse induced.
*/
	public static void main(String args[]) {
		SkipList sl = new SkipList(3);
		sl.put(".1", "1");
		sl.remove("2");
		sl.remove("1");
		sl.put(".1", "1-1");
		sl.put(".2", "2");
		sl.put(".3", "3");
/*		System.out.println("\n#1");
		sl.print();
*/

		sl.put(".4", "4");
/*		System.out.println("\n#2");
		sl.print();

		sl.remove("1");
		System.out.println("\n#2.1");
		sl.print();
		sl.remove("2");
		System.out.println("\n#2.2");
		sl.print();
		sl.remove("3");
		System.out.println("\n#2.3");
		sl.print();
		sl.remove("4");

		System.out.println("\n#3");
		sl.print();
*/
		sl.put(".1", "1-2");
		sl.put(".2", "2-1");
		sl.put(".3", "3-1");
		sl.put(".4", "4-1");
//		System.out.println("\n#4");
//		sl.print();
		sl.put(".5", "5-1");
		sl.put(".6", "6-1");
		sl.put(".7", "7-1");

//		System.out.println("\n#5");
//		sl.print();

//		sl.remove("5");
		sl.put(".5", "5-2");
//		System.out.println("\n#6");
//		sl.print();

		sl.put(".8", "8");
		sl.put(".9", "9");
		sl.put("10", "10");
		sl.put("11", "11");
		sl.put("12", "12");
		sl.put("13", "13");
		sl.put("14", "14");
		sl.put("15", "15");
		sl.put("16", "16");
		sl.put("17", "17");
		sl.put("18", "18");
		sl.put("19", "19");
		sl.put("20", "20");
		sl.put("21", "21");
		sl.put("22", "22");
		sl.put("23", "23");
		sl.put("24", "24");
		sl.put("25", "25");
		sl.put("26", "26");
		sl.put("27", "27");
		sl.put("28", "28");
		sl.put("29", "29");
		sl.put("30", "30");
		sl.put("31", "31");
		sl.put("32", "32");
		sl.put("33", "33");
		sl.put("34", "34");
		sl.put("35", "35");
		sl.put("36", "36");
		sl.put("37", "37");
		sl.put("38", "38");
		sl.put("39", "39");
		sl.put("40", "40");

//		System.out.println("\n#7");
//		sl.print();
		System.out.println("GET " + sl.get("10"));
		System.out.println("GET " + sl.get("12"));
		System.out.println("GET " + sl.get("32"));
		System.out.println("GET " + sl.get("33"));
		System.out.println("GET " + sl.get("37"));
		System.out.println("GET " + sl.get("40"));

		sl.printSL();

		sl.remove("33");
		sl.printSL();
		sl.remove("34");
		sl.printSL();
		sl.remove("36");
		sl.printSL();
		sl.remove("35");
		sl.printSL();

//		System.out.println("\n#8");
		sl.print();
		System.out.println("GET " + sl.get("10"));
		System.out.println("GET " + sl.get("12"));
		System.out.println("GET " + sl.get("32"));
		System.out.println("GET " + sl.get("33"));
		System.out.println("GET " + sl.get("37"));
		System.out.println("GET " + sl.get("40"));

		System.out.println("Height " + sl.stack.levels.length);

		SkipIterator si = sl.iterator();
		for(int i=0;i<5;i++) {
			System.out.println("Iterator: " + si.next());
		}
		for(int i=0;i<3;i++) {
			System.out.println("Iterator: " + si.previous());
		}

		System.out.println("Find 10");
		si = sl.find("10");
		for(int i=0;i<5;i++) {
			System.out.println("Iterator: " + si.next());
		}
		for(int i=0;i<3;i++) {
			System.out.println("Iterator: " + si.previous());
		}

		System.out.println("Find 34");
		si = sl.find("34");
		for(int i=0;i<3;i++) {
			System.out.println("Iterator: " + si.previous());
		}
		for(int i=0;i<5;i++) {
			System.out.println("Iterator: " + si.next());
		}

		System.out.println("Max");
		si = sl.max();
		for(int i=0;i<3;i++) {
			System.out.println("Iterator: " + si.previous());
		}
		for(int i=0;i<5;i++) {
			System.out.println("Iterator: " + si.next());
		}
	}
}
