# -*- coding: utf-8 -*-
"""生成 HaoAI PC 客户端的 app.ico：绿底圆角方 + 白色 H。
不依赖 Pillow：PNG 用 zlib+struct 手写，ICO 里 32px 走 BMP 帧（.NET System.Drawing
的 Icon 只认 BMP 帧）、256px 走 PNG 帧（Vista+ 的标准做法，资源管理器认）。"""
import struct, zlib

GREEN = (47, 189, 127, 255)     # #2fbd7f
GREEN_DK = (31, 111, 74, 255)   # #1f6f4a（底部渐变端）
WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)

def rr_mask(x, y, size, r):
    """圆角方内的判断：四角圆，其余矩形。"""
    if x < r and y < r:
        return (x - r) ** 2 + (y - r) ** 2 <= r * r
    if x >= size - r and y < r:
        return (x - (size - 1 - r)) ** 2 + (y - r) ** 2 <= r * r
    if x < r and y >= size - r:
        return (x - r) ** 2 + (y - (size - 1 - r)) ** 2 <= r * r
    if x >= size - r and y >= size - r:
        return (x - (size - 1 - r)) ** 2 + (y - (size - 1 - r)) ** 2 <= r * r
    return True

def draw(size):
    px = []
    r = round(size * 0.22)
    # H 的三笔（比例随尺寸缩放）：左柱 / 右柱 / 横梁
    bw = max(2, round(size * 0.13))          # 柱宽
    x1 = round(size * 0.28)
    x2 = size - x1 - bw
    by1 = round(size * 0.26)
    by2 = size - by1 - bw
    for y in range(size):
        row = []
        for x in range(size):
            if not rr_mask(x, y, size, r):
                row.append(CLEAR); continue
            # 轻微上浅下深，图标不至于死平
            t = y / size
            col = tuple(round(GREEN[i] + (GREEN_DK[i] - GREEN[i]) * t * 0.6) for i in range(3)) + (255,)
            if by1 <= y < by1 + bw:  # 横梁整行
                if x1 <= x < x2 + bw: row.append(WHITE); continue
            if x1 <= x < x1 + bw and by1 <= y < by2 + bw: row.append(WHITE); continue
            if x2 <= x < x2 + bw and by1 <= y < by2 + bw: row.append(WHITE); continue
            row.append(col)
        px.append(row)
    return px

def png(size, px):
    def chunk(t, d):
        return (struct.pack('>I', len(d)) + t + d
                + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff))
    raw = b''.join(b'\x00' + bytes(v for p in row for v in p) for row in px)
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))

def bmp_frame(size, px):
    """BMP-in-ICO：信息头高度翻倍、BGRA 自底向上、行尾 4 字节对齐。"""
    stride = (size * 4 + 3) & ~3
    hsize = 40
    data = struct.pack('<IiiHHIIiiII', hsize, size, size * 2, 1, 32, 0,
                       size * stride, 0, 0, 0, 0)
    for y in range(size - 1, -1, -1):          # 自底向上
        row = b''
        for x in range(size):
            r, g, b, a = px[y][x]
            row += struct.pack('BBBB', b, g, r, a)
        data += row.ljust(stride, b'\x00')
    # 全 1 的 AND 掩码（用 alpha 通道，掩码不裁）
    data += b'\xff' * (size * ((size + 31) // 32) * 4)
    return data

def ico(entries):
    """entries: [(size, bytes_frame)] 按目录项顺序拼。"""
    n = len(entries)
    out = struct.pack('<HHH', 0, 1, n)
    off = 6 + 16 * n
    body = b''
    for size, frame in entries:
        out += struct.pack('<BBBBHHII', size % 256, size % 256, 0, 0, 1, 32,
                           len(frame), off)
        body += frame
        off += len(frame)
    return out + body

s256 = draw(256)
s32 = draw(32)
data = ico([(32, bmp_frame(32, s32)), (256, png(256, s256))])
import os
os.makedirs(os.path.dirname(os.path.abspath(__file__)) , exist_ok=True)
with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'app.ico'), 'wb') as f:
    f.write(data)
print('app.ico written,', len(data), 'bytes')
