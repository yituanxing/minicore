package aethercore

import aethercore.core.product.ProductPhysicalValueStore
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Check physical value lifetime independently from ROB, rename and the FUs.
  * Both integer and FP PRFs share this storage owner, with different ports
  * and different physical-register-zero semantics.
  */
class ProductPhysicalValueStoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product physical value storage"

  private def idle(d: ProductPhysicalValueStore): Unit = {
    for (r <- 0 until d.readPorts) d.io.readAddress(r).poke(0.U)
    d.io.invalidate.valid.poke(false.B)
    d.io.invalidate.bits.poke(0.U)
    d.io.write.valid.poke(false.B)
    d.io.write.bits.index.poke(0.U)
    d.io.write.bits.value.poke(0.U)
  }

  it should "serve two integer operands without resetting RAM contents" in {
    simulate(new ProductPhysicalValueStore(entries = 48, readPorts = 2,
      immutableZero = true)) { d =>
      idle(d)
      d.io.readAddress(0).poke(0.U)
      d.io.readAddress(1).poke(31.U)
      d.io.readValue(0).expect(0.U)
      d.io.readValue(1).expect(0.U)

      d.io.write.valid.poke(true.B)
      d.io.write.bits.index.poke(32.U)
      d.io.write.bits.value.poke("h123456789abcdef0".U)
      d.clock.step()
      d.io.write.valid.poke(false.B)
      d.io.readAddress(0).poke(32.U)
      d.io.readAddress(1).poke(0.U)
      d.io.readValue(0).expect("h123456789abcdef0".U)
      d.io.readValue(1).expect(0.U)

      // Reallocate the same physical tag: old RAM bits are not a valid value.
      d.io.invalidate.valid.poke(true.B)
      d.io.invalidate.bits.poke(32.U)
      d.clock.step()
      d.io.invalidate.valid.poke(false.B)
      d.io.readValue(0).expect(0.U)

      d.io.write.valid.poke(true.B)
      d.io.write.bits.index.poke(32.U)
      d.io.write.bits.value.poke("hfedcba9876543210".U)
      d.clock.step()
      d.io.write.valid.poke(false.B)
      d.io.readValue(0).expect("hfedcba9876543210".U)
      d.io.readValue(1).expect(0.U)
    }
  }

  it should "read all three FP operands and permit writable f0" in {
    simulate(new ProductPhysicalValueStore(entries = 48, readPorts = 3)) { d =>
      idle(d)
      d.io.readAddress(0).poke(0.U)
      d.io.readAddress(1).poke(1.U)
      d.io.readAddress(2).poke(47.U)
      for (r <- 0 until 3) d.io.readValue(r).expect(0.U)

      d.io.write.valid.poke(true.B)
      d.io.write.bits.index.poke(0.U)
      d.io.write.bits.value.poke("h3ff0000000000000".U)
      d.clock.step()
      d.io.write.valid.poke(false.B)
      d.io.readValue(0).expect("h3ff0000000000000".U)

      // An in-flight older FP completion may arrive during younger rename.
      // These different physical lifetimes must not interfere.
      d.io.invalidate.valid.poke(true.B)
      d.io.invalidate.bits.poke(47.U)
      d.io.write.valid.poke(true.B)
      d.io.write.bits.index.poke(1.U)
      d.io.write.bits.value.poke("h4000000000000000".U)
      d.clock.step()
      d.io.invalidate.valid.poke(false.B)
      d.io.write.valid.poke(false.B)
      d.io.readValue(0).expect("h3ff0000000000000".U)
      d.io.readValue(1).expect("h4000000000000000".U)
      d.io.readValue(2).expect(0.U)
    }
  }

  it should "support expanded 64-entry physical geometry" in {
    simulate(new ProductPhysicalValueStore(entries = 64, readPorts = 3)) { d =>
      idle(d)
      d.io.write.valid.poke(true.B)
      d.io.write.bits.index.poke(63.U)
      d.io.write.bits.value.poke("hffffffffffffffff".U)
      d.clock.step()
      d.io.write.valid.poke(false.B)
      for (r <- 0 until 3) {
        d.io.readAddress(r).poke(63.U)
        d.io.readValue(r).expect("hffffffffffffffff".U)
      }
    }
  }
}
