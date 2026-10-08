package bhtp;

import java.io.ByteArrayOutputStream;
import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Header block encoding (SPEC.md section 5). It is the first two ideas from HPACK:
 *
 *   1. a static table - the 10 header names we actually send get a 1-byte index
 *   2. string literals - anything else is sent as a length-prefixed name
 *
 * Each entry:  [index:1] ([name_len:1][name])?  [value_len:2][value]
 * The name part is only there when index == 0. Entries run to the end of the payload.
 */
public final class Headers {

    // index 0 is "literal name follows", so the table starts at 1
    static final String[] TABLE = {
        null,
        "host",            // 1  request
        "user-agent",      // 2  request
        "accept",          // 3  request
        "content-type",    // 4  response
        "content-length",  // 5  response
        "date",            // 6  response
        "last-modified",   // 7  response
        "etag",            // 8  response
        "server",          // 9  response
        "allow",           // 10 response (405 only)
    };

    public record Field(String name, String value) {}

    private final List<Field> fields = new ArrayList<>();

    public Headers add(String name, String value) {
        fields.add(new Field(name.toLowerCase(Locale.ROOT), value));
        return this;
    }

    /** First value for a name, or null. */
    public String get(String name) {
        name = name.toLowerCase(Locale.ROOT);
        for (Field f : fields)
            if (f.name.equals(name))
                return f.value;
        return null;
    }

    public List<Field> fields() {
        return fields;
    }

    static int indexOf(String name) {
        for (int i = 1; i < TABLE.length; i++)
            if (TABLE[i].equals(name))
                return i;
        return 0;
    }

    public void encode(ByteArrayOutputStream out) {
        for (Field f : fields) {
            byte[] value = f.value.getBytes(StandardCharsets.UTF_8);
            if (value.length > 0xFFFF)
                throw new IllegalArgumentException("header value too long: " + f.name);

            int idx = indexOf(f.name);
            out.write(idx);
            if (idx == 0) {
                byte[] name = f.name.getBytes(StandardCharsets.US_ASCII);
                if (name.length == 0 || name.length > 255)
                    throw new IllegalArgumentException("bad header name length: " + f.name);
                out.write(name.length);
                out.writeBytes(name);
            }
            out.write(value.length >>> 8);
            out.write(value.length & 0xff);
            out.writeBytes(value);
        }
    }

    /** Decodes entries from the buffer's position to its limit. */
    public static Headers decode(ByteBuffer buf) throws ProtocolException {
        Headers h = new Headers();
        try {
            while (buf.hasRemaining()) {
                int idx = buf.get() & 0xff;
                String name;
                if (idx == 0) {
                    int n = buf.get() & 0xff;
                    if (n == 0)
                        throw new ProtocolException("empty literal header name");
                    name = readString(buf, n).toLowerCase(Locale.ROOT);
                } else if (idx < TABLE.length) {
                    name = TABLE[idx];
                } else {
                    // index from a newer table than ours. The value is still
                    // length-prefixed, so we can step over it and drop the header.
                    name = null;
                }
                int vlen = buf.getShort() & 0xffff;
                String value = readString(buf, vlen);
                if (name != null)
                    h.add(name, value);
            }
        } catch (BufferUnderflowException e) {
            throw new ProtocolException("header block is truncated");
        }
        return h;
    }

    private static String readString(ByteBuffer buf, int n) {
        byte[] b = new byte[n];
        buf.get(b); // throws BufferUnderflowException if the length lied
        return new String(b, StandardCharsets.UTF_8);
    }
}
