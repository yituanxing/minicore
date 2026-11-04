package aethercore

import aethercore.common.CommitTrace
import aethercore.core.product._
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Test exact architectural FP trace ownership without claiming the yet-to-be
  * qualified F/D paged CPU or a completed IEEE arithmetic pipeline.
  */
class ProductFloatingTraceHarness(val withFloating: Boolean) extends Module {
  val io = IO(new Bundle {
    val retirement = Input(new ProductRetirement(64, 3, 6, 16, 56))
    val retiring = Input(Bool())
    val fpCsrWrite = Input(Bool())
    val commit = Output(new CommitTrace(64, 56, 64, withFloating))
  })
  io.commit := 0.U.asTypeOf(io.commit)
  io.commit.valid := io.retiring
  if (withFloating) {
    ProductFloatingRetirementTrace.connect(io.commit.floating.get,
      io.retirement, io.retiring, io.fpCsrWrite)
  }
}

class ProductFloatingCommitTraceSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product conditional FP architectural commit trace"

  private def init(d: ProductFloatingTraceHarness): Unit = {
    d.io.retiring.poke(false.B)
    d.io.fpCsrWrite.poke(false.B)
    d.io.retirement.poke(0.U.asTypeOf(d.io.retirement))
    d.clock.step()
  }

  it should "preserve the exact legacy no-F bundle geometry" in {
    simulate(new ProductFloatingTraceHarness(withFloating = false)) { d =>
      init(d)
      d.io.commit.floating.isEmpty shouldBe true
      // Preserve v0/v2 and no-F FPGA trace width with no floating pins.
      d.io.commit.getWidth shouldBe 590
      d.io.retiring.poke(true.B)
      d.io.retirement.wroteFloatingRegister.poke(true.B)
      d.io.retirement.fflags.poke(0x10.U)
      d.io.commit.valid.expect(true.B)
      d.io.commit.floating.isEmpty shouldBe true
    }
  }

  it should "report writable f0, raw FPR bits and per-instruction fflags" in {
    simulate(new ProductFloatingTraceHarness(withFloating = true)) { d =>
      init(d)
      d.io.commit.floating.nonEmpty shouldBe true
      d.io.commit.getWidth shouldBe 666
      val fp = d.io.commit.floating.get
      d.io.retirement.rd.poke(0.U)
      d.io.retirement.value.poke("hffffffff7fc00000".U)
      d.io.retirement.wroteFloatingRegister.poke(true.B)
      d.io.retirement.fflags.poke(0x10.U)
      d.io.retirement.fpDirty.poke(true.B)
      d.io.retiring.poke(true.B)
      fp.rdWrite.expect(true.B)
      fp.rd.expect(0.U)
      fp.rdData.expect("hffffffff7fc00000".U)
      fp.fflags.expect(0x10.U)
      fp.stateDirty.expect(true.B)
    }
  }

  it should "retain flags for FP to integer results without claiming an FPR write" in {
    simulate(new ProductFloatingTraceHarness(withFloating = true)) { d =>
      init(d)
      val fp = d.io.commit.floating.get
      d.io.retirement.rd.poke(7.U)
      d.io.retirement.value.poke(1.U)
      d.io.retirement.wroteRegister.poke(true.B)
      d.io.retirement.wroteFloatingRegister.poke(false.B)
      d.io.retirement.fflags.poke(0x10.U)
      d.io.retirement.fpDirty.poke(true.B)
      d.io.retiring.poke(true.B)
      fp.rdWrite.expect(false.B)
      fp.rd.expect(0.U)
      fp.rdData.expect(0.U)
      fp.fflags.expect(0x10.U)
      fp.stateDirty.expect(true.B)
    }
  }

  it should "trace legal fflags frm fcsr CSR writes as dirty without an FP destination" in {
    simulate(new ProductFloatingTraceHarness(withFloating = true)) { d =>
      init(d)
      val fp = d.io.commit.floating.get
      d.io.retiring.poke(true.B)
      d.io.retirement.wroteRegister.poke(true.B)
      d.io.retirement.rd.poke(5.U)
      d.io.fpCsrWrite.poke(true.B)
      fp.rdWrite.expect(false.B)
      fp.fflags.expect(0.U)
      fp.stateDirty.expect(true.B)
    }
  }

  it should "drop speculative and faulting FP trace side effects" in {
    simulate(new ProductFloatingTraceHarness(withFloating = true)) { d =>
      init(d)
      val fp = d.io.commit.floating.get
      d.io.retirement.rd.poke(3.U)
      d.io.retirement.value.poke("hdeadbeef".U)
      d.io.retirement.wroteFloatingRegister.poke(true.B)
      d.io.retirement.fflags.poke(0x1f.U)
      d.io.retirement.fpDirty.poke(true.B)

      // A completed younger FU has no permission to publish a commit trace.
      d.io.retiring.poke(false.B)
      fp.rdWrite.expect(false.B)
      fp.fflags.expect(0.U)
      fp.stateDirty.expect(false.B)

      // Even a retire valid fault must suppress all FP architectural effects.
      d.io.retiring.poke(true.B)
      d.io.retirement.exception.valid.poke(true.B)
      fp.rdWrite.expect(false.B)
      fp.rd.expect(0.U)
      fp.rdData.expect(0.U)
      fp.fflags.expect(0.U)
      fp.stateDirty.expect(false.B)
    }
  }
}
