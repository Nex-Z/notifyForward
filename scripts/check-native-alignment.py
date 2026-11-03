"""Check every 64-bit native library's ELF LOAD and uncompressed APK alignment.

Usage: py scripts/check-native-alignment.py app/build/outputs/apk/debug/app-debug.apk
16 KB Android devices use arm64-v8a or x86_64; 32-bit ABIs remain supported separately.
"""
import struct
import sys
import zipfile


def check(apk):
    count = 0
    with zipfile.ZipFile(apk) as archive, open(apk, "rb") as raw:
        for entry in archive.infolist():
            if not entry.filename.startswith(("lib/arm64-v8a/", "lib/x86_64/")) or not entry.filename.endswith(".so"):
                continue
            data = archive.read(entry)
            assert data[:6] == b"\x7fELF\x02\x01", f"Unsupported ELF: {entry.filename}"
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, number = struct.unpack_from("<HH", data, 54)
            loads = []
            for index in range(number):
                header = struct.unpack_from("<IIQQQQQQ", data, offset + index * size)
                if header[0] == 1:
                    loads.append(header)
                    assert header[7] >= 16384 and (header[3] - header[2]) % 16384 == 0, f"ELF LOAD not aligned: {entry.filename}"
            assert loads, f"No LOAD segments: {entry.filename}"
            assert entry.compress_type == zipfile.ZIP_STORED, f"Expected uncompressed library: {entry.filename}"
            raw.seek(entry.header_offset + 26)
            name_length, extra_length = struct.unpack("<HH", raw.read(4))
            assert (entry.header_offset + 30 + name_length + extra_length) % 16384 == 0, f"APK entry not aligned: {entry.filename}"
            print(f"PASS {entry.filename}: ELF LOAD + APK entry aligned to 16 KB")
            count += 1
    assert count, "No 64-bit native libraries found"
    print(f"PASS: {count} native libraries; runtime testing is still required")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    check(sys.argv[1])
