package aethercore.core.isa

import chisel3._
import chisel3.util._
import aethercore.common._

/** Decode ownership for FENCE.I, privileged SYSTEM encodings and Zicsr forms.
  *
  * Base FENCE remains in BaseIDecode. This object owns only extension- or
  * privilege-scoped semantics so the top-level Decoder can compose them
  * without embedding policy for each architectural feature.
  */
object SystemDecode {
  def decodeFenceI(c: ControlSignals, funct3: UInt, hasZifencei: Boolean): Unit = {
    when(funct3 === 1.U && hasZifencei.B) {
      c.illegal := false.B
    }
  }

  def decodeSystem(
      c: ControlSignals,
      inst: UInt,
      funct3: UInt,
      hasZicsr: Boolean,
      hasSupervisor: Boolean
  ): Unit = {
    switch(funct3) {
      is("b000".U) {
        when(inst === "h00000073".U || inst === "h00100073".U) {
          c.illegal := false.B
          c.trap := true.B
        }.elsewhen(inst === "h10500073".U && hasZicsr.B) {
          c.illegal := false.B
          c.wfi := true.B
        }.elsewhen(inst === "h30200073".U && hasZicsr.B) {
          c.illegal := false.B
          c.xret := XRetOp.Machine
        }.elsewhen(inst === "h10200073".U && hasZicsr.B && hasSupervisor.B) {
          c.illegal := false.B
          c.xret := XRetOp.Supervisor
        }
      }
      is("b001".U) {
        when(hasZicsr.B) {
          c.illegal := false.B
          c.regWrite := true.B
          c.wbSel := WbSel.Csr
          c.csrOp := CsrOp.Write
          c.usesRs1 := true.B
        }
      }
      is("b010".U) {
        when(hasZicsr.B) {
          c.illegal := false.B
          c.regWrite := true.B
          c.wbSel := WbSel.Csr
          c.csrOp := CsrOp.Set
          c.usesRs1 := true.B
        }
      }
      is("b011".U) {
        when(hasZicsr.B) {
          c.illegal := false.B
          c.regWrite := true.B
          c.wbSel := WbSel.Csr
          c.csrOp := CsrOp.Clear
          c.usesRs1 := true.B
        }
      }
      is("b101".U) {
        when(hasZicsr.B) {
          c.illegal := false.B
          c.regWrite := true.B
          c.wbSel := WbSel.Csr
          c.csrOp := CsrOp.Write
          c.csrUseImm := true.B
        }
      }
      is("b110".U) {
        when(hasZicsr.B) {
          c.illegal := false.B
          c.regWrite := true.B
          c.wbSel := WbSel.Csr
          c.csrOp := CsrOp.Set
          c.csrUseImm := true.B
        }
      }
      is("b111".U) {
        when(hasZicsr.B) {
          c.illegal := false.B
          c.regWrite := true.B
          c.wbSel := WbSel.Csr
          c.csrOp := CsrOp.Clear
          c.csrUseImm := true.B
        }
      }
    }
  }
}
