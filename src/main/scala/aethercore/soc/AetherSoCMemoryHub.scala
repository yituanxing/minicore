package aethercore.soc

import chisel3._
import chisel3.util._
import aethercore.memory.{AetherMemRequest, AetherMemResponse}

/**
  * One semantic AetherMem client as seen from the SoC memory hub.
  *
  * Clients originate requests and consume responses. The hub prefixes each
  * client transaction id with a source tag on the downstream link, allowing
  * multiple clients and multiple transactions to remain outstanding without
  * introducing a centralized outstanding table.
  */
class AetherMemClientPort(
    val addrBits: Int,
    val dataBits: Int,
    val txnIdBits: Int
) extends Bundle {
  val request = Flipped(Decoupled(new AetherMemRequest(addrBits, dataBits, txnIdBits)))
  val response = Decoupled(new AetherMemResponse(dataBits, txnIdBits))
}

/**
  * Fair multi-client AetherMem fabric for the synthesizable SoC boundary.
  *
  * This module deliberately knows nothing about AXI/TileLink, PMA decoding,
  * caches, PTW policy, or CPU ordering. It only arbitrates request transport
  * and prefixes transaction identity with a client-source tag. A later
  * AetherMem-to-AXI adapter can therefore live strictly below this hub.
  *
  * The first intended clients are:
  *   0: D-cache / ordinary data-memory traffic
  *   1: PTW read traffic
  *   2: instruction-fetch / I-cache traffic
  */
class AetherSoCMemoryHub(
    val addrBits: Int,
    val dataBits: Int,
    val clientTxnIdBits: Int = 2,
    val clientCount: Int = 3
) extends Module {
  require(addrBits > 0)
  require(dataBits > 0 && dataBits % 8 == 0)
  require(clientTxnIdBits > 0)
  require(clientCount >= 2)

  private val sourceBits = log2Ceil(clientCount)
  val downstreamTxnIdBits: Int = clientTxnIdBits + sourceBits

  val io = IO(new Bundle {
    val clients = Vec(clientCount, new AetherMemClientPort(addrBits, dataBits, clientTxnIdBits))
    val downstreamRequest = Decoupled(
      new AetherMemRequest(addrBits, dataBits, downstreamTxnIdBits)
    )
    val downstreamResponse = Flipped(
      Decoupled(new AetherMemResponse(dataBits, downstreamTxnIdBits))
    )
  })

  private val requestArbiter = Module(
    new RRArbiter(
      new AetherMemRequest(addrBits, dataBits, clientTxnIdBits),
      clientCount
    )
  )

  for (client <- 0 until clientCount) {
    requestArbiter.io.in(client) <> io.clients(client).request
  }

  // RR arbitration chooses among *currently* valid clients. A new client can
  // become eligible while the physical memory boundary is stalled, changing
  // the combinational chosen/source tag and request bits under a held VALID.
  // One flow-through entry accepts and pins the COMPLETE tagged transaction
  // on the first stalled cycle. With ready high and no queued request, normal
  // requests still pass straight through with no added request-cycle latency.
  private val requestSkid = Module(new Queue(
    new AetherMemRequest(addrBits, dataBits, downstreamTxnIdBits),
    entries = 1,
    pipe = true,
    flow = true
  ))
  requestSkid.io.enq.valid := requestArbiter.io.out.valid
  requestArbiter.io.out.ready := requestSkid.io.enq.ready
  io.downstreamRequest <> requestSkid.io.deq

  requestSkid.io.enq.bits.txnId :=
    Cat(requestArbiter.io.chosen, requestArbiter.io.out.bits.txnId)
  requestSkid.io.enq.bits.op := requestArbiter.io.out.bits.op
  requestSkid.io.enq.bits.paddr := requestArbiter.io.out.bits.paddr
  requestSkid.io.enq.bits.size := requestArbiter.io.out.bits.size
  requestSkid.io.enq.bits.wdata := requestArbiter.io.out.bits.wdata
  requestSkid.io.enq.bits.wmask := requestArbiter.io.out.bits.wmask
  requestSkid.io.enq.bits.atomicOp := requestArbiter.io.out.bits.atomicOp
  requestSkid.io.enq.bits.attributes.cacheable :=
    requestArbiter.io.out.bits.attributes.cacheable
  requestSkid.io.enq.bits.attributes.idempotent :=
    requestArbiter.io.out.bits.attributes.idempotent
  requestSkid.io.enq.bits.attributes.sideEffecting :=
    requestArbiter.io.out.bits.attributes.sideEffecting
  requestSkid.io.enq.bits.attributes.ordered :=
    requestArbiter.io.out.bits.attributes.ordered
  requestSkid.io.enq.bits.attributes.executable :=
    requestArbiter.io.out.bits.attributes.executable
  requestSkid.io.enq.bits.attributes.supportsAtomic :=
    requestArbiter.io.out.bits.attributes.supportsAtomic
  requestSkid.io.enq.bits.attributes.supportsPartial :=
    requestArbiter.io.out.bits.attributes.supportsPartial

  private val responseSource =
    io.downstreamResponse.bits.txnId(downstreamTxnIdBits - 1, clientTxnIdBits)
  private val responseLocalTxnId =
    io.downstreamResponse.bits.txnId(clientTxnIdBits - 1, 0)

  for (client <- 0 until clientCount) {
    val selected = responseSource === client.U
    io.clients(client).response.valid := io.downstreamResponse.valid && selected
    io.clients(client).response.bits.txnId := responseLocalTxnId
    io.clients(client).response.bits.rdata := io.downstreamResponse.bits.rdata
    io.clients(client).response.bits.fault := io.downstreamResponse.bits.fault
    io.clients(client).response.bits.last := io.downstreamResponse.bits.last
  }

  io.downstreamResponse.ready := MuxCase(
    false.B,
    (0 until clientCount).map { client =>
      (responseSource === client.U) -> io.clients(client).response.ready
    }
  )

  when(io.downstreamResponse.valid) {
    assert(responseSource < clientCount.U,
      "SoC memory hub received a response with an invalid source tag")
  }
}
