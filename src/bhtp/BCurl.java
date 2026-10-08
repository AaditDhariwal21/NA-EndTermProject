package bhtp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * bcurl - command line client for BHTP/1.
 *
 *   usage: bcurl [-v | -vv] [-I] host[:port]/path [/another/path | host:port/path ...]
 *
 *   -v    hexdump every frame sent and received (payloads cut at 64 bytes)
 *   -vv   same, but dump full payloads
 *   -I    send HEAD instead of GET
 *
 * Body goes to stdout, everything else to stderr, so `bcurl host:9000/a.png > a.png` works.
 * All paths are fetched over ONE connection, one after the other.
 *
 * Exit status: 0 ok, 1 usage, 2 network/protocol error, 4 if any 4xx, 5 if any 5xx.
 */
public class BCurl {
    static final int EXIT_OK = 0, EXIT_USAGE = 1, EXIT_PROTOCOL = 2, EXIT_4XX = 4, EXIT_5XX = 5;
    static final int DEFAULT_PORT = 9000;
    static final int DUMP_LIMIT = 64;

    private final OutputStream body;
    private final PrintStream log;
    private int verbose;
    private boolean head;

    public BCurl(OutputStream body, PrintStream log) {
        this.body = body;
        this.log = log;
    }

    public static void main(String[] args) {
        int code = new BCurl(System.out, System.err).run(args);
        System.out.flush();
        System.exit(code);
    }

    private record Target(String host, int port, String path) {}

    public int run(String[] args) {
        List<String> rest = new ArrayList<>();
        for (String a : args) {
            switch (a) {
                case "-v" -> verbose = Math.max(verbose, 1);
                case "-vv" -> verbose = 2;
                case "-I" -> head = true;
                default -> {
                    if (a.startsWith("-") && a.length() > 1) {
                        log.println("bcurl: unknown option " + a);
                        return usage();
                    }
                    rest.add(a);
                }
            }
        }
        if (rest.isEmpty())
            return usage();

        Target first;
        List<String> paths = new ArrayList<>();
        try {
            first = parseTarget(rest.get(0));
            paths.add(first.path);
            for (String a : rest.subList(1, rest.size())) {
                if (a.startsWith("/")) {
                    paths.add(a);
                    continue;
                }
                Target t = parseTarget(a);
                if (!t.host.equalsIgnoreCase(first.host) || t.port != first.port) {
                    log.println("bcurl: refusing to open a second connection: " + t.host + ":" + t.port
                            + " is not " + first.host + ":" + first.port);
                    return EXIT_USAGE;
                }
                paths.add(t.path);
            }
        } catch (IllegalArgumentException e) {
            log.println("bcurl: " + e.getMessage());
            return EXIT_USAGE;
        }

        try (Socket s = new Socket(first.host, first.port)) {
            if (verbose > 0)
                log.println("* connected to " + s.getRemoteSocketAddress());
            return fetchAll(s, first, paths);
        } catch (UnknownHostException e) {
            log.println("bcurl: unknown host " + first.host);
        } catch (ConnectException e) {
            log.println("bcurl: could not connect to " + first.host + ":" + first.port + " (" + e.getMessage() + ")");
        } catch (ProtocolException e) {
            log.println("bcurl: protocol error: " + e.getMessage());
        } catch (IOException e) {
            log.println("bcurl: " + e);
        }
        return EXIT_PROTOCOL;
    }

    private int usage() {
        log.println("usage: bcurl [-v | -vv] [-I] host[:port]/path [/more/paths ...]");
        return EXIT_USAGE;
    }

    /** Accepts "host:port/path", "host/path" or "bhtp://host:port/path". */
    static Target parseTarget(String s) {
        if (s.startsWith("bhtp://"))
            s = s.substring("bhtp://".length());
        int slash = s.indexOf('/');
        String hostPort = slash < 0 ? s : s.substring(0, slash);
        String path = slash < 0 ? "/" : s.substring(slash);

        String host = hostPort;
        int port = DEFAULT_PORT;
        int colon = hostPort.lastIndexOf(':');
        if (colon >= 0) {
            host = hostPort.substring(0, colon);
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad port in '" + s + "'");
            }
        }
        if (host.isEmpty())
            throw new IllegalArgumentException("no host in '" + s + "'");
        return new Target(host, port, path);
    }

    private int fetchAll(Socket s, Target t, List<String> paths) throws IOException {
        s.setTcpNoDelay(true);
        InputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream out = new BufferedOutputStream(s.getOutputStream());

        // preface + first request go out together; no need to wait for the server's preface first
        out.write(Proto.PREFACE);
        dumpRaw("> preface", Proto.PREFACE);

        boolean gotPreface = false;
        int worst = 0;
        int streamId = 0;
        for (String path : paths) {
            streamId++;
            Frame req = buildRequest(streamId, head ? Proto.HEAD : Proto.GET, path, t.host + ":" + t.port);
            dump(">", req);
            req.writeTo(out);
            out.flush();

            if (!gotPreface) {
                byte[] p = in.readNBytes(Proto.PREFACE.length);
                dumpRaw("< preface", p);
                if (!Arrays.equals(p, Proto.PREFACE))
                    throw new ProtocolException("server did not answer with a BHTP/1 preface (got \""
                            + new String(p, StandardCharsets.US_ASCII).replaceAll("[^\\x20-\\x7e]", ".") + "\")");
                gotPreface = true;
            }
            worst = Math.max(worst, readResponse(in, streamId));
        }
        if (worst >= 500)
            return EXIT_5XX;
        if (worst >= 400)
            return EXIT_4XX;
        return EXIT_OK;
    }

    static Frame buildRequest(int id, int method, String path, String host) {
        byte[] p = path.getBytes(StandardCharsets.UTF_8);
        if (p.length > 0xFFFF)
            throw new IllegalArgumentException("path is longer than 65535 bytes");

        // payload: [method:1][path_len:2][path][header block...]
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(method);
        b.write(p.length >>> 8);
        b.write(p.length & 0xff);
        b.writeBytes(p);
        new Headers()
                .add("host", host)
                .add("user-agent", "bcurl/1.0")
                .add("accept", "*/*")
                .encode(b);
        // v1 requests never have a body, so the REQUEST frame is also the last frame
        return new Frame(Proto.REQUEST, Proto.FLAG_END, id, b.toByteArray());
    }

    /** Reads frames until END on our stream. Returns the status code. */
    private int readResponse(InputStream in, int streamId) throws IOException {
        int status = -1;
        long expected = -1, received = 0;

        while (true) {
            Frame f = Frame.read(in);
            if (f == null)
                throw new ProtocolException("server closed the connection in the middle of a response");
            dump("<", f);

            if (f.type != Proto.RESPONSE && f.type != Proto.DATA && f.type != Proto.REQUEST) {
                if (verbose > 0)
                    log.printf("* skipping unknown frame type 0x%02x%n", f.type);
                continue;
            }
            if (f.type == Proto.REQUEST)
                throw new ProtocolException("server sent a REQUEST frame");
            if (f.streamId != streamId)
                throw new ProtocolException("expected stream " + streamId + " but got " + f.streamId);

            if (f.type == Proto.RESPONSE) {
                if (status != -1)
                    throw new ProtocolException("two RESPONSE frames for stream " + streamId);
                ByteBuffer buf = ByteBuffer.wrap(f.payload);
                Headers h;
                try {
                    status = buf.getShort() & 0xffff;
                    h = Headers.decode(buf);
                } catch (BufferUnderflowException e) {
                    throw new ProtocolException("RESPONSE frame is too short");
                }
                if (verbose > 0) {
                    log.println("< " + status + " " + Proto.reason(status));
                    for (Headers.Field fld : h.fields())
                        log.println("< " + fld.name() + ": " + fld.value());
                }
                String cl = h.get("content-length");
                if (cl != null) {
                    try {
                        expected = Long.parseLong(cl.trim());
                    } catch (NumberFormatException ignored) {
                        // just means we can't sanity check the length
                    }
                }
            } else {
                if (status == -1)
                    throw new ProtocolException("DATA frame arrived before the RESPONSE frame");
                body.write(f.payload);
                received += f.payload.length;
            }

            if (f.has(Proto.FLAG_END))
                break;
        }
        body.flush();

        if (!head && expected >= 0 && received != expected)
            log.println("bcurl: warning: content-length was " + expected + " but got " + received + " bytes");
        if (status >= 400 && verbose == 0)
            log.println("bcurl: server returned " + status + " " + Proto.reason(status));
        return status;
    }

    // ---------------------------------------------------------------- -v output

    private void dump(String dir, Frame f) {
        if (verbose == 0)
            return;
        log.println(dir + " " + f);
        byte[] raw = f.toBytes();
        int limit = verbose >= 2 ? raw.length : Math.min(raw.length, Proto.HEADER_SIZE + DUMP_LIMIT);
        log.print(Hex.dump(raw, limit));
        if (limit < raw.length)
            log.println("        ... " + (raw.length - limit) + " more bytes (-vv to show)");
    }

    private void dumpRaw(String label, byte[] b) {
        if (verbose == 0)
            return;
        log.println(label + " (" + b.length + " bytes)");
        log.print(Hex.dump(b, b.length));
    }
}
