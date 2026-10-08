package bhtp;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One frame on the wire: a fixed 8-byte header, then `length` bytes of payload.
 *
 * <pre>
 *  byte:  0        1        2        3        4        5        6        7
 *     +--------+--------+--------+--------+--------+--------+--------+--------+
 *     |     length      |  type  | flags  |            stream id              |
 *     +--------+--------+--------+--------+--------+--------+--------+--------+
 * </pre>
 *
 * Everything is big-endian. The length is always in the same place no matter
 * what the type is - that is what makes it possible to skip frame types we
 * don't understand.
 */
public final class Frame {
    public final int type;
    public final int flags;
    public final int streamId;   // really unsigned 32-bit, but we never get near 2^31
    public final byte[] payload;

    public Frame(int type, int flags, int streamId, byte[] payload) {
        if (payload.length > Proto.MAX_PAYLOAD)
            throw new IllegalArgumentException("payload too big for one frame: " + payload.length);
        this.type = type;
        this.flags = flags;
        this.streamId = streamId;
        this.payload = payload;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    /** The exact bytes that go on the wire (header + payload). */
    public byte[] toBytes() {
        int len = payload.length;
        byte[] b = new byte[Proto.HEADER_SIZE + len];
        b[0] = (byte) (len >>> 8);
        b[1] = (byte) len;
        b[2] = (byte) type;
        b[3] = (byte) flags;
        b[4] = (byte) (streamId >>> 24);
        b[5] = (byte) (streamId >>> 16);
        b[6] = (byte) (streamId >>> 8);
        b[7] = (byte) streamId;
        System.arraycopy(payload, 0, b, Proto.HEADER_SIZE, len);
        return b;
    }

    public void writeTo(OutputStream out) throws IOException {
        out.write(toBytes());
    }

    /**
     * Reads one whole frame. Returns null if the peer closed the connection
     * cleanly between two frames, throws EOFException if it closed in the middle of one.
     */
    public static Frame read(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0)
            return null;

        byte[] h = new byte[Proto.HEADER_SIZE];
        h[0] = (byte) first;
        if (in.readNBytes(h, 1, h.length - 1) != h.length - 1)
            throw new EOFException("connection closed inside a frame header");

        int len = ((h[0] & 0xff) << 8) | (h[1] & 0xff);
        int type = h[2] & 0xff;
        int flags = h[3] & 0xff;
        int stream = ((h[4] & 0xff) << 24) | ((h[5] & 0xff) << 16)
                   | ((h[6] & 0xff) << 8) | (h[7] & 0xff);

        // max 64 KiB, so even a garbage length can't make us allocate much
        byte[] payload = in.readNBytes(len);
        if (payload.length != len)
            throw new EOFException("connection closed inside a frame payload ("
                    + payload.length + " of " + len + " bytes)");

        return new Frame(type, flags, stream, payload);
    }

    @Override
    public String toString() {
        String f = has(Proto.FLAG_END) ? "END" : "-";
        if ((flags & ~Proto.FLAG_END) != 0)
            f += String.format("+0x%02x", flags & ~Proto.FLAG_END);
        return String.format("%s stream=%s flags=%s len=%d",
                Proto.typeName(type), Integer.toUnsignedString(streamId), f, payload.length);
    }
}
