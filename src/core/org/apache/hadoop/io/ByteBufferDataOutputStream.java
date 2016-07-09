package org.apache.hadoop.io;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class ByteBufferDataOutputStream extends DataOutputStream {
    private static class ByteBufferOutputStream extends OutputStream {
	private ByteBuffer buf;
	public ByteBufferOutputStream(ByteBuffer buf) {
	    this.buf = buf;
	}
	
	public ByteBufferOutputStream(int size) {
	    this.buf = ByteBuffer.allocate(size);
	}
	public ByteBuffer getByteBuffer() {
	    return buf;
	}
	
	public void write(int b) throws IOException {
	    if (!buf.hasRemaining()) 
		throw new IOException("no enough space");
	    buf.put((byte)b);
	}
	
	public void write(byte[] bytes, int offset, int length) throws IOException {
	    if (buf.remaining() < length)
		throw new IOException("no enough space");
	    buf.put(bytes, offset, length);
	}
    }
    public ByteBufferDataOutputStream(ByteBuffer buf) {
	super(new ByteBufferOutputStream(buf));
    }
    public ByteBuffer getByteBuffer() {
	return ((ByteBufferOutputStream)out).getByteBuffer();
    }
    public ByteBufferDataOutputStream(int size) {
	super(new ByteBufferOutputStream(size));
    }
}
