package aethercore.soc

import aethercore.soc.phy.{AetherFractionalTickGenerator, AetherUart8N1Phy}
import chisel3._
import chisel3.util._

/** Board-neutral, synthesizable UART source for the Product FPGA DDR loader.
  *
  * The host waits for 'R' after DDR ownership is granted, then uploads two
  * frames in order. Each frame begins with the 13-byte little-endian header:
  *
  *   ASCII "AETU" | kind:u8 (1=fw_payload, 2=DTB) |
  *   byteCount:u32 | IEEE CRC32 of the exact file bytes:u32.
  *
  * The FPGA replies 'H' when the descriptor has been accepted. The host then
  * sends at most 1024 bytes, and waits for 'K' before sending the next window.
  * A window credit is returned only after all its words have reached the DDR
  * writer and the final AXI B for the window has drained. No arbitrary UART
  * backpressure is assumed: the entire granted window fits a finite 1024-byte
  * FIFO even if the DDR controller stalls during every byte of that window.
  *
  * The last DTB window deliberately has no acknowledgement: the CPU may be
  * released as soon as its final B response succeeds, and would truncate an
  * upload-PHY acknowledgement on the UART pad. Its CRC is checked BEFORE the
  * final word is offered to the DDR stream loader. The READY beacon repeats
  * until the first descriptor starts, so a late host cannot miss DDR grant.
  * The Linux console then
  * owns TX at its independently configured baud rate. A transfer failure is
  * sticky until full board reset; there is no unsafe in-place retry.
  *
  * A board wrapper must mux TX to Linux at cpuReleased, and feed its physical
  * RX into this source while loaderOwnsDdr. DDR PHY, PLL and the USB-UART
  * electrical interface remain board-owned. This module uses only a fractional
  * clock enable, never a second clock domain.
  */
class AetherProductUartFirmwareUpload(
    val addrBits: Int = AetherSoCBoardSpec.FpgaImplementedPaddrBits,
    val boardClockHz: Long = 50_000_000L,
    val uploadBaud: Long = 1_000_000L,
    val windowBytes: Int = 1024,
    val interByteTimeoutSeconds: Int = 30,
    val firmwareAddress: BigInt = BigInt("80000000", 16),
    val deviceTreeAddress: BigInt = BigInt("87f00000", 16),
    val deviceTreeMaxBytes: BigInt = BigInt("00100000", 16)
) extends Module {
  require(addrBits >= 32)
  require(boardClockHz > 0 && uploadBaud > 0 && uploadBaud * 16 <= boardClockHz,
    "UART x16 tick must be representable by the physical Product clock")
  require(windowBytes >= 8 && windowBytes <= 1024 && windowBytes % 8 == 0)
  require(interByteTimeoutSeconds > 0)
  require(firmwareAddress >= 0 && deviceTreeAddress > firmwareAddress)
  require(deviceTreeAddress + deviceTreeMaxBytes <= (BigInt(1) << addrBits))
  require(deviceTreeAddress - firmwareAddress <= BigInt("ffffffff", 16))
  require(deviceTreeMaxBytes <= BigInt("ffffffff", 16))

  val io = IO(new Bundle {
    val loaderEnabled = Input(Bool())
    val serialRx = Input(Bool())
    val serialTx = Output(Bool())
    val segment = Decoupled(new AetherProductDdrImageSegment(addrBits))
    val wordOut = Decoupled(new AetherProductDdrImageWord)
    val failed = Output(Bool())
    val receivedBytes = Output(UInt(32.W))
    val drainedBytes = Output(UInt(32.W))
  })

  // A 1MHz upload on a 50MHz board uses a 16MHz fractional x16 UART tick.
  // The Linux ns16550 console keeps its own 3.6864MHz/115200 tick contract.
  private val tick = Module(new AetherFractionalTickGenerator(
    sourceFrequencyHz = boardClockHz,
    targetFrequencyHz = uploadBaud * 16))
  private val phy = withReset(reset.asBool || !io.loaderEnabled) {
    Module(new AetherUart8N1Phy)
  }
  phy.io.uartClockTick := tick.io.tick
  phy.io.baudDivisor := 1.U
  phy.io.serialRx := io.serialRx
  io.serialTx := Mux(io.loaderEnabled, phy.io.serialTx, true.B)

  private val frameHeader = Reg(Vec(13, UInt(8.W)))
  private val headerIndex = RegInit(0.U(4.W))
  private val frameIndex = RegInit(0.U(1.W))
  private val frameLength = RegInit(0.U(32.W))
  private val expectedCrc = RegInit(0.U(32.W))
  private val crc = RegInit("hffffffff".U(32.W))
  private val received = RegInit(0.U(32.W))
  private val sentIntoLoader = RegInit(0.U(32.W))
  private val drained = RegInit(0.U(32.W))
  private val receivedInWindow = RegInit(0.U(log2Ceil(windowBytes + 1).W))
  private val windowEnd = RegInit(0.U(32.W))
  private val crcVerified = RegInit(false.B)

  io.receivedBytes := received
  io.drainedBytes := drained
  // The UART has no flow-control wire. A complete host credit fits this FIFO
  // even if the DDR writer accepts zero beats until the host stops sending.
  private val byteFifo = Module(new Queue(UInt(8.W), windowBytes))

  private val pBeacon :: pHeader :: pOfferDescriptor :: pAckHeader :: pPayload :: pWaitCredit :: pAckWindow :: pFinalDrain :: pDone :: pFault :: Nil = Enum(10)
  private val state = RegInit(pBeacon)
  io.failed := state === pFault

  private val expectedKind = Mux(frameIndex === 0.U, 1.U(8.W), 2.U(8.W))
  private val declaredLength = Cat(frameHeader(8), frameHeader(7),
    frameHeader(6), frameHeader(5))
  private val declaredCrc = Cat(phy.io.rxByte, frameHeader(11),
    frameHeader(10), frameHeader(9))
  private val maxFrameBytes = Mux(frameIndex === 0.U,
    (deviceTreeAddress - firmwareAddress).U(32.W), deviceTreeMaxBytes.U(32.W))
  private val goodHeader = frameHeader(0) === 0x41.U &&
    frameHeader(1) === 0x45.U && frameHeader(2) === 0x54.U &&
    frameHeader(3) === 0x55.U && frameHeader(4) === expectedKind &&
    declaredLength > 0.U && declaredLength <= maxFrameBytes

  io.segment.valid := state === pOfferDescriptor && io.loaderEnabled
  io.segment.bits.address := Mux(frameIndex === 0.U,
    firmwareAddress.U(addrBits.W), deviceTreeAddress.U(addrBits.W))
  io.segment.bits.byteCount := frameLength.pad(addrBits + 1)
  io.segment.bits.lastSegment := frameIndex === 1.U

  private val pAcceptHeader = state === pHeader
  private val pAcceptPayload = state === pPayload && receivedInWindow < windowBytes.U
  phy.io.rxReady := io.loaderEnabled &&
    (pAcceptHeader || (pAcceptPayload && byteFifo.io.enq.ready))
  private val rxFire = phy.io.rxValid && phy.io.rxReady
  byteFifo.io.enq.valid := io.loaderEnabled && pAcceptPayload && phy.io.rxValid
  byteFifo.io.enq.bits := phy.io.rxByte

  // A sender violating the granted window may fill the UART's one-byte RX
  // holding register. A *new* start edge while that register cannot drain is
  // explicitly a sticky overflow, never an invisible dropped upload byte.
  private val rxMeta = RegNext(io.serialRx, true.B)
  private val rxSync = RegNext(rxMeta, true.B)
  private val rxPrevious = RegNext(rxSync, true.B)
  private val unauthorizedStart = io.loaderEnabled && phy.io.rxValid &&
    !phy.io.rxReady && rxPrevious && !rxSync

  private val wCollect :: wOffer :: wAwaitResponse :: wComplete :: Nil = Enum(4)
  private val workerState = RegInit(wComplete)
  private val wordBytes = RegInit(VecInit(Seq.fill(8)(0.U(8.W))))
  private val wordCount = RegInit(0.U(4.W))
  private val pendingWordCount = RegInit(0.U(4.W))
  private val pendingLastWord = RegInit(false.B)
  private val wordEndingByte = RegInit(0.U(32.W))

  byteFifo.io.deq.ready := io.loaderEnabled && workerState === wCollect &&
    sentIntoLoader < frameLength && state =/= pFault
  when(byteFifo.io.deq.fire) {
    wordBytes(wordCount(2, 0)) := byteFifo.io.deq.bits
    val following = sentIntoLoader + 1.U
    sentIntoLoader := following
    when(wordCount === 7.U || following === frameLength) {
      pendingWordCount := wordCount + 1.U
      pendingLastWord := following === frameLength
      wordEndingByte := following
      workerState := wOffer
    }.otherwise {
      wordCount := wordCount + 1.U
    }
  }

  io.wordOut.valid := workerState === wOffer && io.loaderEnabled &&
    (!pendingLastWord || crcVerified) && state =/= pFault
  io.wordOut.bits.data := Cat((0 until 8).reverse.map(wordBytes(_)))
  io.wordOut.bits.validBytes := pendingWordCount
  io.wordOut.bits.lastWord := pendingLastWord

  when(io.wordOut.fire) {
    wordCount := 0.U
    workerState := wAwaitResponse
  }
  when(workerState === wAwaitResponse) {
    // After a nonterminal word, ready means the loader has received its B
    // response and is back in receive state. The firmware's last word instead
    // transitions the loader into its second-descriptor accept state.
    when(pendingLastWord && frameIndex === 1.U) {
      workerState := wComplete
    }.elsewhen((pendingLastWord && io.segment.ready) ||
      (!pendingLastWord && io.wordOut.ready)) {
      drained := wordEndingByte
      workerState := Mux(pendingLastWord, wComplete, wCollect)
    }
  }
  // Receipt and CRC verification are distinct from offering the final beat.
  // Only the downstream loader, after its last AXI B, authorizes CPU release.
  // This terminal parser state is intentionally NOT a CPU-release signal.
  when(state === pFinalDrain && workerState === wComplete) {
    state := pDone
  }

  // Reflected IEEE CRC32, byte-wise streaming; equal to Python zlib.crc32.
  private def crc32Next(previous: UInt, byte: UInt): UInt = {
    var current = previous ^ byte.pad(32)
    for (_ <- 0 until 8) {
      current = Mux(current(0), (current >> 1) ^ "hedb88320".U(32.W),
        current >> 1)
    }
    current
  }

  private val nextCrc = crc32Next(crc, phy.io.rxByte)
  private val timeoutCycles = BigInt(boardClockHz) * interByteTimeoutSeconds
  private val timeoutBits = (timeoutCycles + 1).bitLength
  private val idleCounter = RegInit(0.U(timeoutBits.W))
  private val awaitingBytes = (state === pHeader && headerIndex =/= 0.U) ||
    state === pPayload
  // Waiting forever on a stalled final DDR B would silently prevent Linux
  // boot. The finished parser (pDone) therefore watches for the controller
  // to revoke the loader grant after its final B and CPU handoff.
  private val awaitingDdr = state === pOfferDescriptor || state === pWaitCredit ||
    state === pFinalDrain || (state === pDone && io.loaderEnabled)
  when((!awaitingBytes && !awaitingDdr) || rxFire || io.segment.fire ||
      io.wordOut.fire) {
    idleCounter := 0.U
  }.elsewhen(idleCounter >= timeoutCycles.U) {
    state := pFault
  }.otherwise {
    idleCounter := idleCounter + 1.U
  }

  // READY repeats before the first header. Each descriptor gets an ACK;
  // each 1024-byte window gets one credit. The DTB's final credit has no ACK because
  // Linux may claim the physical UART pins immediately after the final B.
  private val beaconIntervalCycles = BigInt(boardClockHz) / 2
  require(beaconIntervalCycles > 0)
  private val beaconCounter = RegInit(0.U((beaconIntervalCycles + 1).bitLength.W))
  private val firstHeaderIdle = state === pHeader && frameIndex === 0.U &&
    headerIndex === 0.U && io.loaderEnabled
  private val repeatBeacon = firstHeaderIdle &&
    beaconCounter >= (beaconIntervalCycles - 1).U
  when(!firstHeaderIdle || rxFire || (repeatBeacon && phy.io.txReady)) {
    beaconCounter := 0.U
  }.otherwise {
    beaconCounter := beaconCounter + 1.U
  }
  phy.io.txValid := state === pBeacon || repeatBeacon ||
    state === pAckHeader || state === pAckWindow
  phy.io.txByte := Mux(state === pBeacon || repeatBeacon, 0x52.U,
    Mux(state === pAckHeader, 0x48.U, 0x4b.U))
  when(io.loaderEnabled && phy.io.txValid && phy.io.txReady) {
    switch(state) {
      is(pBeacon) { state := pHeader }
      is(pAckHeader) { state := pPayload }
      is(pAckWindow) {
        receivedInWindow := 0.U
        when(received === frameLength) {
          frameIndex := 1.U
          headerIndex := 0.U
          state := pHeader
        }.otherwise { state := pPayload }
      }
    }
  }

  when(rxFire && state === pHeader) {
    frameHeader(headerIndex) := phy.io.rxByte
    when(headerIndex === 12.U) {
      when(!goodHeader) {
        state := pFault
      }.otherwise {
        frameLength := declaredLength
        expectedCrc := declaredCrc
        crc := "hffffffff".U
        crcVerified := false.B
        received := 0.U
        sentIntoLoader := 0.U
        drained := 0.U
        receivedInWindow := 0.U
        headerIndex := 0.U
        workerState := wComplete
        state := pOfferDescriptor
      }
    }.otherwise { headerIndex := headerIndex + 1.U }
  }

  when(io.segment.fire) {
    workerState := wCollect
    wordCount := 0.U
    state := pAckHeader
  }

  when(rxFire && state === pPayload) {
    val following = received + 1.U
    val nextWindow = receivedInWindow + 1.U
    received := following
    receivedInWindow := nextWindow
    crc := nextCrc
    val finalByte = following === frameLength
    when(finalByte) {
      when((~nextCrc).asUInt =/= expectedCrc) {
        state := pFault
      }.otherwise {
        crcVerified := true.B
      }
    }
    when(!finalByte || ((~nextCrc).asUInt === expectedCrc)) {
      when(finalByte && frameIndex === 1.U) {
        // No final ACK is sent. Wait for the worker to hand the final CRC-
        // verified beat to the DDR loader before declaring parser completion.
        // The loader itself still waits for B before releasing the CPU.
        state := pFinalDrain
      }.elsewhen(nextWindow === windowBytes.U || finalByte) {
        windowEnd := following
        state := pWaitCredit
      }
    }
  }

  when(state === pWaitCredit && drained >= windowEnd) {
    state := pAckWindow
  }
  when(unauthorizedStart) {
    state := pFault
  }
  // On a very fast DDR B, the board may revoke its loader grant before this
  // source has registered pDone (one cycle after the final word handshake).
  // That is a legitimate handoff, not a source-side mid-upload fault. A
  // revocation BEFORE the final word is accepted still fails closed; board
  // reset/DDR errors remain independently sticky in the cold-boot fabric.
  private val finalWordAlreadySubmitted = state === pFinalDrain &&
    pendingLastWord && (workerState === wAwaitResponse ||
      workerState === wComplete)
  when(!io.loaderEnabled && state =/= pBeacon && state =/= pDone &&
      state =/= pFault && !finalWordAlreadySubmitted) {
    state := pFault
  }
  when(state === pFault) {
    assert(!io.segment.valid && !io.wordOut.valid,
      "a failed UART firmware stream may not issue further DDR writes")
  }
}
