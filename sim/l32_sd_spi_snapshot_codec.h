#pragma once

// Standalone, endian-stable SD-card checkpoint encoding primitives. No RTL,
// OpenSSL, host pointer serialization, or external runtime dependencies.
#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <limits>
#include <span>
#include <stdexcept>
#include <string>
#include <vector>

namespace aethercore::l32sim::snapshot_detail {

class Sha256 {
 public:
  void update(std::span<const std::uint8_t> bytes) {
    if (bytes.size() > (std::numeric_limits<std::uint64_t>::max() / 8) - length_)
      throw std::runtime_error("SD checkpoint SHA256 input too large");
    length_ += bytes.size();
    for (const auto byte : bytes) {
      block_[used_++] = byte;
      if (used_ == block_.size()) { compress(); used_ = 0; }
    }
  }

  std::array<std::uint8_t, 32> finish() {
    const std::uint64_t bitLength = length_ * 8;
    block_[used_++] = 0x80;
    if (used_ > 56) {
      std::fill(block_.begin() + used_, block_.end(), 0);
      compress();
      used_ = 0;
    }
    std::fill(block_.begin() + used_, block_.begin() + 56, 0);
    for (unsigned i = 0; i < 8; ++i)
      block_[56 + i] = static_cast<std::uint8_t>(bitLength >> (56 - 8 * i));
    compress();
    std::array<std::uint8_t, 32> out{};
    for (unsigned i = 0; i < state_.size(); ++i)
      for (unsigned j = 0; j < 4; ++j)
        out[4 * i + j] = static_cast<std::uint8_t>(state_[i] >> (24 - 8 * j));
    return out;
  }

 private:
  static constexpr std::array<std::uint32_t, 64> K{
      0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
      0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
      0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
      0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
      0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
      0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
      0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
      0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
  static std::uint32_t ror(std::uint32_t x, unsigned n) { return (x >> n) | (x << (32 - n)); }
  void compress() {
    std::array<std::uint32_t, 64> w{};
    for (unsigned i = 0; i < 16; ++i)
      w[i] = (std::uint32_t(block_[4*i]) << 24) | (std::uint32_t(block_[4*i+1]) << 16) |
          (std::uint32_t(block_[4*i+2]) << 8) | block_[4*i+3];
    for (unsigned i = 16; i < 64; ++i) {
      const auto s0 = ror(w[i-15],7) ^ ror(w[i-15],18) ^ (w[i-15] >> 3);
      const auto s1 = ror(w[i-2],17) ^ ror(w[i-2],19) ^ (w[i-2] >> 10);
      w[i] = w[i-16] + s0 + w[i-7] + s1;
    }
    auto a=state_[0], b=state_[1], c=state_[2], d=state_[3];
    auto e=state_[4], f=state_[5], g=state_[6], h=state_[7];
    for (unsigned i = 0; i < 64; ++i) {
      const auto S1 = ror(e,6) ^ ror(e,11) ^ ror(e,25);
      const auto ch = (e & f) ^ (~e & g);
      const auto S0 = ror(a,2) ^ ror(a,13) ^ ror(a,22);
      const auto maj = (a & b) ^ (a & c) ^ (b & c);
      const auto t1 = h + S1 + ch + K[i] + w[i];
      const auto t2 = S0 + maj;
      h=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
    }
    for (unsigned i = 0; i < 8; ++i)
      state_[i] += std::array<std::uint32_t,8>{a,b,c,d,e,f,g,h}[i];
  }
  std::array<std::uint32_t, 8> state_{0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
                                      0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
  std::array<std::uint8_t, 64> block_{};
  std::size_t used_ = 0;
  std::uint64_t length_ = 0;
};

inline std::array<std::uint8_t,32> sha256(std::span<const std::uint8_t> bytes) {
  Sha256 hash;
  hash.update(bytes);
  return hash.finish();
}

inline std::array<std::uint8_t,32> sha256File(const std::string& path) {
  std::ifstream file(path, std::ios::binary);
  if (!file) throw std::runtime_error("SD checkpoint source image is missing");
  Sha256 hash;
  std::array<std::uint8_t, 64 * 1024> chunk{};
  while (file) {
    file.read(reinterpret_cast<char*>(chunk.data()), chunk.size());
    const auto count = file.gcount();
    if (count > 0) hash.update(std::span(chunk.data(), static_cast<std::size_t>(count)));
  }
  if (!file.eof()) throw std::runtime_error("SD checkpoint failed to hash source image");
  return hash.finish();
}

class Writer {
 public:
  void u8(std::uint8_t n) { out.push_back(n); }
  void u32(std::uint32_t n) { for (unsigned i = 0; i < 4; ++i) u8(n >> (8 * i)); }
  void u64(std::uint64_t n) { for (unsigned i = 0; i < 8; ++i) u8(n >> (8 * i)); }
  void bytes(std::span<const std::uint8_t> b) { out.insert(out.end(), b.begin(), b.end()); }
  std::vector<std::uint8_t> out;
};

class Reader {
 public:
  explicit Reader(std::span<const std::uint8_t> data) : input_(data) {}
  std::size_t remaining() const { return input_.size() - offset_; }
  std::span<const std::uint8_t> bytes(std::size_t size) {
    if (size > remaining()) throw std::runtime_error("SD checkpoint truncated/length-invalid");
    const auto part = input_.subspan(offset_, size);
    offset_ += size;
    return part;
  }
  std::uint8_t u8() { return bytes(1)[0]; }
  std::uint32_t u32() {
    std::uint32_t v=0;
    for (unsigned i=0; i<4; ++i) v |= std::uint32_t(u8()) << (8*i);
    return v;
  }
  std::uint64_t u64() {
    std::uint64_t v=0;
    for (unsigned i=0; i<8; ++i) v |= std::uint64_t(u8()) << (8*i);
    return v;
  }
  bool flag() {
    const auto v = u8();
    if (v > 1) throw std::runtime_error("SD checkpoint invalid boolean");
    return v == 1;
  }
 private:
  std::span<const std::uint8_t> input_;
  std::size_t offset_ = 0;
};

}  // namespace aethercore::l32sim::snapshot_detail
