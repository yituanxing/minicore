// Cheap host-only protocol preflight for the Product G5-SD1 pin-level card.
// No Verilator, RTL elaboration, Linux execution or FPGA is involved. This
// probe is deliberately run *before* any future, explicitly approved longrun.
#include "l32_sd_spi_card.h"

#include <array>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>

using aethercore::l32sim::SdSpiCard;

namespace {
class PinSpi {
 public:
  explicit PinSpi(const std::string& disk) : card_(disk) {
    card_.observe(false, true, true);
    // Initial >=74 clocks while deselected precede CMD0 in SD SPI mode.
    for (unsigned i = 0; i < 12; ++i) transfer(0xff);
  }

  void select() {
    card_.observe(false, true, false);
    selected_ = true;
  }

  void deselect() {
    card_.observe(false, true, true);
    selected_ = false;
    transfer(0xff);
  }

  std::uint8_t transfer(std::uint8_t outgoing) {
    std::uint8_t incoming = 0;
    for (int bit = 7; bit >= 0; --bit) {
      const bool out = ((outgoing >> bit) & 1U) != 0;
      card_.observe(false, out, !selected_);
      card_.observe(true, out, !selected_);
      incoming = static_cast<std::uint8_t>((incoming << 1) |
          (card_.miso() ? 1U : 0U));
      card_.observe(false, out, !selected_);
    }
    return incoming;
  }

  std::uint8_t command(std::uint8_t index, std::uint32_t argument) {
    select();
    return commandSelected(index, argument);
  }

  // CMD12 terminates CMD18 *without* deasserting the physical chip-select.
  // The controller remains selected while the SD card is streaming sectors.
  std::uint8_t commandSelected(std::uint8_t index, std::uint32_t argument) {
    std::array<std::uint8_t, 5> frame{
        static_cast<std::uint8_t>(0x40 | index),
        static_cast<std::uint8_t>(argument >> 24),
        static_cast<std::uint8_t>(argument >> 16),
        static_cast<std::uint8_t>(argument >> 8),
        static_cast<std::uint8_t>(argument)};
    for (const auto byte : frame) transfer(byte);
    transfer(static_cast<std::uint8_t>((crc7(frame.data(), frame.size()) << 1) | 1));
    for (int tries = 0; tries < 20; ++tries) {
      const auto response = transfer(0xff);
      if ((response & 0x80) == 0) return response;
    }
    throw std::runtime_error("SD SPI R1 did not arrive within 20 bytes");
  }

  std::uint8_t requireDataToken() {
    for (int tries = 0; tries < 30; ++tries) {
      const auto token = transfer(0xff);
      if (token == 0xfe) return token;
      if (token != 0xff) throw std::runtime_error("unexpected SD data token");
    }
    throw std::runtime_error("SD data-start token did not arrive");
  }

  std::array<std::uint8_t, 512> readDataBlock() {
    requireDataToken();
    std::array<std::uint8_t, 512> bytes{};
    for (auto& value : bytes) value = transfer(0xff);
    const auto crcHigh = transfer(0xff);
    const auto crcLow = transfer(0xff);
    const auto cardCrc = static_cast<std::uint16_t>(
        (static_cast<std::uint16_t>(crcHigh) << 8) | crcLow);
    if (cardCrc != crc16(bytes.data(), bytes.size()))
      throw std::runtime_error("SD data CRC16 does not match the returned sector");
    return bytes;
  }

  std::array<std::uint8_t, 512> readBlock(std::uint32_t lba) {
    expect(command(17, lba), 0, "CMD17 READ_SINGLE_BLOCK");
    auto bytes = readDataBlock();
    deselect();
    return bytes;
  }

  std::array<std::array<std::uint8_t, 512>, 2> readTwoBlocks(std::uint32_t lba) {
    expect(command(18, lba), 0, "CMD18 READ_MULTIPLE_BLOCK");
    std::array<std::array<std::uint8_t, 512>, 2> bytes{
        readDataBlock(), readDataBlock()};
    expect(commandSelected(12, 0), 0, "CMD12 STOP_TRANSMISSION");
    // CMD12 must stop the autonomous data stream. If an extra data token is
    // produced here, the next Linux request would consume the wrong sector.
    for (int i = 0; i < 16; ++i)
      if (transfer(0xff) != 0xff)
        throw std::runtime_error("CMD12 left a multi-block read stream active");
    deselect();
    return bytes;
  }

  void writeBlock(std::uint32_t lba,
                  const std::array<std::uint8_t, 512>& bytes) {
    expect(command(24, lba), 0, "CMD24 WRITE_BLOCK");
    writeDataBlock(bytes, false);
    deselect();
  }

  void writeTwoBlocks(std::uint32_t lba,
                      const std::array<std::array<std::uint8_t, 512>, 2>& bytes) {
    expect(command(25, lba), 0, "CMD25 WRITE_MULTIPLE_BLOCK");
    for (const auto& block : bytes) writeDataBlock(block, true);
    // Linux mmc_spi ends a multi-block write with the SPI 0xFD stop token,
    // not CMD12. Keep CS asserted until the card has become ready.
    transfer(0xfd);
    for (int tries = 0; tries < 50; ++tries) {
      if (transfer(0xff) == 0xff) { deselect(); return; }
    }
    throw std::runtime_error("CMD25 stop token never returned ready");
  }

  static void expect(std::uint8_t actual, std::uint8_t expected,
                     const std::string& label) {
    if (actual != expected)
      throw std::runtime_error(label + ": expected R1=" +
          std::to_string(expected) + ", observed " + std::to_string(actual));
  }

  SdSpiCard& card() { return card_; }

 private:
  static std::uint8_t crc7(const std::uint8_t* bytes, std::size_t count) {
    std::uint8_t crc = 0;
    for (std::size_t i = 0; i < count; ++i) {
      auto next = bytes[i];
      for (int bit = 0; bit < 8; ++bit) {
        crc <<= 1;
        if ((crc ^ next) & 0x80) crc ^= 0x09;
        next <<= 1;
      }
    }
    return crc & 0x7f;
  }

  static std::uint16_t crc16(const std::uint8_t* bytes, std::size_t count) {
    std::uint16_t crc = 0;
    for (std::size_t i = 0; i < count; ++i) {
      crc ^= static_cast<std::uint16_t>(bytes[i]) << 8;
      for (int bit = 0; bit < 8; ++bit)
        crc = static_cast<std::uint16_t>((crc << 1) ^ ((crc & 0x8000) ? 0x1021 : 0));
    }
    return crc;
  }

  void writeDataBlock(const std::array<std::uint8_t, 512>& bytes, bool multiple) {
    transfer(0xff); // N(WR), as generated by Linux mmc_spi.
    transfer(multiple ? 0xfc : 0xfe);
    for (const auto byte : bytes) transfer(byte);
    const auto crc = crc16(bytes.data(), bytes.size());
    transfer(static_cast<std::uint8_t>(crc >> 8));
    transfer(static_cast<std::uint8_t>(crc));
    const auto response = transfer(0xff);
    if ((response & 0x1f) != 0x05)
      throw std::runtime_error("SD block write did not accept data");
    bool ready = false;
    for (int i = 0; i < 50; ++i)
      if (transfer(0xff) == 0xff) { ready = true; break; }
    if (!ready) throw std::runtime_error("SD write never exited busy");
  }
  SdSpiCard card_;
  bool selected_ = false;
};

std::uint32_t le32(const std::uint8_t* bytes) {
  return static_cast<std::uint32_t>(bytes[0]) |
    (static_cast<std::uint32_t>(bytes[1]) << 8) |
    (static_cast<std::uint32_t>(bytes[2]) << 16) |
    (static_cast<std::uint32_t>(bytes[3]) << 24);
}
}  // namespace

int main(int argc, char** argv) {
  try {
    if (argc != 2) throw std::runtime_error("usage: product_sim_sd1_card_probe RAW_SD_IMAGE");
    PinSpi spi(argv[1]);
    PinSpi::expect(spi.command(0, 0), 0x01, "CMD0 GO_IDLE_STATE");
    spi.deselect();
    PinSpi::expect(spi.command(8, 0x1aa), 0x01, "CMD8 SEND_IF_COND");
    if (spi.transfer(0xff) != 0 || spi.transfer(0xff) != 0 ||
        spi.transfer(0xff) != 0x01 || spi.transfer(0xff) != 0xaa)
      throw std::runtime_error("SDHC CMD8 voltage/check pattern mismatch");
    spi.deselect();
    // Linux mmc_spi can split CMD55 and ACMD41 across distinct GPIO-CS
    // selections. Their app-command latch must survive this boundary.
    PinSpi::expect(spi.command(55, 0), 0x01, "CMD55 APP_CMD");
    spi.deselect();
    PinSpi::expect(spi.command(41, 0x40000000), 0, "ACMD41 HCS init");
    spi.deselect();
    PinSpi::expect(spi.command(58, 0), 0, "CMD58 READ_OCR");
    const auto ocrMsb = spi.transfer(0xff);
    if ((ocrMsb & 0xc0) != 0xc0)
      throw std::runtime_error("SDHC OCR power-up/CCS bits were not returned");
    for (int i = 0; i < 3; ++i) spi.transfer(0xff);
    spi.deselect();
    // The Linux mmc_spi transport includes CRC7 command frames and CRC16
    // block payloads. Make CRC mandatory before exercising its read/write
    // paths, otherwise a broken CRC implementation could stay unnoticed.
    PinSpi::expect(spi.command(59, 1), 0, "CMD59 CRC_ON_OFF");
    spi.deselect();
    PinSpi::expect(spi.command(9, 0), 0, "CMD9 SEND_CSD");
    spi.requireDataToken();
    std::array<std::uint8_t, 16> csd{};
    for (auto& value : csd) value = spi.transfer(0xff);
    spi.transfer(0xff);
    spi.transfer(0xff);
    spi.deselect();
    if ((csd[0] & 0xc0) != 0x40)
      throw std::runtime_error("SD card did not advertise CSD version 2.0");
    const auto csize = (static_cast<std::uint32_t>(csd[7] & 0x3f) << 16) |
        (static_cast<std::uint32_t>(csd[8]) << 8) | csd[9];
    if ((static_cast<std::uint64_t>(csize) + 1) * 1024 != spi.card().sourceSectors())
      throw std::runtime_error("SDHC CSD-reported capacity differs from image");
    const auto mbr = spi.readBlock(0);
    if (mbr[510] != 0x55 || mbr[511] != 0xaa || mbr[0x1be + 4] != 0x83)
      throw std::runtime_error("SD CMD17 did not return expected disk MBR");
    const auto partitionLba = le32(mbr.data() + 0x1be + 8);
    if (partitionLba != spi.card().partitionStartLba())
      throw std::runtime_error("SD partition origin differs from card image preflight");
    const auto ext4 = spi.readBlock(partitionLba + 2);
    if (ext4[56] != 0x53 || ext4[57] != 0xef)
      throw std::runtime_error("SD CMD17 did not return ext4 superblock magic");
    // A host-session write must be readable through the very same SPI pins,
    // without altering the original SD image used as future-run evidence.
    auto changed = ext4;
    changed[0] ^= 0xa5;
    spi.writeBlock(partitionLba + 2, changed);
    if (spi.readBlock(partitionLba + 2) != changed)
      throw std::runtime_error("SD session COW write/read protocol mismatch");
    // CMD18/CMD12 and CMD25/0xFD are the paths used by Linux mmc_spi for
    // ordinary sequential filesystem I/O. In particular, terminating a
    // multiblock transfer must not leak the next token into a new request.
    const auto originalSecondBlock = spi.readBlock(partitionLba + 3);
    const auto pair = spi.readTwoBlocks(partitionLba + 2);
    if (pair[0] != changed || pair[1] != originalSecondBlock)
      throw std::runtime_error("CMD18 returned a wrong SD sector sequence");
    auto changedPair = pair;
    changedPair[0][0] ^= 0x33;
    changedPair[1][511] ^= 0xcc;
    spi.writeTwoBlocks(partitionLba + 2, changedPair);
    if (spi.readTwoBlocks(partitionLba + 2) != changedPair ||
        spi.readBlock(partitionLba + 3) != changedPair[1])
      throw std::runtime_error("CMD25 SD session COW multi-block write/read mismatch");
    // Verify the stop token also ended the write session: new single-block
    // requests must not be mistaken for another 0xFC data packet.
    if (spi.readBlock(0) != mbr)
      throw std::runtime_error("CMD25 stop token corrupted the following CMD17");
    std::cout << "PRODUCT_SD1_CARD_MULTIBLOCK_CRC_STOP_PREFLIGHT_PASS "
              << spi.card().progress() << '\n';
    std::cout << "PRODUCT_SD1_CARD_PIN_PREFLIGHT_PASS "
              << spi.card().progress() << '\n';
  } catch (const std::exception& e) {
    std::cerr << "PRODUCT_SD1_CARD_PIN_PREFLIGHT_FAIL: " << e.what() << '\n';
    return 1;
  }
  return 0;
}
