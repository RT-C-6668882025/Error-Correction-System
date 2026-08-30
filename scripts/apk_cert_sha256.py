#!/usr/bin/env python3
"""打印 APK 签名证书的 SHA-256 指纹（小写十六进制，无冒号）。

apksigner 只在装了 Android SDK build-tools 的机器上有；这个脚本只用标准库，
直接读 APK Signing Block v2/v3 里的证书 DER，所以任何装了 Python 3 的地方
都能自查一个下载来的包到底是不是同一张证书签的。

minSdk 26 的包默认不带 v1（META-INF/*.RSA）签名，因此这里不做 PKCS#7 回退：
读不到 v2/v3 就直接报错，而不是给出一个看起来像指纹的错值。
"""
import hashlib
import struct
import sys

MAGIC = b"APK Sig Block 42"
SCHEME_IDS = {0x7109871A: "v2", 0xF05368C0: "v3"}


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def u64(b, o):
    return struct.unpack_from("<Q", b, o)[0]


def central_directory_offset(data):
    """从尾部倒着找 EOCD 签名，取中央目录偏移。"""
    for i in range(len(data) - 22, max(len(data) - 65558, -1), -1):
        if data[i:i + 4] == b"PK\x05\x06":
            return u32(data, i + 16)
    raise ValueError("不是有效的 ZIP：找不到 EOCD")


def signing_block(data):
    """签名块紧挨在中央目录前面：长度 | 键值对 | 长度 | magic。"""
    cd = central_directory_offset(data)
    if data[cd - 16:cd] != MAGIC:
        raise ValueError("没有 APK Signing Block（包只有 v1 签名，或不是 APK）")
    size = u64(data, cd - 24)
    start = cd - 8 - size
    if u64(data, start) != size:
        raise ValueError("签名块首尾长度不一致，包可能被改过")
    return data[start + 8:cd - 24]


def id_value_pairs(block):
    off = 0
    while off < len(block):
        length = u64(block, off)
        yield u32(block, off + 8), block[off + 12:off + 8 + length]
        off += 8 + length


def length_prefixed(buf):
    """u32 长度前缀序列，v2/v3 的编码到处都是这个形状。"""
    off = 0
    while off < len(buf):
        n = u32(buf, off)
        yield buf[off + 4:off + 4 + n]
        off += 4 + n


def certificates(value):
    """signers → signer → signed data → certificates → 每张 X.509 DER。"""
    for signers in length_prefixed(value):
        for signer in length_prefixed(signers):
            parts = list(length_prefixed(signer))
            if not parts:
                continue
            signed_data = list(length_prefixed(parts[0]))
            if len(signed_data) < 2:
                continue
            # [0] 是各分块摘要，[1] 才是证书链
            yield from length_prefixed(signed_data[1])


def fingerprints(path):
    data = open(path, "rb").read()
    out = []
    for scheme_id, value in id_value_pairs(signing_block(data)):
        scheme = SCHEME_IDS.get(scheme_id)
        if scheme:
            for cert in certificates(value):
                out.append((scheme, hashlib.sha256(cert).hexdigest()))
    if not out:
        raise ValueError("签名块里没有 v2/v3 签名")
    return out


def main():
    if len(sys.argv) != 2:
        print("用法：apk_cert_sha256.py <apk>", file=sys.stderr)
        return 2
    try:
        found = fingerprints(sys.argv[1])
    except (OSError, ValueError, struct.error) as e:
        print(f"读不出签名证书：{e}", file=sys.stderr)
        return 1
    # 同一张证书会在 v2 与 v3 里各出现一次，去重后正常只剩一行
    for fp in dict.fromkeys(fp for _, fp in found):
        print(fp)
    return 0


if __name__ == "__main__":
    sys.exit(main())
