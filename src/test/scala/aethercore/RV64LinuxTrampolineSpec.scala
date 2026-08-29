package aethercore

import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.core.AetherCore
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Linux-compatible Sv39 trampoline regression.
  *
  * Linux enables satp while still executing at the physical kernel address.
  * Its trampoline maps only the canonical high-half alias, so the first
  * post-satp physical fetch must page-fault into a high-half stvec. That stvec
  * fetch then has to walk a 2 MiB PMD leaf back to the same physical page.
  *
  * 这个测试冻结 Linux 真正使用的 Sv39 切换形态：物理 PC 开启 satp -> 指令页故障
  * -> S-mode 高半区 stvec -> 2 MiB superpage 翻译回同一物理页。
  */
class RV64LinuxTrampolineSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore RV64 Linux Sv39 trampoline"

  private val resetBase = BigInt("80000000", 16)
  private val supervisorPhysicalBase = BigInt("80200000", 16)
  private val supervisorVirtualBase = BigInt("ffffffff80000000", 16)
  private val postSatpOffset = BigInt("14", 16)
  private val postSatpPhysicalPc = supervisorPhysicalBase + postSatpOffset
  private val postSatpVirtualPc = supervisorVirtualBase + postSatpOffset

  private val rootPpn = BigInt("81000", 16)
  private val level1Ppn = BigInt("81001", 16)
  private val leafPpn = supervisorPhysicalBase >> 12

  private def vpn2(va: BigInt): BigInt = (va >> 30) & 0x1ff
  private def vpn1(va: BigInt): BigInt = (va >> 21) & 0x1ff

  private val physicalRootPteAddress = (rootPpn << 12) + (vpn2(postSatpPhysicalPc) << 3)
  private val rootPteAddress = (rootPpn << 12) + (vpn2(supervisorVirtualBase) << 3)
  private val level1PteAddress = (level1Ppn << 12) + (vpn1(supervisorVirtualBase) << 3)

  private val nop = BigInt("00000013", 16)
  private val mret = BigInt("30200073", 16)
  private val marker = BigInt("02a00513", 16) // addi a0, zero, 42

  private def iType(imm: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int = 0x13): BigInt =
    (BigInt(imm & 0xfff) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(opcode & 0x7f)

  private def uType(imm20: Int, rd: Int): BigInt =
    (BigInt(imm20 & 0xfffff) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x37)

  private def rType(rs2: Int, rs1: Int, rd: Int): BigInt =
    (BigInt(rs2 & 0x1f) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x33)

  private def csr(address: Int, source: Int): BigInt =
    (BigInt(address & 0xfff) << 20) |
      (BigInt(source & 0x1f) << 15) |
      (BigInt(1) << 12) |
      BigInt(0x73)

  private def slli(rd: Int, rs1: Int, shamt: Int): BigInt =
    iType(shamt, rs1, 1, rd)

  private def place(start: BigInt, words: Seq[BigInt]): Map[BigInt, BigInt] =
    words.zipWithIndex.map { case (word, index) => start + index * 4 -> word }.toMap

  private def pte(
      ppn: BigInt,
      read: Boolean = false,
      execute: Boolean = false,
      accessed: Boolean = false
  ): BigInt =
    (ppn << 10) | BigInt(1) |
      (if (read) BigInt(1) << 1 else BigInt(0)) |
      (if (execute) BigInt(1) << 3 else BigInt(0)) |
      (if (accessed) BigInt(1) << 6 else BigInt(0))

  private val rootPte = pte(level1Ppn)
  private val level1Leaf = pte(leafPpn, read = true, execute = true, accessed = true)

  private val machineProgram = place(
    resetBase,
    Seq(
      // PMP0 TOR RWX over the complete bounded PA56 domain.
      iType(-1, 0, 0, 5),
      csr(0x3b0, 5),
      iType(15, 0, 0, 5),
      csr(0x3a0, 5),

      // Delegate instruction page faults to S-mode.
      uType(1, 5),
      csr(0x302, 5),

      // stvec = 0xffffffff80000014. RV64 LUI sign-extends bit 31.
      uType(0x80000, 6),
      iType(postSatpOffset.toInt, 6, 0, 6),
      csr(0x105, 6),

      // mepc = 0x0000000080200000 without RV64 LUI sign-extension.
      iType(1, 0, 0, 2),
      slli(2, 2, 31),
      uType(0x200, 3),
      rType(3, 2, 2),
      csr(0x341, 2),

      // MPP=S then enter the physical Linux-style setup code.
      uType(1, 3),
      iType(-2048, 3, 0, 3),
      csr(0x300, 3),
      mret
    )
  )

  private val supervisorProgram = place(
    supervisorPhysicalBase,
    Seq(
      // satp = Sv39(mode=8) | rootPpn.
      iType(1, 0, 0, 1),
      slli(1, 1, 63),
      uType(0x81, 2),
      rType(2, 1, 1),
      csr(0x180, 1),
      // This physical fetch must fault after satp becomes active. The same
      // physical word is then fetched through high-half stvec translation.
      marker,
      nop
    )
  )

  it should "page-fault the post-satp physical PC and resume at the high-half stvec through a PMD leaf" in {
    val program = machineProgram ++ supervisorProgram

    simulate(new AetherCore(CoreProfiles.rv64imsuSv39PmpSoftware)) { dut =>
      dut.io.imem.inst.poke(nop.U)
      dut.io.imem.fault.poke(false.B)
      dut.io.dmem.ready.poke(true.B)
      dut.io.dmem.rdata.poke(0.U)
      dut.io.dmem.fault.poke(false.B)
      dut.io.ptw.get.ready.poke(false.B)
      dut.io.ptw.get.rdata.poke(0.U)
      dut.io.ptw.get.fault.poke(false.B)
      dut.io.timerInterrupt.poke(false.B)

      var cycles = 0
      var sawPhysicalPageFault = false
      var sawTranslatedTargetPa = false
      var sawHighHalfCommit = false
      var pteReads = Vector.empty[BigInt]

      while (!sawHighHalfCommit && cycles < 900) {
        dut.io.imem.fault.poke(false.B)
        if (dut.io.imem.valid.peek().litToBoolean) {
          val pa = dut.io.imem.addr.peek().litValue
          dut.io.imem.inst.poke(program.getOrElse(pa, nop).U)
          if (pa == postSatpPhysicalPc) sawTranslatedTargetPa = true
        } else {
          dut.io.imem.inst.poke(nop.U)
        }

        dut.io.ptw.get.ready.poke(false.B)
        dut.io.ptw.get.rdata.poke(0.U)
        dut.io.ptw.get.fault.poke(false.B)
        if (dut.io.ptw.get.valid.peek().litToBoolean) {
          val address = dut.io.ptw.get.addr.peek().litValue
          val value = address match {
            // The trampoline deliberately does not identity-map the physical
            // post-satp PC. Returning an invalid root PTE forces the intended
            // delegated instruction page fault into the high-half stvec.
            case `physicalRootPteAddress` => BigInt(0)
            case `rootPteAddress` => rootPte
            case `level1PteAddress` => level1Leaf
            case other => fail(f"unexpected Linux trampoline PTW address 0x$other%x")
          }
          pteReads :+= address
          dut.io.ptw.get.rdata.poke(value.U)
          dut.io.ptw.get.ready.poke(true.B)
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          val pc = dut.io.commit.pc.peek().litValue
          if (pc == postSatpPhysicalPc && dut.io.commit.exception.peek().litToBoolean) {
            dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionPageFault.U)
            dut.io.commit.exceptionValue.expect(postSatpPhysicalPc.U)
            sawPhysicalPageFault = true
          }
          if (pc == postSatpVirtualPc) {
            dut.io.commit.exception.expect(false.B)
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rd.expect(10.U)
            dut.io.commit.rdData.expect(42.U)
            sawHighHalfCommit = true
          }
        }
      }

      sawPhysicalPageFault shouldBe true
      sawTranslatedTargetPa shouldBe true
      sawHighHalfCommit shouldBe true
      pteReads should contain(physicalRootPteAddress)
      pteReads should contain(rootPteAddress)
      pteReads should contain(level1PteAddress)
      vpn2(supervisorVirtualBase) shouldBe BigInt(510)
      (leafPpn & BigInt("1ff", 16)) shouldBe BigInt(0)
    }
  }
}
