"""Gera uma "foto de grupo" PNG (cores lisas, bem pequena) em base64, sem dependências."""
import base64, struct, zlib
W = H = 96
rows = []
for y in range(H):
    row = bytearray([0])
    for x in range(W):
        r, g, b = (232, 129, 75) if y < H // 2 else (200, 69, 46)
        for cx, cy, rad, col in ((32, 40, 16, (255, 238, 214)), (64, 40, 16, (250, 224, 200)), (48, 78, 30, (40, 70, 60))):
            if (x - cx) ** 2 + (y - cy) ** 2 < rad * rad:
                r, g, b = col
        row += bytes((r, g, b))
    rows.append(bytes(row))
def chunk(t, d):
    return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b''.join(rows), 9)) + chunk(b'IEND', b'')
print(base64.b64encode(png).decode())
