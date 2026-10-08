# BHTP/1: HTTP, in binary

Aadit Dhariwal, 24BCS10169

Network Architecture course project. `bserve` is a static file server and `bcurl` is a command-line
client. They speak **BHTP/1**, a small binary framing of HTTP that I designed. The protocol is defined in
[SPEC.md](SPEC.md), and [HEXDUMP.md](HEXDUMP.md) walks through one complete request and response byte by byte.

I did this project solo, so both tracks (server and client) are mine. To keep them honest to the spec rather
than to each other, the tests talk to the server with hand-built frames instead of going through bcurl.

Needs Java 17 or newer. No libraries.

## Build and run

```sh
./build.sh                      # or build.bat on Windows cmd
./bserve ./www 9000             # terminal 1
./bcurl -v localhost:9000/index.html          # terminal 2
```

```
bcurl [-v | -vv] [-I] host[:port]/path [/more/paths ...]
  -v    hexdump every frame (payload cut at 64 bytes)
  -vv   hexdump every frame in full
  -I    HEAD instead of GET
```

- The body goes to **stdout**. `-v` output and errors go to **stderr**, so `./bcurl host:9000/a.png > a.png` works.
- Extra paths are fetched over the **same connection**:
  `./bcurl localhost:9000/index.html /style.css /docs/`. A URL with a different host or port is refused
  rather than opening a second connection.
- Exit status: `0` ok, `1` usage error, `2` network or protocol error, `4` if any response was 4xx, `5` if any was 5xx.

## Tests

```sh
./test.sh                       # or test.bat
```

`test/bhtp/ProtocolTest.java` builds frames **by hand**, so it checks the server against the spec rather than
against my own client. It covers the unknown-frame-type skip rule (both directions), malformed frames getting a 400
while the connection stays open, path traversal, HEAD, empty and large (multi-frame) files, unknown header indices,
a bad preface, and bcurl using exactly one connection.

## Layout

| File | What |
|---|---|
| `src/bhtp/Frame.java` | 8-byte frame header: read and write |
| `src/bhtp/Headers.java` | header block: static table and literals |
| `src/bhtp/Proto.java` | constants: preface, types, flags, methods |
| `src/bhtp/Hex.java` | hexdump for `-v` |
| `src/bhtp/BServe.java` | server |
| `src/bhtp/BCurl.java` | client |
| `www/` | sample site |
