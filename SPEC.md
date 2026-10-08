# BHTP/1: a binary framing for HTTP

**Author:** Aadit Dhariwal (24BCS10169)

**Status:** version 1. The words MUST, SHOULD and MAY mean what they mean in RFC 2119.
All integers are unsigned and **big-endian**. Text is UTF-8 unless stated.

## 1. Overview

BHTP/1 carries HTTP-style GET/HEAD requests over one TCP connection, using binary frames
instead of text lines. A client opens a connection, sends a preface, then sends requests
**one at a time** and reads each response before sending the next request. The connection
stays open for as many requests as the client wants. There is no well-known port; the examples use 9000.

## 2. Connection

1. The client connects and sends the 8-byte **preface** `42 48 54 50 2F 31 0D 0A` (`BHTP/1\r\n`).
   It MAY send its first frame right after the preface, without waiting.
2. The server reads 8 bytes. If they are not exactly the preface, it MUST close the
   connection without sending anything. Otherwise it MUST send the same 8 bytes back.
3. The client MUST check the server's preface before it reads any frame. If the check fails, it is not
   talking to a BHTP/1 server and MUST treat that as a protocol error.
4. Frames follow in both directions. The client closes the TCP connection when it is done.
   The server MAY close a connection that has been idle for 30 s. Ending the TCP stream between frames is a
   clean close. Ending it inside a frame means the frame was truncated.

## 3. Frame header

Every frame is an 8-byte header followed by `Length` bytes of payload.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-------------------------------+---------------+---------------+
|          Length (16)          |   Type (8)    |   Flags (8)   |
+-------------------------------+---------------+---------------+
|                        Stream ID (32)                         |
+---------------------------------------------------------------+
|                    Payload (Length bytes) ...                 |
```

| Field | Bits | Meaning |
|---|---|---|
| Length | 16 | Payload size in bytes, 0-65535. The 8 header bytes are not counted. |
| Type | 8 | What the payload is (section 4). |
| Flags | 8 | `0x01` END: this is the last frame of the message. Other bits are reserved. Senders MUST send them as 0, and receivers MUST ignore them. |
| Stream ID | 32 | Identifies one request and its response. The client uses 1 for its first request and adds 1 for each request after that. IDs MUST NOT be reused on a connection. Every frame of a response carries the ID of its request. 0 is reserved for future connection-level frames. |

**Why these widths** (HTTP/2 uses 24/8/8/31, which makes a 9-byte header):

- **Length = 16 bits.** Bodies are split into DATA frames anyway, so one frame never needs to
  be large. HTTP/2 has a 24-bit field, but its default maximum frame size is 16 KiB, and going higher takes a
  SETTINGS exchange. BHTP/1 has no SETTINGS, so the field width *is* the limit. A receiver can always
  buffer a whole frame, including one it is skipping, in 64 KiB. A bad or hostile length can't
  make it allocate 16 MiB. The cost is that one header block can't exceed 64 KiB, which is
  larger than the 8-16 KiB header limit common in HTTP servers.
- **Type = 8 bits.** v1 uses 3 of the 256 values. The rest are room for v2.
- **Flags = 8 bits.** v1 only uses END, but a flags byte costs one byte and saves adding a new frame type
  for each yes/no option later.
- **Stream ID = 32 bits.** In v1 it checks that a response belongs to the request the client sent, which catches
  desyncs. It is also what would let v2 multiplex several requests without changing the header.
  I chose 32 rather than 16 bits because IDs are never reused. A long keep-alive connection can pass 65,535 requests, but
  not 4 billion. HTTP/2's reserved bit is not needed here. With it gone, the header is 8 bytes, a whole 64-bit word.

## 4. Frame types

| Type | Name | Sent by | Payload |
|---|---|---|---|
| `0x01` | REQUEST | client | `method:1` `path_len:2` `path` `header block` |
| `0x02` | RESPONSE | server | `status:2` `header block` |
| `0x03` | DATA | server | raw body bytes |

- **method:** `1` = GET, `2` = HEAD.
- **path:** raw UTF-8, length-prefixed. It is *not* percent-encoded. It MUST start with `/`, and a
  `?query` part is ignored. Because every string is length-prefixed, a path or header value can
  contain any byte, and CRLF header injection is impossible by construction.
- v1 requests have no body, so a REQUEST frame MUST carry END.
- A response is one RESPONSE frame followed by zero or more DATA frames, all on the request's stream.
  The **last frame carries END**. If there is no body (HEAD, or an empty file), END goes on the RESPONSE frame itself.
  The body is the DATA payloads joined in order. `content-length` is informational. The client
  uses END, not the header, to know the body is complete.

> **Unknown frame types.** A receiver that gets a frame whose Type it does not know MUST read
> and discard `Length` payload bytes, then carry on as if the frame had never arrived. It MUST NOT
> close the connection or send an error. This works because Length is in the same place in every
> frame, whatever its type. This rule is what lets a v1 peer coexist with a v2 one.

A server that receives a *known* type a client must not send (RESPONSE or DATA) answers with 400 on that stream.

## 5. Header block

A header block is a sequence of entries that runs to the end of the frame payload. There is no count field.
This is HPACK's first two mechanisms: a static table and string literals. It has no dynamic table and no Huffman coding.

```
entry   = index:1  [ name_len:1  name ]  value_len:2  value
          (name_len and name are present only when index = 0)
```

- **index 1-10** use the static table below, and the name costs 1 byte.
- **index 0** means a literal name follows. `name_len` is 1-255, and the name is lowercase ASCII.
- **index 11-255** are reserved for future table entries. A receiver MUST skip the entry: it
  reads `value_len` and the value, then drops the entry. Every entry ends in a length-prefixed value, so this is always possible.

| # | name | # | name |
|---|---|---|---|
| 1 | host | 6 | date |
| 2 | user-agent | 7 | last-modified |
| 3 | accept | 8 | etag |
| 4 | content-type | 9 | server |
| 5 | content-length | 10 | allow |

These are exactly the ten names bcurl and bserve send. Indices 1-3 appear in requests, and 4-10 in responses.
Header names are case-insensitive and sent in lowercase. The same name MAY appear more than once.

## 6. Server behaviour

The server maps a path to `<root>/<path>` after normalising it, so `..` segments are collapsed.

| Situation | Status |
|---|---|
| File exists and is readable | **200**, with `content-type`, `content-length`, `last-modified`, `etag`, `date` and `server` |
| Path is a directory | serve `index.html` inside it |
| No such file | **404** |
| Path resolves outside the root (`/../x`, a symlink out of the root, an absolute path) | **403** |
| Payload truncated, bad header block, path missing its leading `/`, stream ID 0, RESPONSE or DATA sent by a client | **400** |
| Method not 1 or 2 | **405**, with `allow: GET, HEAD` |

Error responses carry a short `text/plain` body explaining the error, except for HEAD requests, which get no body.
A 400 is sent on the offending stream, and the **connection stays open**. The frame header was valid, so the
two sides still agree where the next frame starts.

## 7. Errors, summarised

| Receiver sees | Action |
|---|---|
| Unknown frame type | skip `Length` bytes (both sides) |
| Unknown flag bits / unknown header index | ignore the bits / skip the entry |
| Malformed REQUEST payload | 400 on that stream, keep going |
| Wrong preface | close (server) / protocol error (client) |
| Frame on an unexpected stream, DATA before RESPONSE, REQUEST from a server | client protocol error, close |
| TCP closed mid-frame | close |

## 8. Room for version 2

Changes a v1 peer can safely ignore go inside v1 as new frame types, flags or header indices.
Some examples are PING, GOAWAY (graceful shutdown), CANCEL, request bodies (a REQUEST without END, followed by DATA),
and multiplexing several streams at once using the ID that is already in the header. A change v1 peers *cannot* ignore
gets a new preface, `BHTP/2\r\n`. A v1 server closes the connection on that preface, and the client can fall back.
