import struct, re, sys

path = './nand/00000000000000000000000000000000/title/0004009b/00014002/content/00000001.app'
f = open(path, 'rb')
data = f.read()
print("file size:", len(data))
print("first 0x200 magic:", data[0:16].hex())
print("NCCH at:", data.find(b'NCCH'), "NCSD at:", data.find(b'NCSD'))

for off in (0x104, 0x108, 0x110, 0x11c, 0x180, 0x184, 0x188, 0x18c, 0x1a0, 0x1b0):
    if off + 4 <= len(data):
        print(hex(off), data[off:off+4].hex(), struct.unpack_from('<I', data, off)[0])

print("product code:", data[0x120:0x135].decode('ascii', errors='ignore'))
for m in re.finditer(b'SMDH', data):
    print("SMDH found at offset", hex(m.start()))