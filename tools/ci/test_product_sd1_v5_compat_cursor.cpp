#include "../../sim/product_sd1_cursor_query_reply.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>

using aethercore::sd1::CursorQueryReply;

struct FrozenObserverOverlay {
  // These are the three OLD observer words, not new Host fields. All zero in
  // official v4 O states, because UART policy has no trigger or input.
  std::uint64_t seipAtInputStart=0;
  std::uint64_t inputCompleteCycle=0;
  std::uint64_t postInputDeadline=0;
  bool inputStarted=false;
  std::uint64_t inputIndex=0;
};

static CursorQueryReply read(const FrozenObserverOverlay& o) {
  return {o.seipAtInputStart,o.inputCompleteCycle,o.postInputDeadline};
}
static void save(FrozenObserverOverlay& o,const CursorQueryReply& c) {
  o.seipAtInputStart=c.observedQueries;
  o.inputCompleteCycle=c.acceptedBytes;
  o.postInputDeadline=c.matchedPrefix;
}
static void feed(CursorQueryReply& c,std::string_view s,bool native,bool login=false) {
  for (unsigned char ch:s) c.observeGuestTx(ch,native,login);
}
static std::string accept(CursorQueryReply& c) {
  std::string reply;
  while (c.pending()) {
    reply.push_back(static_cast<char>(c.nextByte()));
    c.accepted();
  }
  return reply;
}

int main() {
  FrozenObserverOverlay o;
  std::array<std::uint64_t,3> oldBytes={o.seipAtInputStart,
      o.inputCompleteCycle,o.postInputDeadline};
  // With genuine v4-empty UART policy, all pre-query cycles leave the full
  // serialized observer byte layout AND values unchanged.
  auto c=read(o);
  feed(c,"boot\x1b[6n",false);
  feed(c,"Run /sbin/init as init process ... no cursor\x1b[5n",true);
  save(o,c);
  assert(c.valid() && !c.pending());
  assert((oldBytes==std::array<std::uint64_t,3>{o.seipAtInputStart,
      o.inputCompleteCycle,o.postInputDeadline}));
  assert(!o.inputStarted && o.inputIndex==0);

  // Exact guest TX query, nothing otherwise; one reply at a time and only
  // after ready/valid handshake accepts bytes.
  feed(c,"\x1b[6n",true);
  assert(c.pending() && c.nextByte()==0x1b);
  save(o,c);
  auto resumed=read(o);
  assert(resumed.valid() && resumed.nextByte()==0x1b);
  resumed.accepted();
  save(o,resumed);
  auto resumedAgain=read(o);
  assert(resumedAgain.nextByte()=='[');
  assert(std::string(1,'\x1b')+accept(resumedAgain)==CursorQueryReply::kReply);
  save(o,resumedAgain);
  assert(!read(o).pending() && !o.inputStarted && o.inputIndex==0);

  // Repeated interactive requests are supported without pre-seeded RX.
  auto repeated=read(o);
  for (int i=0;i<4;++i) feed(repeated,"\x1b[6n",true);
  assert(accept(repeated)==std::string(CursorQueryReply::kReply).append(
      CursorQueryReply::kReply).append(CursorQueryReply::kReply).append(
      CursorQueryReply::kReply));
  feed(repeated,"\x1b[6n",true,true);
  assert(!repeated.pending());

  CursorQueryReply bounded;
  for (int i=0;i<256;++i) feed(bounded,"\x1b[6n",true);
  assert(bounded.valid());
  bool stopped=false;
  try { feed(bounded,"\x1b[6n",true); }
  catch (const std::runtime_error&) { stopped=true; }
  assert(stopped);
  bounded.matchedPrefix=5;
  assert(!bounded.valid());
  return 0;
}
