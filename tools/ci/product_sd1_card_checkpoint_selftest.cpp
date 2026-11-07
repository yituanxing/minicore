// Host-only deterministic SDHC card checkpoint regression. No RTL, Linux,
// simulator or external media fixture. Every test uses an independently
// generated sparse 64MiB MBR/ext4-signature image and the actual SPI pins.
#include "l32_sd_spi_card.h"

#include <array>
#include <algorithm>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

using aethercore::l32sim::SdSpiCard;
using aethercore::l32sim::snapshot_detail::sha256;
using aethercore::l32sim::snapshot_detail::sha256File;

namespace {
void require(bool ok, const std::string& description) {
  if (!ok) throw std::runtime_error(description);
}

void makeImage(const std::string& path, bool altered) {
  std::ofstream file(path, std::ios::binary | std::ios::trunc);
  require(bool(file), "create sparse SD image");
  file.seekp((64ULL << 20) - 1);
  file.put('\0');
  std::array<std::uint8_t, 512> mbr{};
  mbr[0x1be + 4] = 0x83;
  mbr[0x1be + 8] = 0x00; mbr[0x1be + 9] = 0x08;  // LBA 2048
  mbr[0x1be + 12] = 0x00; mbr[0x1be + 13] = 0xf8; mbr[0x1be + 14] = 0x01;
  mbr[510] = 0x55; mbr[511] = 0xaa;
  file.seekp(0);
  file.write(reinterpret_cast<const char*>(mbr.data()), mbr.size());
  std::array<std::uint8_t, 512> super{};
  super[56] = 0x53; super[57] = 0xef;
  file.seekp((2048ULL + 2) * 512);
  file.write(reinterpret_cast<const char*>(super.data()), super.size());
  std::array<std::uint8_t,512> data{};
  for (std::size_t i=0; i<data.size(); ++i) data[i]=static_cast<std::uint8_t>(i ^ 0x5a);
  file.seekp(2100ULL * 512);
  file.write(reinterpret_cast<const char*>(data.data()), data.size());
  if (altered) {
    // Same geometry, same MBR/ext4 signatures. Only full-media SHA256 can
    // reject a changed sector far away from bootstrap metadata.
    file.seekp(5000ULL * 512 + 23);
    file.put('\x5a');
  }
  file.close();
  require(bool(file), "flush sparse SD image");
}

std::uint8_t crc7(const std::uint8_t* bytes, std::size_t count) {
  std::uint8_t crc=0;
  for (std::size_t i=0; i<count; ++i) {
    auto next=bytes[i];
    for (int bit=0; bit<8; ++bit) {
      crc<<=1;
      if ((crc^next)&0x80) crc^=9;
      next<<=1;
    }
  }
  return crc&0x7f;
}

std::uint16_t crc16(const std::uint8_t* bytes, std::size_t count) {
  std::uint16_t crc=0;
  for (std::size_t i=0; i<count; ++i) {
    crc^=static_cast<std::uint16_t>(bytes[i])<<8;
    for (int b=0; b<8; ++b)
      crc=static_cast<std::uint16_t>((crc<<1)^((crc&0x8000)?0x1021:0));
  }
  return crc;
}

class Spi {
 public:
  explicit Spi(SdSpiCard& card) : card_(card) {}
  bool selected() const { return selected_; }
  void adoptSelected(bool selected) { selected_=selected; }
  void select() { card_.observe(false,true,false); selected_=true; }
  void deselect() { card_.observe(false,true,true); selected_=false; transfer(0xff); }
  bool bit(bool out) {
    card_.observe(false,out,!selected_);
    card_.observe(true,out,!selected_);
    const bool in=card_.miso();
    card_.observe(false,out,!selected_);
    return in;
  }
  bool highHalf(bool out) {
    card_.observe(false,out,!selected_);
    card_.observe(true,out,!selected_);
    return card_.miso();
  }
  bool lowHalf(bool out) {
    card_.observe(false,out,!selected_);
    return card_.miso();
  }
  void bits(std::uint8_t byte, int first, int afterLast) {
    for (int b=first; b>=afterLast; --b) bit(((byte>>b)&1U)!=0);
  }
  std::uint8_t transfer(std::uint8_t out) {
    std::uint8_t in=0;
    for (int b=7; b>=0; --b) in=static_cast<std::uint8_t>((in<<1)|bit(((out>>b)&1U)!=0));
    return in;
  }
  void preamble() { for (int i=0; i<12; ++i) transfer(0xff); }
  static std::array<std::uint8_t,6> frame(std::uint8_t cmd,std::uint32_t arg) {
    std::array<std::uint8_t,6> v{static_cast<std::uint8_t>(0x40|cmd),
      static_cast<std::uint8_t>(arg>>24),static_cast<std::uint8_t>(arg>>16),
      static_cast<std::uint8_t>(arg>>8),static_cast<std::uint8_t>(arg),0};
    v[5]=static_cast<std::uint8_t>((crc7(v.data(),5)<<1)|1);
    return v;
  }
  std::uint8_t response() {
    for (int n=0; n<20; ++n) {
      const auto v=transfer(0xff);
      if ((v&0x80)==0) return v;
    }
    throw std::runtime_error("SD R1 response timeout");
  }
  std::uint8_t command(std::uint8_t cmd,std::uint32_t arg=0) {
    select();
    const auto f=frame(cmd,arg);
    for (auto v:f) transfer(v);
    return response();
  }
  void token() {
    for (int i=0; i<40; ++i) {
      const auto v=transfer(0xff);
      if (v==0xfe) return;
      require(v==0xff, "invalid block token");
    }
    throw std::runtime_error("SD block token timeout");
  }
  std::array<std::uint8_t,512> read() {
    token();
    std::array<std::uint8_t,512> result{};
    for (auto& b:result) b=transfer(0xff);
    const auto a=transfer(0xff), b=transfer(0xff);
    require((std::uint16_t(a)<<8|b)==crc16(result.data(),result.size()),"read CRC16");
    return result;
  }
  void writePacket(const std::array<std::uint8_t,512>& value,bool multiple=false) {
    transfer(0xff);
    transfer(multiple?0xfc:0xfe);
    for (auto b:value) transfer(b);
    const auto crc=crc16(value.data(),value.size());
    transfer(static_cast<std::uint8_t>(crc>>8));
    transfer(static_cast<std::uint8_t>(crc));
    require((transfer(0xff)&0x1f)==5, "write response");
    bool ready=false;
    for (int i=0; i<12; ++i) if (transfer(0xff)==0xff) { ready=true; break; }
    require(ready,"write busy must terminate");
  }
  void init() {
    require(command(0)==1,"CMD0"); deselect();
    require(command(8,0x1aa)==1,"CMD8");
    for (int i=0; i<4; ++i) transfer(0xff);
    deselect();
    require(command(55)==1,"CMD55"); deselect();
    require(command(41,0x40000000)==0,"ACMD41"); deselect();
    require(command(59,1)==0,"CMD59 CRC on"); deselect();
  }
 private:
  SdSpiCard& card_;
  bool selected_=false;
};

template <typename F>
void rejectsWithoutMutation(SdSpiCard& card,F makeInvalid,const std::string& why) {
  const auto good=card.snapshot();
  auto bad=good;
  makeInvalid(bad);
  bool rejected=false;
  try { card.restore(bad); } catch (const std::runtime_error&) { rejected=true; }
  require(rejected, "checkpoint should reject "+why);
  require(card.snapshot()==good, "checkpoint failure mutated live card: "+why);
}

void compare(SdSpiCard& a,SdSpiCard& b,const std::string& label) {
  require(a.snapshot()==b.snapshot(),"SD snapshot/replay bytes differ: "+label);
  require(a.progress()==b.progress(),"SD counters differ: "+label);
}

void testC1RootfsRebind(const std::string& original, const std::string& changed) {
  SdSpiCard guest(original);
  guest.armC1RootfsBoundary();
  Spi io(guest);
  io.preamble(); io.init();
  require(io.command(17,2100)==0,"C1 first guest read");
  (void)io.read(); io.deselect();
  require(guest.c1ConsumedSectorCount()==1,"C1 must record the exact read LBA");
  const auto c1=guest.snapshot();
  require(c1[8]==2 && c1[9]==0,"C1 must use an independently versioned schema");
  SdSpiCard restored(original);
  restored.restore(c1);
  require(restored.snapshot()==c1,"C1 read-set lost across save/restore");
  require(restored.c1RootfsHandoffQuiescent(),
          "C1 complete read must expose a quiescent rootfs boundary");
  restored.rebindC1Rootfs(changed);
  require(!restored.c1RootfsBoundaryArmed(),"C1 swap must disable old read tracking");
  Spi resumed(restored);
  require(resumed.command(17,5000)==0,"C1 new rootfs sector read");
  auto changedSector=resumed.read(); resumed.deselect();
  require(changedSector[23]==0x5a,"C1 warm resume did not read new rootfs bytes");

  SdSpiCard alreadyRead(original);
  alreadyRead.armC1RootfsBoundary();
  Spi consumed(alreadyRead);
  consumed.preamble(); consumed.init();
  require(consumed.command(17,5000)==0,"C1 consume the sector later changed");
  (void)consumed.read(); consumed.deselect();
  SdSpiCard unsafe(original);
  unsafe.restore(alreadyRead.snapshot());
  const auto stable=unsafe.snapshot();
  bool rejected=false;
  try { unsafe.rebindC1Rootfs(changed); }
  catch (const std::runtime_error&) { rejected=true; }
  require(rejected,"C1 must reject replacing bytes already consumed by Linux");
  require(unsafe.snapshot()==stable,"C1 rejected swap mutated old card state");

  SdSpiCard inflight(original);
  inflight.armC1RootfsBoundary();
  Spi partial(inflight);
  partial.preamble(); partial.init();
  require(partial.command(17,2100)==0,"C1 pending read");
  require(!inflight.c1RootfsHandoffQuiescent(),
          "C1 handoff preflight must reject pending SPI response");
  rejected=false;
  try { inflight.rebindC1Rootfs(changed); }
  catch (const std::runtime_error&) { rejected=true; }
  require(rejected,"C1 must reject a queued, not-yet-consumed SPI response");

  // A forged v2 read ledger with a valid envelope hash must still be checked
  // against the exact original source, not trusted as a caller assertion.
  rejectsWithoutMutation(guest,[](auto& data) {
    data.back()^=0x40;
    const auto hash=sha256(std::span<const std::uint8_t>(data.data()+52,data.size()-52));
    std::copy(hash.begin(),hash.end(),data.begin()+20);
  },"forged C1 previously consumed sector hash");
  std::cout << "PRODUCT_SD1_C1_MEDIA_REBIND_HOST_PASS schema=2 "
            << "same_prior_reads=PASS changed_unread_sector=PASS "
            << "changed_consumed_sector=REJECT inflight=REJECT forged_readset=REJECT "
            << "rtl_cycles=0 linux_cycles=0\n";
}

void run(const std::string& path,const std::string& alteredPath) {
  testC1RootfsRebind(path,alteredPath);
  const std::string empty{};
  const std::string abc="abc";
  const auto toHex=[](const auto& hash) {
    const char* digits="0123456789abcdef";
    std::string value;
    for (auto b:hash) { value+=digits[b>>4]; value+=digits[b&15]; }
    return value;
  };
  require(toHex(sha256(std::span<const std::uint8_t>(
      reinterpret_cast<const std::uint8_t*>(empty.data()),empty.size())))==
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","SHA256(empty)");
  require(toHex(sha256(std::span<const std::uint8_t>(
      reinterpret_cast<const std::uint8_t*>(abc.data()),abc.size())))==
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad","SHA256(abc)");

  SdSpiCard a(path),b(path);
  Spi sa(a),sb(b);
  sa.preamble();
  b.restore(a.snapshot());
  compare(a,b,"startup preamble");
  sa.select(); sb.select();
  const auto cmd0=Spi::frame(0,0);
  sa.bits(cmd0[0],7,5);
  b.restore(a.snapshot());  // mid-byte, plus CS asserted and SCLK falling
  sa.bits(cmd0[0],4,0); sb.bits(cmd0[0],4,0);
  for (std::size_t i=1; i<cmd0.size(); ++i)
    require(sa.transfer(cmd0[i])==sb.transfer(cmd0[i]),"CMD0 return bits");
  require(sa.response()==1 && sb.response()==1,"mid-byte restored CMD0");
  sa.deselect(); sb.deselect();
  compare(a,b,"three-bit command frame");
  sa.init(); sb.init();
  compare(a,b,"CMD55 latch and CRC-initialized transport");

  // At the snapshot edge SCLK is HIGH: the same clock's falling edge must
  // continue from the restored TX bit position, not emit a new byte/bit.
  sa.select(); sb.select();
  const auto cmd55=Spi::frame(55,0);
  require(sa.highHalf(false)==sb.highHalf(false),"half-edge precondition");
  b.restore(a.snapshot());
  require(sa.lowHalf(false)==sb.lowHalf(false),"restored SCLK falling edge");
  sa.bits(cmd55[0],6,0); sb.bits(cmd55[0],6,0);
  for (unsigned i=1; i<cmd55.size(); ++i)
    require(sa.transfer(cmd55[i])==sb.transfer(cmd55[i]),"CMD55 bits");
  require(sa.response()==0 && sb.response()==0,"CMD55 after init");
  sa.deselect(); sb.deselect();
  b.restore(a.snapshot());  // APP_CMD latch intentionally survives CS-high
  require(sa.command(41,0x40000000)==0 && sb.command(41,0x40000000)==0,
          "ACMD41 after restored CS-high APP_CMD latch");
  sa.deselect(); sb.deselect();
  compare(a,b,"SCLK high-edge and deselected app-command latch");

  require(sa.command(17,2100)==0 && sb.command(17,2100)==0,"CMD17");
  sa.token(); sb.token();
  for (unsigned i=0; i<23; ++i)
    require(sa.transfer(0xff)==sb.transfer(0xff),"inflight TX block prefix");
  b.restore(a.snapshot());  // pending 512-byte TX response in middle
  for (unsigned i=23; i<514; ++i)
    require(sa.transfer(0xff)==sb.transfer(0xff),"inflight TX block suffix+CRC");
  sa.deselect(); sb.deselect();
  compare(a,b,"mid-CMD17 queued sector");

  std::array<std::uint8_t,512> written{};
  for (std::size_t i=0; i<written.size(); ++i) written[i]=static_cast<std::uint8_t>(0x30^i);
  require(sa.command(24,2100)==0 && sb.command(24,2100)==0,"CMD24");
  sa.transfer(0xff); sb.transfer(0xff);
  sa.transfer(0xfe); sb.transfer(0xfe);
  for (unsigned i=0; i<197; ++i)
    require(sa.transfer(written[i])==sb.transfer(written[i]),"inflight RX write prefix");
  sa.bits(written[197],7,3); sb.bits(written[197],7,3);
  b.restore(a.snapshot());  // CMD24 partial sector + partial SPI byte
  sa.bits(written[197],2,0); sb.bits(written[197],2,0);
  for (unsigned i=198; i<written.size(); ++i)
    require(sa.transfer(written[i])==sb.transfer(written[i]),"inflight RX write suffix");
  const auto crc=crc16(written.data(),written.size());
  require(sa.transfer(static_cast<std::uint8_t>(crc>>8))==
          sb.transfer(static_cast<std::uint8_t>(crc>>8)),"CRC high");
  require(sa.transfer(static_cast<std::uint8_t>(crc))==
          sb.transfer(static_cast<std::uint8_t>(crc)),"CRC low");
  require((sa.transfer(0xff)&0x1f)==5 && (sb.transfer(0xff)&0x1f)==5,"COW accept");
  for (int i=0; i<3; ++i) require(sa.transfer(0xff)==sb.transfer(0xff),"busy/ready");
  sa.deselect(); sb.deselect();
  compare(a,b,"mid-CMD24 514-byte write with COW overlay");
  require(sa.command(17,2100)==0 && sb.command(17,2100)==0,"COW read");
  require(sa.read()==written && sb.read()==written,"COW bytes survive replay");
  sa.deselect(); sb.deselect();

  require(sa.command(18,2100)==0 && sb.command(18,2100)==0,"CMD18");
  require(sa.read()==written && sb.read()==written,"CMD18 first sector");
  sa.token(); sb.token();
  for (int i=0; i<41; ++i) require(sa.transfer(0xff)==sb.transfer(0xff),"CMD18 next sector prefix");
  b.restore(a.snapshot());
  for (int i=41; i<514; ++i) require(sa.transfer(0xff)==sb.transfer(0xff),"CMD18 stream restore");
  sa.deselect(); sb.deselect();
  compare(a,b,"inflight multi-read next LBA");

  require(sa.command(25,2101)==0 && sb.command(25,2101)==0,"CMD25");
  auto block2=written; block2[0]^=0xa5;
  sa.writePacket(written,true); sb.writePacket(written,true);
  b.restore(a.snapshot());  // WaitToken with nextWriteLba already incremented
  sa.writePacket(block2,true); sb.writePacket(block2,true);
  require(sa.transfer(0xfd)==sb.transfer(0xfd),"multi-write stop token");
  sa.deselect(); sb.deselect();
  compare(a,b,"multi-write second LBA and sparse overlay");

  rejectsWithoutMutation(a,[](auto& data){data[0]^=1;},"bad magic");
  rejectsWithoutMutation(a,[](auto& data){data[8]^=1;},"wrong version");
  rejectsWithoutMutation(a,[](auto& data){data[data.size()/2]^=1;},"body checksum");
  rejectsWithoutMutation(a,[](auto& data){data.pop_back();},"truncated payload");
  rejectsWithoutMutation(a,[](auto& data){data.push_back(0);},"trailing payload");
  const auto resign=[](auto& data) {
    require(data.size()>=52,"snapshot header size");
    const auto digest=sha256(std::span<const std::uint8_t>(data.data()+52,data.size()-52));
    std::copy(digest.begin(),digest.end(),data.begin()+20);
  };
  rejectsWithoutMutation(a,[&](auto& data) {
    // Payload start 52, identity 48, 10 state flags, then write-FSM byte.
    data[52+48+10]=9;
    resign(data);
  },"valid checksum but invalid write FSM");
  rejectsWithoutMutation(a,[&](auto& data) {
    // Three dirty sectors in sorted LBA order; duplicate the second LBA.
    const std::size_t sectors=3;
    const std::size_t first=data.size()-sectors*(8+512);
    require(data[first-8]==3,"expected three sparse sectors for malicious LBA test");
    std::copy_n(data.begin()+first,8,data.begin()+first+(8+512));
    resign(data);
  },"valid checksum but duplicate overlay LBA");
  SdSpiCard wrong(alteredPath);
  const auto wrongBefore=wrong.snapshot();
  bool mediaRejected=false;
  try { wrong.restore(a.snapshot()); }
  catch (const std::runtime_error&) { mediaRejected=true; }
  require(mediaRejected,"same-size, same-partition, modified media must reject");
  require(wrong.snapshot()==wrongBefore,"media mismatch mutated live card");
  require(sha256File(path)!=sha256File(alteredPath),"different whole-media SHA256");

  // Overlay insertion order cannot affect deterministic checkpoint bytes.
  SdSpiCard c(path),d(path);
  Spi sc(c),sd(d); sc.preamble(); sd.preamble(); sc.init(); sd.init();
  auto write=[&](Spi& spi,std::uint32_t lba,const auto& data) {
    require(spi.command(24,lba)==0,"sorted overlay CMD24");
    spi.writePacket(data); spi.deselect();
  };
  write(sc,2102,written); write(sc,2103,block2); write(sc,2104,written);
  write(sd,2103,block2); write(sd,2102,written); write(sd,2104,written);
  require(c.snapshot()==d.snapshot(),"COW overlay unordered insertion encoded nondeterministically");
  require(c.progress()==d.progress(),"sorted overlay counters");

  // Adversarial media swap: same file length and same restored timestamp.
  // An mtime/size memoized fingerprint would accept this mutated source and
  // make an SD1 distro checkpoint silently refer to different disk sectors.
  const auto immutableBefore=a.snapshot();
  const auto sourceTime=std::filesystem::last_write_time(path);
  auto mutate=[&](char value) {
    std::fstream disk(path,std::ios::binary|std::ios::in|std::ios::out);
    require(bool(disk),"open synthetic SD image for mutation test");
    disk.seekp(7000ULL*512+19);
    disk.put(value);
    disk.close();
    require(bool(disk),"mutate synthetic SD image");
    std::filesystem::last_write_time(path,sourceTime);
    require(std::filesystem::file_size(path)==(64ULL<<20),"synthetic image length unchanged");
    require(std::filesystem::last_write_time(path)==sourceTime,"restored timestamp unchanged");
  };
  mutate('\x77');
  bool forgedMediaRejected=false;
  try { (void)a.snapshot(); }
  catch (const std::runtime_error&) { forgedMediaRejected=true; }
  require(forgedMediaRejected,"same-size, same-mtime backing image mutation accepted");
  mutate('\0');
  require(a.snapshot()==immutableBefore,"failed snapshot or temporary media mutation changed card state");

  std::cout << "PRODUCT_SD1_CARD_CHECKPOINT_HOST_PASS "
            << "schema=1 media=full_sha256 boundaries=rx3_sclk_high_cmd6_write197_multi_read_multi_write "
            << "overlay=deterministic_sorted_lba corrupt_rejected=7 wrong_media_rejected=1 "
            << "forged_same_size_mtime_rejected=1 "
            << "rtl_cycles=0 linux_cycles=0\n";
}
}  // namespace

int main(int argc,char** argv) {
  try {
    if (argc!=3) throw std::runtime_error("usage: checkpoint_selftest IMAGE ALTERED_IMAGE");
    makeImage(argv[1],false); makeImage(argv[2],true);
    run(argv[1],argv[2]);
  } catch (const std::exception& e) {
    std::cerr << "PRODUCT_SD1_CARD_CHECKPOINT_HOST_FAIL: " << e.what() << '\n';
    return 1;
  }
  return 0;
}
