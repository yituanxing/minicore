package aethercore

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import aethercore.core.v2._

/**
  * Measurement-only harnesses for the ROB4 scheduling seam.
  *
  * These modules do not change production behavior. They isolate the LUT cost
  * of moving several-hundred-bit BackendUop payloads through age-order and
  * dependency-index muxes versus keeping physical-slot identity and moving only
  * tiny age metadata.
  */
class TinyRobAgeReorderCost(val xlen: Int = 64) extends Module {
  private val Entries = 4
  private val IndexBits = 2

  val io = IO(new Bundle {
    val head = Input(UInt(IndexBits.W))
    val physical = Input(Vec(Entries, new TinyRobWindowEntry(xlen)))
    val age = Output(Vec(Entries, new TinyRobWindowEntry(xlen)))
  })

  for (age <- 0 until Entries) {
    val slot = (io.head + age.U)(IndexBits - 1, 0)
    io.age(age) := io.physical(slot)
  }
}

class TinyDependencyAgeComposeCost(val xlen: Int = 64) extends Module {
  private val Entries = 4
  private val IdentityBits = 2
  private val GenerationBits = 8

  val io = IO(new Bundle {
    val robAge = Input(Vec(Entries, new TinyRobWindowEntry(xlen)))
    val dependencyPhysical = Input(Vec(
      Entries,
      new TinyDependencySlotView(xlen, IdentityBits, GenerationBits)
    ))
    val scheduling = Output(Vec(Entries, new TinySchedulingEntry(xlen)))
  })

  for (age <- 0 until Entries) {
    val robEntry = io.robAge(age)
    val dependencyEntry =
      io.dependencyPhysical(robEntry.uop.robToken.index)
    val dependencyMatches = robEntry.valid &&
      dependencyEntry.valid &&
      dependencyEntry.robToken.index === robEntry.uop.robToken.index &&
      dependencyEntry.robToken.generation === robEntry.uop.robToken.generation

    io.scheduling(age) := 0.U.asTypeOf(new TinySchedulingEntry(xlen))
    io.scheduling(age).valid := robEntry.valid
    io.scheduling(age).complete := robEntry.complete
    io.scheduling(age).uop := robEntry.uop
    io.scheduling(age).dependenciesValid := dependencyMatches
    when(dependencyMatches) {
      io.scheduling(age).rs1 := dependencyEntry.rs1
      io.scheduling(age).rs2 := dependencyEntry.rs2
    }
    io.scheduling(age).operandsReady := dependencyMatches &&
      dependencyEntry.rs1.ready && dependencyEntry.rs2.ready
  }
}

class TinyCurrentSchedulingSeamCost(val xlen: Int = 64) extends Module {
  private val Entries = 4
  private val IndexBits = 2
  private val IdentityBits = 2
  private val GenerationBits = 8

  val io = IO(new Bundle {
    val head = Input(UInt(IndexBits.W))
    val count = Input(UInt(3.W))
    val robPhysical = Input(Vec(Entries, new TinyRobWindowEntry(xlen)))
    val dependencyPhysical = Input(Vec(
      Entries,
      new TinyDependencySlotView(xlen, IdentityBits, GenerationBits)
    ))
    val scheduling = Output(Vec(Entries, new TinySchedulingEntry(xlen)))
  })

  val ageWindow = Wire(Vec(Entries, new TinyRobWindowEntry(xlen)))
  for (age <- 0 until Entries) {
    val slot = (io.head + age.U)(IndexBits - 1, 0)
    val entry = io.robPhysical(slot)
    val live = age.U < io.count && entry.valid
    ageWindow(age) := 0.U.asTypeOf(new TinyRobWindowEntry(xlen))
    ageWindow(age).valid := live
    ageWindow(age).complete := live && entry.complete
    ageWindow(age).uop := entry.uop
  }

  for (age <- 0 until Entries) {
    val robEntry = ageWindow(age)
    val dependencyEntry =
      io.dependencyPhysical(robEntry.uop.robToken.index)
    val dependencyMatches = robEntry.valid &&
      dependencyEntry.valid &&
      dependencyEntry.robToken.index === robEntry.uop.robToken.index &&
      dependencyEntry.robToken.generation === robEntry.uop.robToken.generation

    io.scheduling(age) := 0.U.asTypeOf(new TinySchedulingEntry(xlen))
    io.scheduling(age).valid := robEntry.valid
    io.scheduling(age).complete := robEntry.complete
    io.scheduling(age).uop := robEntry.uop
    io.scheduling(age).dependenciesValid := dependencyMatches
    when(dependencyMatches) {
      io.scheduling(age).rs1 := dependencyEntry.rs1
      io.scheduling(age).rs2 := dependencyEntry.rs2
    }
    io.scheduling(age).operandsReady := dependencyMatches &&
      dependencyEntry.rs1.ready && dependencyEntry.rs2.ready
  }
}

/** Physical-slot scheduling entry: wide payload stays in its physical slot. */
class TinyPhysicalSchedulingEntryCost(val xlen: Int = 64) extends Bundle {
  private val IdentityBits = 2
  private val GenerationBits = 8

  val live = Bool()
  val complete = Bool()
  val age = UInt(2.W)
  val uop = new BackendUop(xlen, IdentityBits, GenerationBits)
  val dependenciesValid = Bool()
  val rs1 = new OperandState(xlen, IdentityBits, GenerationBits)
  val rs2 = new OperandState(xlen, IdentityBits, GenerationBits)
  val operandsReady = Bool()
}

class TinyPhysicalSchedulingSeamCost(val xlen: Int = 64) extends Module {
  private val Entries = 4
  private val IndexBits = 2
  private val IdentityBits = 2
  private val GenerationBits = 8

  val io = IO(new Bundle {
    val head = Input(UInt(IndexBits.W))
    val count = Input(UInt(3.W))
    val robPhysical = Input(Vec(Entries, new TinyRobWindowEntry(xlen)))
    val dependencyPhysical = Input(Vec(
      Entries,
      new TinyDependencySlotView(xlen, IdentityBits, GenerationBits)
    ))
    val scheduling = Output(Vec(Entries, new TinyPhysicalSchedulingEntryCost(xlen)))
  })

  for (slot <- 0 until Entries) {
    val robEntry = io.robPhysical(slot)
    val dependencyEntry = io.dependencyPhysical(slot)
    val age = (slot.U - io.head)(IndexBits - 1, 0)
    val live = age < io.count && robEntry.valid
    val dependencyMatches = live &&
      dependencyEntry.valid &&
      dependencyEntry.robToken.index === robEntry.uop.robToken.index &&
      dependencyEntry.robToken.generation === robEntry.uop.robToken.generation

    io.scheduling(slot) := 0.U.asTypeOf(new TinyPhysicalSchedulingEntryCost(xlen))
    io.scheduling(slot).live := live
    io.scheduling(slot).complete := live && robEntry.complete
    io.scheduling(slot).age := age
    io.scheduling(slot).uop := robEntry.uop
    io.scheduling(slot).dependenciesValid := dependencyMatches
    when(dependencyMatches) {
      io.scheduling(slot).rs1 := dependencyEntry.rs1
      io.scheduling(slot).rs2 := dependencyEntry.rs2
    }
    io.scheduling(slot).operandsReady := dependencyMatches &&
      dependencyEntry.rs1.ready && dependencyEntry.rs2.ready
  }
}

object ElaborateTinyRobAgeReorderCostRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TinyRobAgeReorderCost(64),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateTinyDependencyAgeComposeCostRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TinyDependencyAgeComposeCost(64),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateTinyCurrentSchedulingSeamCostRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TinyCurrentSchedulingSeamCost(64),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}

object ElaborateTinyPhysicalSchedulingSeamCostRV64 extends App {
  ChiselStage.emitSystemVerilogFile(
    new TinyPhysicalSchedulingSeamCost(64),
    args,
    Array("--lowering-options=disallowLocalVariables,disallowPackedArrays,locationInfoStyle=wrapInAtSquareBracket")
  )
}
