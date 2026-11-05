#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>

namespace aethercore::l32sim {

/** Pin-level SDHC card used ONLY by the explicit Product SD1 SPI-SD top.
 *
 * Linux's mmc_spi driver speaks SD SPI mode 0 through the same four physical
 * pins exposed on the FPGA top. This model consumes complete bytes sampled on
 * rising SCLK edges and changes MISO on falling edges. It does not short-cut
 * the Product CPU, SPI controller, PLIC or Linux block layer.
 *
 * The source image is immutable. Accepted writes are kept in a sparse,
 * session-local copy-on-write overlay: userspace can mount the ext4 partition
 * read/write without a long Product run mutating its golden input. Real FPGA
 * SD media is persistent; this host overlay is NOT an RTL checkpoint or a
 * power-cycle persistence claim.
 */
class SdSpiCard {
 public:
  explicit SdSpiCard(const std::string& imagePath)
      : disk_(imagePath, std::ios::binary) {
    if (!disk_) throw std::runtime_error("Product SD1 SD image cannot be opened: " + imagePath);
    const auto bytes = std::filesystem::file_size(imagePath);
    if (bytes < (64ULL << 20) || bytes % (512ULL << 10) != 0 ||
        bytes / 512 > (1ULL << 22) * 1024ULL) {
      throw std::runtime_error("Product SD1 needs a 512KiB-aligned 64MiB..2TiB raw SDHC image");
    }
    sectorCount_ = bytes / 512;

    const auto mbr = readSector(0);
    if (mbr[510] != 0x55 || mbr[511] != 0xaa || mbr[0x1be + 4] != 0x83)
      throw std::runtime_error("Product SD1 SD image must have an MBR with Linux partition 1");
    firstPartitionLba_ = le32(mbr.data() + 0x1be + 8);
    partitionSectors_ = le32(mbr.data() + 0x1be + 12);
    if (firstPartitionLba_ < 2048 || partitionSectors_ == 0 ||
        static_cast<std::uint64_t>(firstPartitionLba_) + partitionSectors_ > sectorCount_)
      throw std::runtime_error("Product SD1 SD image has an invalid partition-1 sector range");
    const auto ext4Superblock = readSector(firstPartitionLba_ + 2);
    if (ext4Superblock[56] != 0x53 || ext4Superblock[57] != 0xef)
      throw std::runtime_error("Product SD1 partition 1 is not an ext2/3/4 filesystem");
  }

  bool miso() const { return miso_; }

  // Call only after one complete simulated clock edge. The model's SCLK edge
  // detector is independent of simulator eval count or host execution time.
  void observe(bool sclk, bool mosi, bool csHigh) {
    if (csHigh) {
      if (!lastCsHigh_) deselect();
      if (!lastSclk_ && sclk) {
        // A physical SD card must see at least 74 startup clocks with both
        // CS and DI high before it can enter SPI mode via CMD0. Count only
        // a contiguous valid preamble, saturating to avoid wraparound on a
        // long Debian simulation that deasserts CS between transactions.
        if (!startupClockSequenceSeen_) {
          if (!mosi) unselectedClocks_ = 0;
          else if (unselectedClocks_ < 74) ++unselectedClocks_;
          if (unselectedClocks_ >= 74) startupClockSequenceSeen_ = true;
        }
      }
      miso_ = true;
    } else {
      // Selection and the first SPI edge can coincide. Treat the electrical
      // edge exactly once even in that case: losing one input bit would make
      // the whole subsequent command stream impossible to enumerate.
      if (lastCsHigh_) select();
      if (!lastSclk_ && sclk) {
        rxByte_ = static_cast<std::uint8_t>((rxByte_ << 1) | (mosi ? 1 : 0));
        if (++rxBits_ == 8) {
          consumeByte(rxByte_);
          rxByte_ = 0;
          rxBits_ = 0;
        }
      } else if (lastSclk_ && !sclk) {
        if (txBit_ == 0) {
          txByte_ = nextTransmitByte();
          txBit_ = 7;
        } else {
          --txBit_;
        }
        miso_ = ((txByte_ >> txBit_) & 1U) != 0;
      }
    }
    lastSclk_ = sclk;
    lastCsHigh_ = csHigh;
  }

  std::string progress() const {
    std::ostringstream out;
    out << "sd-cmds=" << commands_ << " sd-reads=" << reads_
        << " sd-writes=" << writes_ << " sd-cow-sectors=" << overlay_.size()
        << " sd-last-cmd=" << lastCommand_ << " sd-init=" << (!idle_ ? 1 : 0);
    return out.str();
  }

  std::uint64_t sourceSectors() const { return sectorCount_; }
  std::uint32_t partitionStartLba() const { return firstPartitionLba_; }

 private:
  static constexpr std::size_t SectorSize = 512;
  using Sector = std::array<std::uint8_t, SectorSize>;
  enum class WriteState { None, WaitToken, ReceiveData };

  static std::uint32_t le32(const std::uint8_t* b) {
    return static_cast<std::uint32_t>(b[0]) |
        (static_cast<std::uint32_t>(b[1]) << 8) |
        (static_cast<std::uint32_t>(b[2]) << 16) |
        (static_cast<std::uint32_t>(b[3]) << 24);
  }

  static std::uint8_t crc7(const std::uint8_t* bytes, std::size_t count) {
    std::uint8_t crc = 0;
    for (std::size_t i = 0; i < count; ++i) {
      std::uint8_t next = bytes[i];
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

  Sector readSector(std::uint64_t lba) {
    if (lba >= sectorCount_) throw std::runtime_error("Product SD1 SD read beyond image");
    if (const auto found = overlay_.find(lba); found != overlay_.end()) return found->second;
    Sector bytes{};
    disk_.clear();
    disk_.seekg(static_cast<std::streamoff>(lba * SectorSize));
    disk_.read(reinterpret_cast<char*>(bytes.data()), SectorSize);
    if (!disk_) throw std::runtime_error("Product SD1 SD short sector read");
    return bytes;
  }

  void select() {
    rxBits_ = 0;
    rxByte_ = 0;
    txBit_ = 7;
    txByte_ = 0xff;
    miso_ = true;
  }

  void deselect() {
    rxBits_ = 0;
    rxByte_ = 0;
    commandBytes_ = 0;
    txByte_ = 0xff;
    txBit_ = 7;
    output_.clear();
    multiRead_ = false;
    multiWrite_ = false;
    writeState_ = WriteState::None;
    // CMD55 selects an application command across a subsequent SPI message.
    // Linux mmc_spi may release CS between CMD55 and ACMD41/ACMD51. Clearing
    // appCommand_ here would turn ACMD41 into unsupported CMD41 and prevent
    // SD-card enumeration on an otherwise working physical controller.
  }

  void queueByte(std::uint8_t value) { output_.push_back(value); }
  void queueR1(std::uint8_t status) {
    queueByte(0xff);  // Standard N(CR) delay before the response.
    queueByte(status);
  }
  std::uint8_t status() const { return idle_ ? 0x01 : 0x00; }

  void queueData(const std::uint8_t* data, std::size_t bytes) {
    queueByte(0xff);
    queueByte(0xfe);
    for (std::size_t i = 0; i < bytes; ++i) queueByte(data[i]);
    const auto crc = crc16(data, bytes);
    queueByte(static_cast<std::uint8_t>(crc >> 8));
    queueByte(static_cast<std::uint8_t>(crc));
  }

  void queueReadBlock(std::uint64_t lba) {
    if (lba >= sectorCount_) {
      queueByte(0x09); // SD SPI data-error token: out of range.
      multiRead_ = false;
      return;
    }
    const auto sector = readSector(lba);
    queueData(sector.data(), sector.size());
    ++reads_;
  }

  std::uint8_t nextTransmitByte() {
    if (output_.empty() && multiRead_ && !idle_)
      queueReadBlock(nextReadLba_++);
    if (output_.empty()) return 0xff;
    const auto result = output_.front();
    output_.pop_front();
    return result;
  }

  std::array<std::uint8_t, 16> cid() const {
    std::array<std::uint8_t, 16> value{
        0x03, 'A', 'E', 'T', 'H', 'E', 'R', 0x10, 0x20, 0x26, 0x11, 0x05,
        0x01, 0x60, 0x00, 0x01};
    value[15] = static_cast<std::uint8_t>((crc7(value.data(), 15) << 1) | 1);
    return value;
  }

  std::array<std::uint8_t, 16> csd() const {
    // CSD v2.0 capacity=(C_SIZE+1)*1024 sectors, READ_BL_LEN=9 (512B).
    const auto csize = static_cast<std::uint32_t>((sectorCount_ / 1024) - 1);
    std::array<std::uint8_t, 16> value{
        0x40, 0x0e, 0x00, 0x32, 0x5b, 0x59, 0x00,
        static_cast<std::uint8_t>((csize >> 16) & 0x3f),
        static_cast<std::uint8_t>(csize >> 8),
        static_cast<std::uint8_t>(csize),
        0x7f, 0x80, 0x0a, 0x40, 0x00, 0x01};
    value[15] = static_cast<std::uint8_t>((crc7(value.data(), 15) << 1) | 1);
    return value;
  }

  void consumeWriteByte(std::uint8_t data) {
    if (writeState_ == WriteState::WaitToken) {
      if (multiWrite_ && data == 0xfd) {
        writeState_ = WriteState::None;
        multiWrite_ = false;
      } else if (data == (multiWrite_ ? 0xfc : 0xfe)) {
        writeBytes_ = 0;
        writeState_ = WriteState::ReceiveData;
      }
      return;
    }
    if (writeState_ != WriteState::ReceiveData) return;
    writePacket_[writeBytes_++] = data;
    if (writeBytes_ != writePacket_.size()) return;

    const auto actualCrc = static_cast<std::uint16_t>(
        (static_cast<std::uint16_t>(writePacket_[512]) << 8) | writePacket_[513]);
    const bool valid = !crcEnabled_ || crc16(writePacket_.data(), 512) == actualCrc;
    if (nextWriteLba_ >= sectorCount_) {
      queueByte(0x0d); // SD SPI write-error response.
    } else if (!valid) {
      queueByte(0x0b); // SD SPI CRC-error response.
    } else {
      Sector sector{};
      for (std::size_t i = 0; i < sector.size(); ++i) sector[i] = writePacket_[i];
      overlay_[nextWriteLba_] = sector;
      queueByte(0x05); // SD SPI data accepted.
      ++writes_;
    }
    queueByte(0x00); // Busy for a bounded period; next byte is ready.
    queueByte(0xff);
    if (multiWrite_) {
      ++nextWriteLba_;
      writeState_ = WriteState::WaitToken;
    } else {
      writeState_ = WriteState::None;
    }
  }

  void consumeByte(std::uint8_t data) {
    if (writeState_ != WriteState::None) {
      consumeWriteByte(data);
      return;
    }
    if (commandBytes_ == 0) {
      if ((data & 0xc0) != 0x40) return;
    }
    command_[commandBytes_++] = data;
    if (commandBytes_ != command_.size()) return;
    commandBytes_ = 0;
    executeCommand();
  }

  void executeCommand() {
    const auto opcode = static_cast<std::uint8_t>(command_[0] & 0x3f);
    if (opcode == 0 && !startupClockSequenceSeen_) {
      // Reject an emulator-only success before burning billions of RTL
      // cycles. Real hardware would not recognize this premature CMD0.
      throw std::runtime_error(
          "Product SD1 CMD0 before 74 CS-high/DI-high initialization clocks");
    }
    const auto argument = (static_cast<std::uint32_t>(command_[1]) << 24) |
        (static_cast<std::uint32_t>(command_[2]) << 16) |
        (static_cast<std::uint32_t>(command_[3]) << 8) | command_[4];
    // CMD0 is an unconditional SPI reset, even if a previous CMD55 was
    // abandoned. A power-up retry must never be decoded as an application
    // command while an old APP_CMD latch is still present.
    const bool app = appCommand_ && opcode != 55 && opcode != 0;
    appCommand_ = false;
    ++commands_;
    lastCommand_ = opcode;

    // CMD0/CMD8 always require CRC7; CMD59 may enable CRC for all commands.
    if ((opcode == 0 || opcode == 8 || crcEnabled_) &&
        command_[5] != static_cast<std::uint8_t>((crc7(command_.data(), 5) << 1) | 1)) {
      queueR1(static_cast<std::uint8_t>(status() | 0x08));
      return;
    }

    if (app) {
      switch (opcode) {
        case 41: // ACMD41 SD_SEND_OP_COND with HCS bit for SDHC.
          highCapacity_ = (argument & 0x40000000) != 0;
          idle_ = false;
          queueR1(0);
          return;
        case 23: // ACMD23 SET_WR_BLK_ERASE_COUNT.
          queueR1(status());
          return;
        case 6: // ACMD6 SET_BUS_WIDTH; SPI transport remains 1-bit.
          queueR1(status());
          return;
        case 51: { // ACMD51 SEND_SCR, SD v2 + 1/4-bit capabilities.
          queueR1(status());
          constexpr std::array<std::uint8_t, 8> scr{0x02, 0x05, 0, 0, 0, 0, 0, 0};
          queueData(scr.data(), scr.size());
          return;
        }
        case 13: { // ACMD13 SD_STATUS (SPI R2 + 64-byte block).
          queueR1(status());
          queueByte(0);
          const std::array<std::uint8_t, 64> zero{};
          queueData(zero.data(), zero.size());
          return;
        }
        default:
          queueR1(static_cast<std::uint8_t>(status() | 0x04));
          return;
      }
    }

    switch (opcode) {
      case 0: // GO_IDLE_STATE.
        idle_ = true;
        highCapacity_ = false;
        crcEnabled_ = false;
        multiRead_ = false;
        multiWrite_ = false;
        writeState_ = WriteState::None;
        queueR1(0x01);
        break;
      case 1: // Legacy SEND_OP_COND fallback.
        idle_ = false;
        queueR1(0);
        break;
      case 8: // SEND_IF_COND, R7.
        queueR1(status());
        queueByte(static_cast<std::uint8_t>(argument >> 24));
        queueByte(static_cast<std::uint8_t>(argument >> 16));
        queueByte(static_cast<std::uint8_t>(argument >> 8));
        queueByte(static_cast<std::uint8_t>(argument));
        break;
      case 9: { // SEND_CSD.
        queueR1(status());
        if (!idle_) { const auto data = csd(); queueData(data.data(), data.size()); }
        break;
      }
      case 10: { // SEND_CID.
        queueR1(status());
        if (!idle_) { const auto data = cid(); queueData(data.data(), data.size()); }
        break;
      }
      case 12: // STOP_TRANSMISSION; SPI prepends one stuff byte.
        multiRead_ = false;
        output_.clear();
        queueByte(0xff);
        queueR1(status());
        break;
      case 13: // SEND_STATUS, SPI R2.
        queueR1(status());
        queueByte(0);
        break;
      case 16: // SET_BLOCKLEN (SDHC always 512).
        queueR1(argument == 512 ? status() : static_cast<std::uint8_t>(status() | 0x40));
        break;
      case 17: { // READ_SINGLE_BLOCK.
        const auto lba = highCapacity_ ? argument : argument / 512;
        if (idle_ || lba >= sectorCount_) {
          queueR1(static_cast<std::uint8_t>(status() | 0x20));
        } else {
          queueR1(0);
          queueReadBlock(lba);
        }
        break;
      }
      case 18: { // READ_MULTIPLE_BLOCK.
        const auto lba = highCapacity_ ? argument : argument / 512;
        if (idle_ || lba >= sectorCount_) {
          queueR1(static_cast<std::uint8_t>(status() | 0x20));
        } else {
          queueR1(0);
          multiRead_ = true;
          nextReadLba_ = lba;
        }
        break;
      }
      case 23: // SET_BLOCK_COUNT (non-app); no optional pre-erase policy.
        queueR1(status());
        break;
      case 24: // WRITE_BLOCK.
      case 25: { // WRITE_MULTIPLE_BLOCK.
        const auto lba = highCapacity_ ? argument : argument / 512;
        if (idle_ || lba >= sectorCount_) {
          queueR1(static_cast<std::uint8_t>(status() | 0x20));
        } else {
          queueR1(0);
          multiWrite_ = opcode == 25;
          nextWriteLba_ = lba;
          writeState_ = WriteState::WaitToken;
        }
        break;
      }
      case 55: // APP_CMD.
        appCommand_ = true;
        queueR1(status());
        break;
      case 58: { // READ_OCR, R3: 3.2..3.4V, CCS and power-up ready.
        queueR1(status());
        const std::uint32_t ocr = (idle_ ? 0 : 0x80000000U) |
            (highCapacity_ ? 0x40000000U : 0) | 0x00ff8000U;
        for (int shift = 24; shift >= 0; shift -= 8)
          queueByte(static_cast<std::uint8_t>(ocr >> shift));
        break;
      }
      case 59: // CRC_ON_OFF.
        crcEnabled_ = (argument & 1) != 0;
        queueR1(status());
        break;
      case 6: { // SWITCH_FUNC: default-speed-only, valid 64-byte status.
        queueR1(status());
        if (!idle_) {
          const std::array<std::uint8_t, 64> supportedDefaultOnly{};
          queueData(supportedDefaultOnly.data(), supportedDefaultOnly.size());
        }
        break;
      }
      default:
        // Unsupported optional commands are explicitly rejected. The MMC
        // driver may fall back to basic SD SPI rather than silently accepting
        // a command for which the model has no semantics.
        queueR1(static_cast<std::uint8_t>(status() | 0x04));
        break;
    }
  }

  std::ifstream disk_;
  std::uint64_t sectorCount_ = 0;
  std::uint32_t firstPartitionLba_ = 0;
  std::uint32_t partitionSectors_ = 0;
  std::unordered_map<std::uint64_t, Sector> overlay_;
  std::deque<std::uint8_t> output_;
  std::array<std::uint8_t, 6> command_{};
  std::size_t commandBytes_ = 0;
  std::array<std::uint8_t, 514> writePacket_{};
  std::size_t writeBytes_ = 0;
  WriteState writeState_ = WriteState::None;
  bool multiRead_ = false;
  bool multiWrite_ = false;
  bool appCommand_ = false;
  bool crcEnabled_ = false;
  bool highCapacity_ = false;
  bool idle_ = true;
  std::uint64_t nextReadLba_ = 0;
  std::uint64_t nextWriteLba_ = 0;
  std::uint64_t commands_ = 0;
  std::uint64_t reads_ = 0;
  std::uint64_t writes_ = 0;
  std::uint64_t unselectedClocks_ = 0;
  bool startupClockSequenceSeen_ = false;
  unsigned lastCommand_ = 0;
  bool lastSclk_ = false;
  bool lastCsHigh_ = true;
  bool miso_ = true;
  std::uint8_t rxByte_ = 0;
  unsigned rxBits_ = 0;
  std::uint8_t txByte_ = 0xff;
  unsigned txBit_ = 7;
};

} // namespace aethercore::l32sim
