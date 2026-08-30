package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.common.{MachineExceptionCode, PrivilegeMode}
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.v2.TinyPagedCore

trait V2F7PagedCoreChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private val Config = CoreProfiles.rv32imasuSv32PmpSoftware
  private val Geometry = PageTableGeometry.Sv32
  private val Reset = Config.platform.resetVector

  private val SupervisorVa = BigInt("00004000", 16)
  private val RootPpn = BigInt(1)
  private val Level0Ppn = BigInt(2)
  private val LeafPpn = BigInt(3)
  private val RootPteAddress = BigInt("00001000", 16)
  private val LeafPteAddress = BigInt("00002010", 16)
  private val SupervisorPa = BigInt("00003000", 16)
  private val DataVa = BigInt("00005000", 16)
  private val DataPa = BigInt("00004000", 16)
  private val DataLeafPteAddress = BigInt("00002014", 16)

  private def initialize(dut: TinyPagedCore): Unit = {
    dut.io.imem.inst.poke("h00000013".U)
    dut.io.imem.fault.poke(false.B)
    dut.io.time.foreach(_.poke(0.U))

    dut.io.ptw.ready.poke(true.B)
    dut.io.ptw.rdata.poke(0.U)
    dut.io.ptw.fault.poke(false.B)

    dut.io.resolvedAttributes.cacheable.poke(true.B)
    dut.io.resolvedAttributes.idempotent.poke(true.B)
    dut.io.resolvedAttributes.sideEffecting.poke(false.B)
    dut.io.resolvedAttributes.ordered.poke(false.B)
    dut.io.resolvedAttributes.executable.poke(false.B)
    dut.io.resolvedAttributes.supportsAtomic.poke(false.B)
    dut.io.resolvedAttributes.supportsPartial.poke(true.B)

    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.txnId.poke(0.U)
    dut.io.memoryResponse.bits.rdata.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.last.poke(true.B)
  }

  /**
    * Real RV32 machine-mode setup program. Keeping the same instruction layout
    * in all tests makes it obvious that only PMP/page-table state changed.
    */
  private def machineProgram(enablePmp: Boolean): Map[BigInt, BigInt] = {
    val pmpSetup = if (enablePmp) Seq(
      BigInt("fff00093", 16), // addi x1,x0,-1
      BigInt("3b009073", 16), // csrw pmpaddr0,x1
      BigInt("01f00093", 16), // addi x1,x0,31 = RWX + NAPOT
      BigInt("3a009073", 16)  // csrw pmpcfg0,x1
    ) else Seq.fill(4)(BigInt("00000013", 16))

    val setup = pmpSetup ++ Seq(
      BigInt("800000b7", 16), // lui  x1,0x80000
      BigInt("00108093", 16), // addi x1,x1,1 -> Sv32 MODE | root PPN 1
      BigInt("18009073", 16), // csrw satp,x1
      BigInt("000040b7", 16), // lui  x1,4 -> 0x4000
      BigInt("34109073", 16), // csrw mepc,x1
      BigInt("000010b7", 16), // lui  x1,1 -> 0x1000
      BigInt("80008093", 16), // addi x1,x1,-2048 -> 0x800 (MPP=S)
      BigInt("30009073", 16), // csrw mstatus,x1
      BigInt("30200073", 16)  // mret
    )

    setup.zipWithIndex.map { case (inst, index) =>
      (Reset + index * 4) -> inst
    }.toMap
  }

  private def pte(ppn: BigInt, read: Boolean, execute: Boolean, accessed: Boolean): BigInt =
    (ppn << 10) |
      BigInt(1) |
      (if (read) BigInt(1) << 1 else BigInt(0)) |
      (if (execute) BigInt(1) << 3 else BigInt(0)) |
      (if (accessed) BigInt(1) << 6 else BigInt(0))

  private def writableDataPte(ppn: BigInt): BigInt =
    (ppn << 10) |
      BigInt(1) |          // V
      (BigInt(1) << 1) |  // R
      (BigInt(1) << 2) |  // W
      (BigInt(1) << 6) |  // A
      (BigInt(1) << 7)    // D

  /**
    * Machine-mode setup for a precise data-PMP denial:
    *   entry0 = NA4 deny at physical 0x4000
    *   entry1 = NAPOT allow-all
    * then enter S-mode with Sv32 rooted at PPN 1.
    *
    * Entry priority makes instruction fetch and PTW traffic legal while the
    * one translated Store target is denied by the first matching PMP entry.
    */
  private def machineProgramDenyDataStore(): Map[BigInt, BigInt] = {
    val setup = Seq(
      BigInt("000010b7", 16), // lui  x1,1      -> pmpaddr0 = 0x1000 = 0x4000 >> 2
      BigInt("3b009073", 16), // csrw pmpaddr0,x1
      BigInt("fff00093", 16), // addi x1,x0,-1
      BigInt("3b109073", 16), // csrw pmpaddr1,x1
      BigInt("000020b7", 16), // lui  x1,2      -> 0x2000
      BigInt("f1008093", 16), // addi x1,x1,-240 -> 0x1f10
      BigInt("3a009073", 16), // pmpcfg0: entry0 NA4 no-perm, entry1 NAPOT RWX
      BigInt("800000b7", 16), // lui  x1,0x80000
      BigInt("00108093", 16), // addi x1,x1,1 -> Sv32 MODE | root PPN 1
      BigInt("18009073", 16), // csrw satp,x1
      BigInt("000040b7", 16), // lui  x1,4 -> S-mode PC 0x4000
      BigInt("34109073", 16), // csrw mepc,x1
      BigInt("000010b7", 16), // lui  x1,1
      BigInt("80008093", 16), // addi x1,x1,-2048 -> MPP=S
      BigInt("30009073", 16), // csrw mstatus,x1
      BigInt("30200073", 16)  // mret
    )
    setup.zipWithIndex.map { case (inst, index) =>
      (Reset + index * 4) -> inst
    }.toMap
  }

  private def driveExternal(
      dut: TinyPagedCore,
      instructions: Map[BigInt, BigInt],
      pageTable: Map[BigInt, BigInt],
      seenImem: mutable.ArrayBuffer[BigInt],
      seenPtw: mutable.ArrayBuffer[BigInt]
  ): Unit = {
    val imemAddress = dut.io.imem.addr.peek().litValue
    dut.io.imem.inst.poke(instructions.getOrElse(imemAddress, BigInt("00000013", 16)).U)
    dut.io.imem.fault.poke(false.B)
    if (dut.io.imem.valid.peek().litToBoolean) {
      seenImem += imemAddress
    }

    dut.io.ptw.ready.poke(true.B)
    dut.io.ptw.fault.poke(false.B)
    if (dut.io.ptw.valid.peek().litToBoolean) {
      val ptwAddress = dut.io.ptw.addr.peek().litValue
      seenPtw += ptwAddress
      dut.io.ptw.rdata.poke(pageTable.getOrElse(ptwAddress, BigInt(0)).U)
    } else {
      dut.io.ptw.rdata.poke(0.U)
    }
  }

  behavior of "AetherCore v2 F7 paged instruction frontend"

  it should "enter S-mode and execute a real Sv32 translated instruction at its virtual PC" in {
    simulate(new TinyPagedCore(Config, Geometry)) { dut =>
      initialize(dut)

      val instructions = machineProgram(enablePmp = true) +
        (SupervisorPa -> BigInt("00900293", 16)) // addi x5,x0,9 at PA 0x3000
      val pageTable = Map(
        RootPteAddress -> pte(Level0Ppn, read = false, execute = false, accessed = false),
        LeafPteAddress -> pte(LeafPpn, read = true, execute = true, accessed = true)
      )
      val seenImem = mutable.ArrayBuffer.empty[BigInt]
      val seenPtw = mutable.ArrayBuffer.empty[BigInt]

      var cycles = 0
      var supervisorCommit = false
      while (cycles < 700 && !supervisorCommit) {
        driveExternal(dut, instructions, pageTable, seenImem, seenPtw)

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == SupervisorVa) {
          dut.io.commit.exception.expect(false.B)
          dut.io.commit.rdWrite.expect(true.B)
          dut.io.commit.rd.expect(5.U)
          dut.io.commit.rdData.expect(9.U)
          dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
          supervisorCommit = true
        }

        dut.clock.step()
        cycles += 1
      }

      withClue("translated supervisor instruction never retired: ") {
        supervisorCommit shouldBe true
      }
      withClue("Sv32 level-1 root PTE was never externally read: ") {
        seenPtw should contain (RootPteAddress)
      }
      withClue("Sv32 level-0 leaf PTE was never externally read: ") {
        seenPtw should contain (LeafPteAddress)
      }
      withClue("translated physical instruction address was never fetched: ") {
        seenImem should contain (SupervisorPa)
      }
    }
  }

  it should "retire an invalid Sv32 instruction leaf as a precise instruction page fault" in {
    simulate(new TinyPagedCore(Config, Geometry)) { dut =>
      initialize(dut)

      val instructions = machineProgram(enablePmp = true) +
        (SupervisorPa -> BigInt("00900293", 16))
      val pageTable = Map(
        RootPteAddress -> pte(Level0Ppn, read = false, execute = false, accessed = false),
        LeafPteAddress -> BigInt(0)
      )
      val seenImem = mutable.ArrayBuffer.empty[BigInt]
      val seenPtw = mutable.ArrayBuffer.empty[BigInt]

      var cycles = 0
      var faultCommit = false
      while (cycles < 700 && !faultCommit) {
        driveExternal(dut, instructions, pageTable, seenImem, seenPtw)

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == SupervisorVa &&
            dut.io.commit.exception.peek().litToBoolean) {
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionPageFault.U)
          dut.io.commit.exceptionValue.expect(SupervisorVa.U)
          dut.io.commit.rdWrite.expect(false.B)
          faultCommit = true
        }

        dut.clock.step()
        cycles += 1
      }

      withClue("invalid execute leaf did not retire as instruction page fault: ") {
        faultCommit shouldBe true
      }
      seenPtw should contain (RootPteAddress)
      seenPtw should contain (LeafPteAddress)
      withClue("page-faulting target emitted a physical instruction request: ") {
        seenImem should not contain (SupervisorPa)
      }
    }
  }

  it should "deny an S-mode Store through the unified data PMP without external memory traffic" in {
    simulate(new TinyPagedCore(Config, Geometry)) { dut =>
      initialize(dut)

      val instructions = machineProgramDenyDataStore() ++ Map(
        SupervisorPa -> BigInt("00005137", 16),       // lui  x2,5 -> VA 0x5000
        (SupervisorPa + 4) -> BigInt("00100193", 16), // addi x3,x0,1
        (SupervisorPa + 8) -> BigInt("00312023", 16)  // sw   x3,0(x2)
      )
      val pageTable = Map(
        RootPteAddress -> pte(Level0Ppn, read = false, execute = false, accessed = false),
        LeafPteAddress -> pte(LeafPpn, read = true, execute = true, accessed = true),
        DataLeafPteAddress -> writableDataPte(BigInt(4))
      )
      val seenImem = mutable.ArrayBuffer.empty[BigInt]
      val seenPtw = mutable.ArrayBuffer.empty[BigInt]
      val seenData = mutable.ArrayBuffer.empty[BigInt]

      var cycles = 0
      var faultCommit = false
      while (cycles < 900 && !faultCommit) {
        driveExternal(dut, instructions, pageTable, seenImem, seenPtw)

        if (dut.io.memoryRequest.valid.peek().litToBoolean) {
          seenData += dut.io.memoryRequest.bits.paddr.peek().litValue
        }

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == DataVa + 8 &&
            dut.io.commit.exception.peek().litToBoolean) {
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.StoreAccessFault.U)
          dut.io.commit.exceptionValue.expect(DataVa.U)
          dut.io.commit.rdWrite.expect(false.B)
          dut.io.currentPrivilege.expect(PrivilegeMode.Supervisor.U)
          faultCommit = true
        }

        dut.clock.step()
        cycles += 1
      }

      withClue("unified data-PMP denial did not retire as precise StoreAccessFault: ") {
        faultCommit shouldBe true
      }
      withClue("PMP-denied Store escaped onto the external data-memory port: ") {
        seenData shouldBe empty
      }
      seenPtw should contain (RootPteAddress)
      seenPtw should contain (LeafPteAddress)
      seenPtw should contain (DataLeafPteAddress)
    }
  }

  it should "consume a PTW PMP denial locally and retire instruction access fault without external PTE traffic" in {
    simulate(new TinyPagedCore(Config, Geometry)) { dut =>
      initialize(dut)

      val instructions = machineProgram(enablePmp = false) +
        (SupervisorPa -> BigInt("00900293", 16))
      val pageTable = Map(
        RootPteAddress -> pte(Level0Ppn, read = false, execute = false, accessed = false),
        LeafPteAddress -> pte(LeafPpn, read = true, execute = true, accessed = true)
      )
      val seenImem = mutable.ArrayBuffer.empty[BigInt]
      val seenPtw = mutable.ArrayBuffer.empty[BigInt]

      var cycles = 0
      var faultCommit = false
      while (cycles < 700 && !faultCommit) {
        driveExternal(dut, instructions, pageTable, seenImem, seenPtw)

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.pc.peek().litValue == SupervisorVa &&
            dut.io.commit.exception.peek().litToBoolean) {
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionAccessFault.U)
          dut.io.commit.exceptionValue.expect(SupervisorVa.U)
          dut.io.commit.rdWrite.expect(false.B)
          faultCommit = true
        }

        dut.clock.step()
        cycles += 1
      }

      withClue("PTW PMP denial did not become precise instruction access fault: ") {
        faultCommit shouldBe true
      }
      withClue("PMP-denied implicit PTE read escaped to the external PTW port: ") {
        seenPtw shouldBe empty
      }
      withClue("PMP-denied translation emitted a physical instruction request: ") {
        seenImem should not contain (SupervisorPa)
      }
    }
  }
}
