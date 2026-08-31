package aethercore.core

import aethercore.common.MemSize
import aethercore.config.PageTableGeometry
import chisel3._
import chisel3.util._

/**
  * Request seam used when multiple data lifetimes share one TranslationUnit.
  * It intentionally carries only translation context; physical-memory lifetime
  * and completion ownership remain inside the requesting LSU.
  */
class DataTranslationRequest(val geometry: PageTableGeometry) extends Bundle {
  val virtualAddress = UInt(geometry.xlen.W)
  val privilege = UInt(2.W)
  val write = Bool()
  val satpTranslationEnabled = Bool()
  val satpRootPpn = UInt(geometry.ppnBits.W)
  val sum = Bool()
  val mxr = Bool()
}

/** Result returned by a shared TranslationUnit to the owning data lifetime. */
class DataTranslationResponse(val geometry: PageTableGeometry) extends Bundle {
  val physicalAddress = UInt(geometry.architecturalPhysicalAddressBits.W)
  val pageFault = Bool()
  val accessFault = Bool()
}

/**
  * Serial correctness-first data-side adapter shared by one paged VM geometry.
  *
  * Translation permission intent is deliberately separate from physical bus
  * direction: an AMO read phase may require store permission while still
  * issuing a physical read before the write-back phase.
  *
  * externalTranslation=false preserves the historical private TranslationUnit.
  * externalTranslation=true removes that unit and exposes a narrow Decoupled
  * request/response seam so several LSUs can share exactly one TLB/PTW owner.
  */
class DataPathAdapter(
    val geometry: PageTableGeometry,
    val paddrBits: Int = -1,
    val tlbEntries: Int = 8,
    val externalTranslation: Boolean = false
) extends Module {
  private val Xlen = geometry.xlen
  private val ArchitecturalPhysicalBits = geometry.architecturalPhysicalAddressBits
  private val PhysicalBits =
    if (paddrBits > 0) paddrBits else ArchitecturalPhysicalBits
  private val BusBytes = Xlen / 8

  require(PhysicalBits > 0, s"implemented data PA width must be positive, got $PhysicalBits")

  val io = IO(new Bundle {
    val requestValid = Input(Bool())
    val flush = Input(Bool())
    val virtualAddress = Input(UInt(Xlen.W))
    val privilege = Input(UInt(2.W))
    val translateWrite = Input(Bool())
    val write = Input(Bool())
    val wdata = Input(UInt(Xlen.W))
    val wmask = Input(UInt(BusBytes.W))
    val size = Input(MemSize())

    val satpTranslationEnabled = Input(Bool())
    val satpRootPpn = Input(UInt(geometry.ppnBits.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())

    val pteValid = Output(Bool())
    val pteAddress = Output(UInt(PhysicalBits.W))
    val pteReady = Input(Bool())
    val pteData = Input(UInt(geometry.pteBits.W))
    val pteFault = Input(Bool())

    val translationRequest =
      if (externalTranslation) Some(Decoupled(new DataTranslationRequest(geometry))) else None
    val translationResponse =
      if (externalTranslation) Some(Flipped(Decoupled(new DataTranslationResponse(geometry)))) else None

    val dataValid = Output(Bool())
    val dataWrite = Output(Bool())
    val dataAddress = Output(UInt(PhysicalBits.W))
    val dataWdata = Output(UInt(Xlen.W))
    val dataWmask = Output(UInt(BusBytes.W))
    val dataSize = Output(MemSize())
    val dataReady = Input(Bool())
    val dataRdata = Input(UInt(Xlen.W))
    val dataFault = Input(Bool())

    val requestComplete = Output(Bool())
    val physicalAddress = Output(UInt(PhysicalBits.W))
    val readData = Output(UInt(Xlen.W))
    val pageFault = Output(Bool())
    val accessFault = Output(Bool())
  })

  val translationResponseValid = Wire(Bool())
  val translationPhysicalAddress = Wire(UInt(ArchitecturalPhysicalBits.W))
  val translationPageFault = Wire(Bool())
  val translationAccessFault = Wire(Bool())

  if (externalTranslation) {
    val request = io.translationRequest.get
    val response = io.translationResponse.get

    // A tiny per-lifetime translated-PA hold is deliberately retained outside
    // the shared TLB/PTW.  The shared TranslationUnit may therefore accept the
    // second Load as soon as the first translation resolves, without waiting
    // for that first physical read response.
    val heldValid = RegInit(false.B)
    val heldPhysicalAddress = Reg(UInt(ArchitecturalPhysicalBits.W))
    val heldPageFault = RegInit(false.B)
    val heldAccessFault = RegInit(false.B)

    request.valid := io.requestValid && !heldValid
    request.bits.virtualAddress := io.virtualAddress
    request.bits.privilege := io.privilege
    request.bits.write := io.translateWrite
    request.bits.satpTranslationEnabled := io.satpTranslationEnabled
    request.bits.satpRootPpn := io.satpRootPpn
    request.bits.sum := io.sum
    request.bits.mxr := io.mxr

    translationResponseValid := heldValid || response.valid
    translationPhysicalAddress := Mux(
      heldValid, heldPhysicalAddress, response.bits.physicalAddress
    )
    translationPageFault := Mux(
      heldValid, heldPageFault, response.bits.pageFault
    )
    translationAccessFault := Mux(
      heldValid, heldAccessFault, response.bits.accessFault
    )

    // Consume the shared translation result immediately.  If the physical/fault
    // path also completes on this cycle there is no reason to retain it.
    response.ready := !heldValid
    when(io.requestComplete) {
      heldValid := false.B
    }.elsewhen(response.fire) {
      heldValid := true.B
      heldPhysicalAddress := response.bits.physicalAddress
      heldPageFault := response.bits.pageFault
      heldAccessFault := response.bits.accessFault
    }
    when(io.flush) {
      heldValid := false.B
    }

    io.pteValid := false.B
    io.pteAddress := 0.U
  } else {
    val translation = Module(new TranslationUnit(
      geometry,
      tlbEntries,
      implementedPaddrBits = PhysicalBits
    ))
    translation.io.requestValid := io.requestValid
    translation.io.kill := false.B
    translation.io.flush := io.flush
    translation.io.virtualAddress := io.virtualAddress
    translation.io.privilege := io.privilege
    translation.io.write := io.translateWrite
    translation.io.execute := false.B
    translation.io.satpTranslationEnabled := io.satpTranslationEnabled
    translation.io.satpRootPpn := io.satpRootPpn
    translation.io.sum := io.sum
    translation.io.mxr := io.mxr

    translation.io.pteData := io.pteData
    if (PhysicalBits >= ArchitecturalPhysicalBits) {
      translation.io.pteReady := io.pteReady
      translation.io.pteFault := io.pteFault
      io.pteValid := translation.io.pteValid
      io.pteAddress := translation.io.pteAddress.pad(PhysicalBits)
    } else {
      val (narrowPteAddress, pteOutOfRange) =
        PhysicalAddressNarrowing(translation.io.pteAddress, PhysicalBits)
      val pteRangeFault = translation.io.pteValid && pteOutOfRange

      translation.io.pteReady := Mux(pteRangeFault, true.B, io.pteReady)
      translation.io.pteFault := pteRangeFault || (io.pteValid && io.pteFault)
      io.pteValid := translation.io.pteValid && !pteOutOfRange
      io.pteAddress := narrowPteAddress
    }

    translationResponseValid := translation.io.responseValid
    translationPhysicalAddress := translation.io.physicalAddress
    translationPageFault := translation.io.pageFault
    translationAccessFault := translation.io.accessFault

    translation.io.responseReady := io.requestComplete
  }

  // Keep the qualified architectural-width path structurally identical.  Only
  // a genuinely narrower implemented PA domain instantiates the range check.
  private val (implementedPhysicalAddress, translationRangeFault) =
    if (PhysicalBits >= ArchitecturalPhysicalBits) {
      (translationPhysicalAddress.pad(PhysicalBits), false.B)
    } else {
      val (narrowPhysicalAddress, translationOutOfRange) =
        PhysicalAddressNarrowing(translationPhysicalAddress, PhysicalBits)
      val rangeFault =
        translationResponseValid && !translationPageFault &&
          !translationAccessFault && translationOutOfRange
      (narrowPhysicalAddress, rangeFault)
    }

  val translationFault =
    translationPageFault || translationAccessFault || translationRangeFault
  io.dataValid := io.requestValid && translationResponseValid && !translationFault
  io.dataWrite := io.write
  io.dataAddress := implementedPhysicalAddress
  io.dataWdata := io.wdata
  io.dataWmask := io.wmask
  io.dataSize := io.size

  val physicalComplete = io.dataValid && io.dataReady
  val faultComplete = io.requestValid && translationResponseValid && translationFault
  io.requestComplete := physicalComplete || faultComplete

  io.physicalAddress := implementedPhysicalAddress
  io.readData := io.dataRdata
  io.pageFault := faultComplete && translationPageFault
  io.accessFault :=
    (faultComplete && (translationAccessFault || translationRangeFault)) ||
      (physicalComplete && io.dataFault)
}
