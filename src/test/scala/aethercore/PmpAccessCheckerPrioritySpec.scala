package aethercore

import aethercore.core.PmpAccessChecker
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Random

/** Differential checks for the priority selection network over already-decoded
  * PMP ranges. This deliberately covers overlapping entries independently of
  * the CSR geometry decoder, including high physical addresses and M bypass.
  */
class PmpAccessCheckerPrioritySpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product PMP first-hit priority"

  private case class Entry(
      active: Boolean, lower: BigInt, upper: BigInt,
      read: Boolean, write: Boolean, execute: Boolean, lock: Boolean
  )

  private def exercise(paddrBits: Int, oneHot: Boolean): Unit = {
    val random = new Random(0x504d50L + paddrBits)
    val limit = BigInt(1) << paddrBits
    val highBase = limit - 256
    val anchors = Seq(BigInt(0), BigInt(128), highBase)
    simulate(new PmpAccessChecker(
      entries = 16, paddrBits = paddrBits, oneHotPriorityExperiment = oneHot
    )) { dut =>
      for (trial <- 0 until 300) {
        val base = anchors(trial % anchors.size)
        val entries = Seq.tabulate(16) { index =>
          // Some entries deliberately overlap almost completely; others are
          // OFF. Exercise first-hit selection even if a later match permits.
          val lower = (base + random.nextInt(64)).min(limit)
          val upper = (lower + 1 + random.nextInt(128)).min(limit)
          Entry(
            active = random.nextInt(5) != 0,
            lower = lower,
            upper = upper,
            read = random.nextBoolean(),
            write = random.nextBoolean(),
            execute = random.nextBoolean(),
            lock = random.nextBoolean()
          )
        }
        entries.zipWithIndex.foreach { case (e, index) =>
          val lane = dut.io.ranges(index)
          lane.active.poke(e.active.B)
          lane.lower.poke(e.lower.U)
          lane.upper.poke(e.upper.U)
          lane.read.poke(e.read.B)
          lane.write.poke(e.write.B)
          lane.execute.poke(e.execute.B)
          lane.lock.poke(e.lock.B)
        }

        val address = (base + random.nextInt(256)).min(limit - 1)
        val bytes = Seq(0, 1, 4, 8, 15)(trial % 5)
        val privilege = Seq(0, 1, 3)(trial % 3) // U, S, M
        val isWrite = trial % 7 == 0
        val isExecute = trial % 11 == 0
        dut.io.address.poke(address.U)
        dut.io.bytes.poke(bytes.U)
        dut.io.privilege.poke(privilege.U)
        dut.io.write.poke(isWrite.B)
        dut.io.execute.poke(isExecute.B)

        val end = address + bytes - 1
        val invalid = bytes == 0 || end >= limit
        val first = entries.indexWhere(e =>
          e.active && !invalid && address < e.upper && end >= e.lower
        )
        val expectedAllow = !invalid && (if (first == -1) {
          privilege == 3
        } else {
          val e = entries(first)
          val permission = if (isExecute) e.execute else if (isWrite) e.write else e.read
          address >= e.lower && end < e.upper &&
            ((privilege == 3 && !e.lock) || permission)
        })

        withClue(s"PA$paddrBits oneHot=$oneHot trial=$trial address=$address bytes=$bytes first=$first: ") {
          dut.io.matched.expect((first != -1).B)
          dut.io.matchedEntry.expect(math.max(0, first).U)
          dut.io.allow.expect(expectedAllow.B)
        }
      }
    }
  }

  for (oneHot <- Seq(false, true)) {
    it should s"preserve PMP16 lowest-index priority in PA8 with oneHot=$oneHot" in exercise(8, oneHot)
    it should s"preserve PMP16 lowest-index priority in PA56 with oneHot=$oneHot" in exercise(56, oneHot)
  }
}
