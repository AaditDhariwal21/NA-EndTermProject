package bhtp;

import java.nio.charset.StandardCharsets;

/**
 * Constants for BHTP/1. Every number in here is defined in SPEC.md,
 * so if you change one, change the spec too.
 */
public final class Proto {
    private Proto() {}

    /** Sent once by each side when the connection opens. Exactly 8 bytes. */
    public static final byte[] PREFACE = "BHTP/1\r\n".getBytes(StandardCharsets.US_ASCII);

    public static final int HEADER_SIZE = 8;
    public static final int MAX_PAYLOAD = 0xFFFF;   // limited by the 16-bit length field
    public static final int DATA_CHUNK = 16 * 1024; // how much file we put in one DATA frame

    // frame types
    public static final int REQUEST = 0x01;
    public static final int RESPONSE = 0x02;
    public static final int DATA = 0x03;

    // flags
    public static final int FLAG_END = 0x01;        // last frame of this message

    // methods (first byte of a REQUEST payload)
    public static final int GET = 1;
    public static final int HEAD = 2;

    public static String typeName(int type) {
        return switch (type) {
            case REQUEST -> "REQUEST";
            case RESPONSE -> "RESPONSE";
            case DATA -> "DATA";
            default -> String.format("UNKNOWN(0x%02x)", type);
        };
    }

    public static String methodName(int m) {
        return switch (m) {
            case GET -> "GET";
            case HEAD -> "HEAD";
            default -> "method#" + m;
        };
    }

    public static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            default -> "";
        };
    }
}
