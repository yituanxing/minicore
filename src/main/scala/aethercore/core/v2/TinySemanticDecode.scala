package aethercore.core.v2

import chisel3._
import aethercore.common.TrapInfo
import aethercore.config.IsaConfig

/**
  * Compatibility composition wrapper for the frozen v2 decode boundary.
  *
  * Architectural meaning is produced by TinyArchitecturalSemanticDecode.
  * Backend execution classification is owned separately by the deliberately
  * encoding-blind TinyBackendClassifier. Existing callers keep the qualified
  * RobDispatch interface while neither layer can absorb the other's policy.
  */
class TinySemanticDecode(val isa: IsaConfig) extends Module {
  private val xlen = isa.xlen

  val io = IO(new Bundle {
    val pc = Input(UInt(xlen.W))
    val inst = Input(UInt(32.W))
    val rawInst = Input(UInt(32.W))
    val instBytes = Input(UInt(3.W))
    val fetchException = Input(new TrapInfo(xlen))

    val dispatch = Output(new RobDispatch(xlen))
  })

  private val architecturalDecode = Module(new TinyArchitecturalSemanticDecode(isa))
  architecturalDecode.io.pc := io.pc
  architecturalDecode.io.inst := io.inst
  architecturalDecode.io.rawInst := io.rawInst
  architecturalDecode.io.instBytes := io.instBytes
  architecturalDecode.io.fetchException := io.fetchException

  private val decoded = architecturalDecode.io.decoded
  private val backendClassifier = Module(new TinyBackendClassifier)
  backendClassifier.io.aluOp := decoded.aluOp
  backendClassifier.io.systemKind := decoded.system.kind
  backendClassifier.io.memoryKind := decoded.memory.kind
  backendClassifier.io.controlFlowKind := decoded.controlFlow.kind
  backendClassifier.io.writesRd := decoded.writesRd
  backendClassifier.io.rd := decoded.rd
  backendClassifier.io.exceptionValid := decoded.exception.valid

  io.dispatch := 0.U.asTypeOf(new RobDispatch(xlen))
  io.dispatch.decoded := decoded
  io.dispatch.executionClass := backendClassifier.io.executionClass
  io.dispatch.producesValue := backendClassifier.io.producesValue
}
