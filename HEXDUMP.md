# Annotated hexdump: `GET /hello.txt`

Captured with `./bcurl -vv localhost:9000/hello.txt` against `./bserve ./www 9000`. `www/hello.txt`
contains the 3 bytes `hi\n`. Every byte that crossed the wire is listed below, in order.

Totals: the client sent **64 bytes** (8 preface + 56 frame). The server sent **154 bytes** (8 preface + 135 + 11).

## Raw capture

```
> preface (8 bytes)
  0000  42 48 54 50 2f 31 0d 0a                           |BHTP/1..|
> REQUEST stream=1 flags=END len=48
  0000  00 30 01 01 00 00 00 01  01 00 0a 2f 68 65 6c 6c  |.0........./hell|
  0010  6f 2e 74 78 74 01 00 0e  6c 6f 63 61 6c 68 6f 73  |o.txt...localhos|
  0020  74 3a 39 30 30 30 02 00  09 62 63 75 72 6c 2f 31  |t:9000...bcurl/1|
  0030  2e 30 03 00 03 2a 2f 2a                           |.0...*/*|
< preface (8 bytes)
  0000  42 48 54 50 2f 31 0d 0a                           |BHTP/1..|
< RESPONSE stream=1 flags=- len=127
  0000  00 7f 02 00 00 00 00 01  00 c8 04 00 19 74 65 78  |.............tex|
  0010  74 2f 70 6c 61 69 6e 3b  20 63 68 61 72 73 65 74  |t/plain; charset|
  0020  3d 75 74 66 2d 38 05 00  01 33 07 00 1c 54 68 75  |=utf-8...3...Thu|
  0030  2c 20 38 20 4f 63 74 20  32 30 32 36 20 30 38 3a  |, 8 Oct 2026 08:|
  0040  31 34 3a 35 30 20 47 4d  54 08 00 0f 22 33 2d 31  |14:50 GMT..."3-1|
  0050  61 31 31 61 39 34 35 32  33 66 22 06 00 1c 54 68  |a11a94523f"...Th|
  0060  75 2c 20 38 20 4f 63 74  20 32 30 32 36 20 30 38  |u, 8 Oct 2026 08|
  0070  3a 31 36 3a 31 33 20 47  4d 54 09 00 0a 62 73 65  |:16:13 GMT...bse|
  0080  72 76 65 2f 31 2e 30                              |rve/1.0|
< DATA stream=1 flags=END len=3
  0000  00 03 03 01 00 00 00 01  68 69 0a                 |........hi.|
```

## 1. Client preface (client -> server, 8 bytes)

| Offset | Bytes | Meaning |
|---|---|---|
| 00-07 | `42 48 54 50 2f 31 0d 0a` | `BHTP/1\r\n`, the version check (spec §2) |

## 2. REQUEST frame (client -> server, 8 + 48 bytes)

| Offset | Bytes | Field | Meaning |
|---|---|---|---|
| 00-01 | `00 30` | Length | payload is 0x30 = **48** bytes |
| 02 | `01` | Type | **REQUEST** |
| 03 | `01` | Flags | **END**: no request body follows |
| 04-07 | `00 00 00 01` | Stream ID | **1**, the first request on this connection |
| 08 | `01` | method | **GET** |
| 09-0a | `00 0a` | path_len | 10 |
| 0b-14 | `2f 68 65 6c 6c 6f 2e 74 78 74` | path | `/hello.txt` |
| 15 | `01` | header index | static #1 = **host** |
| 16-17 | `00 0e` | value_len | 14 |
| 18-25 | `6c 6f ... 30` | value | `localhost:9000` |
| 26 | `02` | header index | static #2 = **user-agent** |
| 27-28 | `00 09` | value_len | 9 |
| 29-31 | `62 63 ... 30` | value | `bcurl/1.0` |
| 32 | `03` | header index | static #3 = **accept** |
| 33-34 | `00 03` | value_len | 3 |
| 35-37 | `2a 2f 2a` | value | `*/*` |

Check: 1 + 2 + 10 (method, path) + 17 + 12 + 6 (headers) = 48. Each header name cost **one byte** instead of 4-10 bytes of text.

## 3. Server preface (server -> client, 8 bytes)

| Offset | Bytes | Meaning |
|---|---|---|
| 00-07 | `42 48 54 50 2f 31 0d 0a` | Same 8 bytes echoed back. The client now knows it is talking to a BHTP/1 server. |

## 4. RESPONSE frame (server -> client, 8 + 127 bytes)

| Offset | Bytes | Field | Meaning |
|---|---|---|---|
| 00-01 | `00 7f` | Length | 0x7f = **127** bytes |
| 02 | `02` | Type | **RESPONSE** |
| 03 | `00` | Flags | no END, so DATA frames follow |
| 04-07 | `00 00 00 01` | Stream ID | **1**, answering request 1 |
| 08-09 | `00 c8` | status | 0xc8 = **200** |
| 0a | `04` | header index | #4 **content-type** |
| 0b-0c | `00 19` | value_len | 25 |
| 0d-25 | `74 65 78 74 ...` | value | `text/plain; charset=utf-8` |
| 26 | `05` | header index | #5 **content-length** |
| 27-28 | `00 01` | value_len | 1 |
| 29 | `33` | value | `3` |
| 2a | `07` | header index | #7 **last-modified** |
| 2b-2c | `00 1c` | value_len | 28 |
| 2d-48 | `54 68 75 ...` | value | `Thu, 8 Oct 2026 08:14:50 GMT` |
| 49 | `08` | header index | #8 **etag** |
| 4a-4b | `00 0f` | value_len | 15 |
| 4c-5a | `22 33 2d ...` | value | `"3-1a11a94523f"` (size-mtime in hex) |
| 5b | `06` | header index | #6 **date** |
| 5c-5d | `00 1c` | value_len | 28 |
| 5e-79 | `54 68 75 ...` | value | `Thu, 8 Oct 2026 08:16:13 GMT` |
| 7a | `09` | header index | #9 **server** |
| 7b-7c | `00 0a` | value_len | 10 |
| 7d-86 | `62 73 65 ...` | value | `bserve/1.0` |

Check: 2 + 28 + 4 + 31 + 18 + 31 + 13 = 127. The header block has no count field. The parser
stops when it reaches the end of the 127-byte payload.

## 5. DATA frame (server -> client, 8 + 3 bytes)

| Offset | Bytes | Field | Meaning |
|---|---|---|---|
| 00-01 | `00 03` | Length | 3 bytes |
| 02 | `03` | Type | **DATA** |
| 03 | `01` | Flags | **END**: this is the whole body, and the response is complete |
| 04-07 | `00 00 00 01` | Stream ID | 1 |
| 08-0a | `68 69 0a` | body | `hi\n` |

After END, the connection stays open. A second path on the bcurl command line would go out as a REQUEST
with stream ID **2** on this same socket.
