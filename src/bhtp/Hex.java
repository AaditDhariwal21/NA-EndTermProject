package bhtp;

/** Classic hexdump: offset, 16 bytes in two groups of 8, then the printable chars. */
public final class Hex {
    private Hex() {}

    public static String dump(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        for (int off = 0; off < len; off += 16) {
            sb.append(String.format("  %04x  ", off));
            for (int i = 0; i < 16; i++) {
                if (off + i < len)
                    sb.append(String.format("%02x ", b[off + i] & 0xff));
                else
                    sb.append("   ");
                if (i == 7)
                    sb.append(' ');
            }
            sb.append(" |");
            for (int i = off; i < Math.min(off + 16, len); i++) {
                int c = b[i] & 0xff;
                sb.append(c >= 0x20 && c < 0x7f ? (char) c : '.');
            }
            sb.append("|\n");
        }
        return sb.toString();
    }

    /** Short single-line form, for log messages. */
    public static String inline(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b)
            sb.append(String.format("%02x ", x & 0xff));
        return sb.toString().trim();
    }
}
