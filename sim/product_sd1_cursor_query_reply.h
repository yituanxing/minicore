#pragma once

#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string_view>

namespace aethercore::sd1 {

// Host-only terminal behavior: reply only to a complete *observed* guest CSI 6n.
// This never synthesizes a boot, service or login message.
struct CursorQueryReply {
  static constexpr std::string_view kQuery = "\x1b[6n";
  static constexpr std::string_view kReply = "\x1b[24;80R";
  static constexpr std::uint64_t kMaxReplies = 256;

  std::uint64_t observedQueries = 0;
  std::uint64_t acceptedBytes = 0;
  std::uint64_t matchedPrefix = 0;

  void observeGuestTx(std::uint8_t ch, bool nativePid1Seen, bool loginSeen) {
    if (!nativePid1Seen || loginSeen) {
      matchedPrefix = 0;
      return;
    }
    if (ch == static_cast<std::uint8_t>(kQuery[matchedPrefix])) {
      ++matchedPrefix;
      if (matchedPrefix == kQuery.size()) {
        matchedPrefix = 0;
        if (observedQueries == kMaxReplies)
          throw std::runtime_error("SD1 terminal cursor-query limit exceeded");
        ++observedQueries;
      }
    } else {
      matchedPrefix = ch == static_cast<std::uint8_t>(kQuery[0]) ? 1 : 0;
    }
  }

  bool pending() const { return acceptedBytes < observedQueries * kReply.size(); }

  std::uint8_t nextByte() const {
    if (!pending()) throw std::runtime_error("no observed cursor query pending");
    return static_cast<std::uint8_t>(kReply[acceptedBytes % kReply.size()]);
  }

  void accepted() {
    if (!pending()) throw std::runtime_error("unrequested SD1 UART RX acceptance");
    ++acceptedBytes;
  }

  bool valid() const {
    return observedQueries <= kMaxReplies && matchedPrefix < kQuery.size() &&
           acceptedBytes <= observedQueries * kReply.size();
  }
};

}  // namespace aethercore::sd1
