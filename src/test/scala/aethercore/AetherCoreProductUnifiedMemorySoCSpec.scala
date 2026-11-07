package aethercore

import aethercore.common.MachineExceptionCode
import aethercore.memory.AetherMemOp
import aethercore.soc.{AetherCoreProductSoCProfiles, AetherCoreProductUnifiedMemorySoC, AetherSoCAddressMap, AetherSoCBoardSpec}
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Exercises the real CPU -> I-cache/BootROM -> MemoryHub -> external-RAM
  * request path, and CPU -> PMA -> PlatformFabric -> UART MMIO write path.
  * The testbench models only the exported RAM responder; no instruction or
  * privileged-state seam is patched around the Product core.
  */
class AetherCoreProductUnifiedMemorySoCSpec
    extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product SmallOoO unified-memory SoC"

  private val boot = AetherSoCAddressMap.QualifiedBootRomBase
  private val ram = BigInt("80000000", 16)
  private val plicBase = BigInt("0c000000", 16)
  private val plicPriority17 = plicBase + 0x44
  private val plicPriority52 = plicBase + 0xd0
  private val plicSupervisorEnableSecondWord = plicBase + 0x2084

  private def sw(rs2: Int, offset: Int, rs1: Int): BigInt = {
    require(rs2 >= 0 && rs2 < 32 && rs1 >= 0 && rs1 < 32)
    require(offset >= 0 && offset < 2048 && (offset & 3) == 0)
    BigInt((((offset >> 5) & 0x7f) << 25) |
      (rs2 << 20) | (rs1 << 15) | (2 << 12) |
      ((offset & 0x1f) << 7) | 0x23)
  }

  it should "select the ROM reset and preserve the board OpenSBI ISA/ABI and timing contract" in {
    val target = AetherCoreProductSoCProfiles.smallOpenSbiEngineering(32)
    target.platform.resetVector shouldBe boot
    target.platform.paddrBits shouldBe 32
    target.software.isa.hasC shouldBe true
    target.software.isa.hasZifencei shouldBe true
    target.software.isa.hasTimeCounter shouldBe true
    target.software.isa.hasMachineProvidedSupervisorTimer shouldBe true
    target.software.isa.extensions.contains('F') shouldBe false
    target.software.isa.extensions.contains('D') shouldBe false
    target.mabi shouldBe "lp64"
    target.software.isa.pmpEntries shouldBe 16
    // The unchanged OpenSBI firmware DTB advertises riscv,ndev=52. Product
    // implements those sources without changing the qualified V2 16-source
    // board contract.
    AetherCoreProductSoCProfiles.OpenSbiPlicSourceCount shouldBe 52
    AetherSoCBoardSpec.QualifiedPlicSourceCount shouldBe 16
    AetherSoCBoardSpec.qualifiedLinux(target.platform).addressMap.bootRomBase shouldBe boot
  }

  it should "execute BootROM into external RAM and drive UART MMIO through the Product LSU" in {
    simulate(new AetherCoreProductUnifiedMemorySoC()) { d =>
      d.io.rxValid.poke(false.B)
      d.io.rxByte.poke(0.U)
      d.io.memoryRequest.ready.poke(false.B)
      d.io.memoryResponse.valid.poke(false.B)
      d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
      d.clock.step()

      // The ROM performs 0x1000 -> 0x1004 -> 0x1008 -> 0x80000000.
      // The RAM program reads the same cacheable word twice, writes 'A' to
      // UART, then initializes PLIC priority IDs 17 and 52 and the second
      // supervisor enable word (matching the unchanged OpenSBI riscv,ndev=52
      // DTB). Finally it deliberately JALRs to UART MMIO, which must fault.
      // BootROM leaves x5 = 0x80000000.
      val words = Map[BigInt, BigInt](
        ram -> BigInt("02a00313", 16),       // addi x6,x0,42
        (ram + 4) -> BigInt("0402a503", 16), // lw x10,64(x5), D-cache miss
        (ram + 8) -> BigInt("0000100f", 16), // fence.i, retire after first LW
        (ram + 12) -> BigInt("0402a583", 16), // lw x11,64(x5), D-cache hit
        (ram + 16) -> BigInt("100003b7", 16), // lui x7,0x10000
        (ram + 20) -> BigInt("04100413", 16), // addi x8,x0,65
        (ram + 24) -> BigInt("00838023", 16), // sb x8,0(x7), MMIO bypass
        (ram + 28) -> BigInt("0c0004b7", 16), // lui x9,0xc000 -> PLIC base
        (ram + 32) -> BigInt("00300413", 16), // addi x8,x0,3
        (ram + 36) -> sw(8, 0x44, 9),       // sw x8,0x44(x9): priority ID17
        (ram + 40) -> sw(8, 0xd0, 9),       // sw x8,0xd0(x9): priority ID52
        (ram + 44) -> BigInt("0c002537", 16), // lui x10,0xc002
        (ram + 48) -> sw(8, 0x84, 10),      // sw x8,0x84(x10): S enable word1
        (ram + 52) -> BigInt("00038067", 16), // jalr x0,x7,0 -> UART execute fault
        (ram + 64) -> BigInt("00000055", 16)
      )
      val plicWrites = Map(
        (ram + 36) -> plicPriority17,
        (ram + 40) -> plicPriority52,
        (ram + 48) -> plicSupervisorEnableSecondWord
      )
      // Byte-accurate memory for 16/32-bit frontend fetch parcels. External
      // AetherMem reads return their first requested byte in rdata[7:0].
      def byteAt(address: BigInt): BigInt = {
        val aligned = address & ~BigInt(3)
        val word = words.getOrElse(aligned, BigInt("00000013", 16))
        (word >> ((address - aligned).toInt * 8)) & 255
      }
      def readAt(address: BigInt): BigInt =
        (0 until 8).foldLeft(BigInt(0)) { (acc, offset) =>
          acc | (byteAt(address + offset) << (8 * offset))
        }

      var pending = Option.empty[(BigInt, BigInt)]
      var observedExternal = Set.empty[BigInt]
      var retired = Set.empty[BigInt]
      var sawOperand = false
      var sawFirstLoad = false
      var sawCachedLoad = false
      var sawUart = false
      var sawExecuteFault = false
      var retiredPlicWrites = Set.empty[BigInt]
      for (_ <- 0 until 1600
           if !(sawUart && sawOperand && sawFirstLoad && sawCachedLoad &&
             retiredPlicWrites.size == plicWrites.size && sawExecuteFault)) {
        val ready = pending.isEmpty
        d.io.memoryRequest.ready.poke(ready.B)
        d.io.memoryResponse.valid.poke(pending.nonEmpty.B)
        pending.foreach { case (id, data) =>
          d.io.memoryResponse.bits.txnId.poke(id.U)
          d.io.memoryResponse.bits.rdata.poke(data.U)
          d.io.memoryResponse.bits.fault.poke(false.B)
          d.io.memoryResponse.bits.last.poke(true.B)
        }

        val issue = ready && d.io.memoryRequest.valid.peek().litToBoolean
        val request = if (issue) {
          d.io.memoryRequest.bits.op.expect(AetherMemOp.Read)
          val address = d.io.memoryRequest.bits.paddr.peek().litValue
          address should be >= ram
          observedExternal += address
          Some(d.io.memoryRequest.bits.txnId.peek().litValue -> readAt(address))
        } else None
        val complete = pending.nonEmpty && d.io.memoryResponse.ready.peek().litToBoolean

        if (d.io.retire.valid.peek().litToBoolean) {
          val pc = d.io.retire.bits.pc.peek().litValue
          retired += pc
          if (pc == ram) {
            d.io.retire.bits.exception.valid.expect(false.B)
            d.io.retire.bits.rd.expect(6.U)
            d.io.retire.bits.value.expect(42.U)
            sawOperand = true
          }
          if (pc == ram + 4 || pc == ram + 12) {
            d.io.retire.bits.exception.valid.expect(false.B)
            d.io.retire.bits.value.expect(85.U)
            if (pc == ram + 4) sawFirstLoad = true
            else sawCachedLoad = true
          }
          plicWrites.get(pc).foreach { address =>
            d.io.retire.bits.exception.valid.expect(false.B)
            d.io.retire.bits.memoryTrace.valid.expect(true.B)
            d.io.retire.bits.memoryTrace.write.expect(true.B)
            d.io.retire.bits.memoryTrace.paddr.expect(address.U)
            d.io.retire.bits.memoryTrace.wdata.expect(3.U)
            d.io.retire.bits.memoryTrace.wmask.expect(15.U)
            retiredPlicWrites += pc
          }
          if (pc == BigInt("10000000", 16)) {
            d.io.retire.bits.exception.valid.expect(true.B)
            d.io.retire.bits.exception.cause.expect(
              MachineExceptionCode.InstructionAccessFault.U)
            d.io.retire.bits.exception.value.expect(pc.U)
            sawExecuteFault = true
          }
        }
        if (d.io.uartValid.peek().litToBoolean) {
          d.io.uartByte.expect(65.U)
          sawUart = true
        }
        d.clock.step()
        if (complete) pending = None
        if (request.nonEmpty) pending = request
      }

      retired should contain(boot)
      retired should contain(boot + 4)
      retired should contain(boot + 8)
      retired should contain(ram)
      retired should contain(ram + 24)
      retiredPlicWrites shouldBe plicWrites.keySet
      observedExternal should contain(ram)
      observedExternal should contain(ram + 64)
      observedExternal.forall(_ >= ram) shouldBe true
      sawOperand shouldBe true
      sawFirstLoad shouldBe true
      sawCachedLoad shouldBe true
      sawUart shouldBe true
      sawExecuteFault shouldBe true
      d.io.dcacheMissCount.peek().litValue should be >= BigInt(1)
      d.io.dcacheHitCount.peek().litValue should be >= BigInt(1)
      d.io.dcacheBypassCount.peek().litValue should be >= BigInt(1)
      d.io.mtime.peek().litValue should be > BigInt(0)
    }
  }
}
