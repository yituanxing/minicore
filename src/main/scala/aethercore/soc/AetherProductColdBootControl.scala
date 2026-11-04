package aethercore.soc

import chisel3._
import chisel3.util._

/** Board-clock-domain handoff between an external DDR image loader and Product.
  *
  * The board must synchronize clockLocked, ddrCalibrated, imageLoaded,
  * loaderIdle and imageLoadFailed to this clock. imageLoaded must assert only
  * after the OpenSBI payload has been written into DDR, and loaderIdle must
  * assert only after *all* loader AXI responses have completed. Neither is
  * fabricated by this controller: this is not a DDR PHY or an image loader.
  *
  * Ownership sequence:
  *   clock lock -> DDR calibration -> new imageLoaded edge + loaderIdle
  *     -> one isolated AXI turnaround cycle -> CPU reset release.
  *
  * A fault after CPU release holds both owners inactive and resets the CPU.
  * Automatic DDR ownership recovery would be unsafe with in-flight CPU AXI
  * transactions; a new full-board reset is required before loading again.
  */
class AetherProductColdBootControl extends Module {
  val io = IO(new Bundle {
    val clockLocked = Input(Bool())
    val ddrCalibrated = Input(Bool())
    val imageLoaded = Input(Bool())
    val loaderIdle = Input(Bool())
    val imageLoadFailed = Input(Bool())

    val loaderOwnsDdr = Output(Bool())
    val cpuOwnsDdr = Output(Bool())
    val cpuReleased = Output(Bool())
    val bootFault = Output(Bool())
  })

  private val states = Enum(7)
  private val sWaitClock = states(0)
  private val sWaitDdr = states(1)
  private val sWaitImageLow = states(2)
  private val sWaitImage = states(3)
  private val sHandoff = states(4)
  private val sCpu = states(5)
  private val sFault = states(6)
  private val state = RegInit(sWaitClock)
  private val memoryReady = !reset.asBool && io.clockLocked && io.ddrCalibrated

  switch(state) {
    is(sWaitClock) {
      when(io.clockLocked) { state := sWaitDdr }
    }
    is(sWaitDdr) {
      when(!io.clockLocked) { state := sWaitClock }
        .elsewhen(io.ddrCalibrated) { state := sWaitImageLow }
    }
    is(sWaitImageLow) {
      when(!io.clockLocked) { state := sWaitClock }
        .elsewhen(!io.ddrCalibrated) { state := sWaitDdr }
        // Do not accept stale 'loaded' from a previous power/reset cycle.
        .elsewhen(!io.imageLoaded) { state := sWaitImage }
    }
    is(sWaitImage) {
      when(!io.clockLocked) { state := sWaitClock }
        .elsewhen(!io.ddrCalibrated) { state := sWaitDdr }
        .elsewhen(io.imageLoaded && io.loaderIdle) { state := sHandoff }
    }
    is(sHandoff) {
      // One dead cycle ensures no CPU transaction overlaps loader ownership.
      when(!memoryReady || !io.imageLoaded || !io.loaderIdle) {
        state := sFault
      }.otherwise {
        state := sCpu
      }
    }
    is(sCpu) {
      // Reset and isolate immediately on readiness loss. Do not ever regrant
      // memory to the loader without a fresh reset/drain of CPU transactions.
      when(!memoryReady) { state := sFault }
    }
    is(sFault) {
      // Sticky until the system-level reset is asserted.
    }
  }
  when(io.imageLoadFailed) { state := sFault }

  io.loaderOwnsDdr := (state === sWaitImageLow || state === sWaitImage) &&
    memoryReady && !io.imageLoadFailed
  io.cpuReleased := state === sCpu && memoryReady && !io.imageLoadFailed
  io.cpuOwnsDdr := io.cpuReleased
  io.bootFault := state === sFault || io.imageLoadFailed

  assert(!(io.loaderOwnsDdr && io.cpuOwnsDdr),
    "Cold boot cannot grant external DDR to loader and CPU together")
}

/** Five-channel isolation actually used between the Product master and DDR.
  *
  * This small module is also independently cycle-simulated, so gating of AW,
  * W, AR, B and R is verified without needing a lengthy Linux CPU simulation.
  */
class AetherProductAxiColdBootIsolation(
    val addrBits: Int,
    val dataBits: Int,
    val txnIdBits: Int
) extends Module {
  val io = IO(new Bundle {
    val released = Input(Bool())
    val cpu = Flipped(new Axi4MasterIO(addrBits, dataBits, txnIdBits))
    val ddr = new Axi4MasterIO(addrBits, dataBits, txnIdBits)
  })

  io.ddr.aw.valid := io.released && io.cpu.aw.valid
  io.ddr.aw.bits := io.cpu.aw.bits
  io.cpu.aw.ready := io.released && io.ddr.aw.ready
  io.ddr.w.valid := io.released && io.cpu.w.valid
  io.ddr.w.bits := io.cpu.w.bits
  io.cpu.w.ready := io.released && io.ddr.w.ready
  io.cpu.b.valid := io.released && io.ddr.b.valid
  io.cpu.b.bits := io.ddr.b.bits
  io.ddr.b.ready := io.released && io.cpu.b.ready
  io.ddr.ar.valid := io.released && io.cpu.ar.valid
  io.ddr.ar.bits := io.cpu.ar.bits
  io.cpu.ar.ready := io.released && io.ddr.ar.ready
  io.cpu.r.valid := io.released && io.ddr.r.valid
  io.cpu.r.bits := io.ddr.r.bits
  io.ddr.r.ready := io.released && io.cpu.r.ready

  when(!io.released) {
    assert(!io.ddr.aw.valid && !io.ddr.w.valid && !io.ddr.ar.valid,
      "CPU AXI request must not reach DDR before image handoff")
    assert(!io.ddr.b.ready && !io.ddr.r.ready,
      "DDR AXI response must not enter CPU before image handoff")
  }
}
