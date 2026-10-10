import Foundation
import CryptoKit

func sha256Bytes(_ data: Data) -> [UInt8] { Array(SHA256.hash(data: data)) }

func hexString(_ bytes: some Sequence<UInt8>) -> String {
    let digits = Array("0123456789abcdef".utf8)
    var out: [UInt8] = []
    for b in bytes { out.append(digits[Int(b >> 4)]); out.append(digits[Int(b & 0x0F)]) }
    return String(decoding: out, as: UTF8.self)
}

func sha256Hex(_ data: Data) -> String { hexString(sha256Bytes(data)) }
