package net.i2p.client.streaming;

/** Like a StringBuffer, but for bytes */
public class ByteCollector {
    byte[] contents;
    int size;

    public ByteCollector() {
	contents=new byte[80];
	size=0;
    }

    public ByteCollector(byte[] b) {
	this();
	append(b);
    }

    public ByteCollector(byte b) {
	this();
	append(b);
    }

    public ByteCollector append (byte b) {
	ensureCapacity(size+1);
	contents[size++]=b;
	return this;
    }

    public ByteCollector append (byte[] b) {
	ensureCapacity(size+b.length);
	System.arraycopy(b,0,contents,size,b.length);
	size+=b.length;
	return this;
    }

    public ByteCollector append(byte[] b, int len) {
	return append(b,0,len);
    }

    public ByteCollector append(byte[] b, int off, int len) {
	ensureCapacity(size+len);
	System.arraycopy(b,off,contents,size,len);
	size+=len;
	return this;
    }
    
    public ByteCollector append(ByteCollector bc) {
	// optimieren?
	return append(bc.toByteArray());
    }

    public byte[] toByteArray() {
	byte[] result=new byte[size];
	System.arraycopy(contents,0,result,0,size);
	return result;
    }

    public byte[] startToByteArray(int maxlen) {
	if (size < maxlen) {
	    byte[] res = toByteArray();
	    clear();
	    return res;
	} else {
	    byte[] result = new byte[maxlen];
	    System.arraycopy(contents,0,result,0,maxlen);
	    System.arraycopy(contents,maxlen,contents,0,size-maxlen);
	    size-=maxlen;
	    return result;
	}
    }
    
    public int getCurrentSize() { 
	return size;
    }

    public boolean ensureCapacity(int cap) {
	if (contents.length<cap) {
	    int l=contents.length;
	    while (l<cap) {
		l=(l*3)/2+1;
	    }
	    byte[] newcont=new byte[l];
	    System.arraycopy(contents,0,newcont,0,size);
	    contents=newcont;
	    return true;
	}
	return false;
    }

    public boolean isEmpty() {
	return size==0;
    }

    public int indexOf(ByteCollector bc) {
	// optimieren?
	return indexOf(bc.toByteArray());
    }
    
    public int indexOf(byte b) {
	// optimieren?
	return indexOf(new byte[] {b});
    }
    
    public int indexOf(byte[] ba) {
	loop:
	for (int i=0;i<size-ba.length+1;i++) {
	    for (int j=0;j<ba.length;j++) {
		if (contents[i+j]!=ba[j]) continue loop;
	    }
	    return i;
	}
	return -1;
    }
    
    public void clear() {
	size=0;
    }

    public void clearAndShorten() {
	size=0;
	contents=new byte[80];
    }

    public String toString() {
	return new String(toByteArray());
    }

    public int hashCode() {
	int h =0;
	for (int i=0;i<size;i++) {
	    h+=contents[i]*contents[i];
	}
	return h;
    }

    public boolean equals(Object o) {
	if (o instanceof ByteCollector) {
	    ByteCollector by=(ByteCollector)o;
	    if (size!=by.size) return false;
	    for (int i=0;i<size;i++) {
		if (contents[i]!=by.contents[i]) return false;
	    }
	    return true;
	} else {
	    return false;
	}
    }

    public byte removeFirst() {
	byte bb=contents[0];
	if (size==0)
	    throw new IllegalArgumentException("ByteCollector is empty");
	if(size>1)
	    System.arraycopy(contents,1,contents,0,--size);
	else
	    size=0;
	return bb;
    }
}
