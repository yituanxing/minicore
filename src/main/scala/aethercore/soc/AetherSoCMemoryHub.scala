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
    val clientCount: Int = 3,
    val compactQualifiedTxnIds: Boolean = false
) extends Module {
  require(addrBits > 0)
  require(dataBits > 0 && dataBits % 8 == 0)
  require(clientTxnIdBits > 0)
  require(clientCount >= 2)

  private val sourceBits = log2Ceil(clientCount)
  require(!compactQualifiedTxnIds || (clientCount == 3 && clientTxnIdBits == 2),
    "compact qualified transaction IDs require the 3-client / 2-bit-local product shape")
  val downstreamTxnIdBits: Int =
    if (compactQualifiedTxnIds) 3 else clientTxnIdBits + sourceBits

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

  io.downstreamRequest.valid := requestArbiter.io.out.valid
  requestArbiter.io.out.ready := io.downstreamRequest.ready

  if (compactQualifiedTxnIds) {
    val chosen = requestArbiter.io.chosen
    val localTxn = requestArbiter.io.out.bits.txnId
    when(requestArbiter.io.out.valid) {
      when(chosen === 0.U) {
        assert(localTxn <= 2.U,
          "compact Data client transaction ID must be 0, 1 or 2")
      }.otherwise {
        assert(localTxn === 0.U,
          "compact PTW/I-cache clients must use local transaction ID zero")
      }
    }
    io.downstreamRequest.bits.txnId :=
      MuxLookup(chosen, 0.U(downstreamTxnIdBits.W))(
        Seq(
          0.U -> localTxn,
          1.U -> 3.U(downstreamTxnIdBits.W),
          2.U -> 4.U(downstreamTxnIdBits.W)
        )
      )
  } else {
    io.downstreamRequest.bits.txnId :=
      Cat(requestArbiter.io.chosen, requestArbiter.io.out.bits.txnId)
  }
  io.downstreamRequest.bits.op := requestArbiter.io.out.bits.op
  io.downstreamRequest.bits.paddr := requestArbiter.io.out.bits.paddr
  io.downstreamRequest.bits.size := requestArbiter.io.out.bits.size
  io.downstreamRequest.bits.wdata := requestArbiter.io.out.bits.wdata
  io.downstreamRequest.bits.wmask := requestArbiter.io.out.bits.wmask
  io.downstreamRequest.bits.atomicOp := requestArbiter.io.out.bits.atomicOp
  io.downstreamRequest.bits.attributes.cacheable :=
    requestArbiter.io.out.bits.attributes.cacheable
  io.downstreamRequest.bits.attributes.idempotent :=
    requestArbiter.io.out.bits.attributes.idempotent
  io.downstreamRequest.bits.attributes.sideEffecting :=
    requestArbiter.io.out.bits.attributes.sideEffecting
  io.downstreamRequest.bits.attributes.ordered :=
    requestArbiter.io.out.bits.attributes.ordered
  io.downstreamRequest.bits.attributes.executable :=
    requestArbiter.io.out.bits.attributes.executable
  io.downstreamRequest.bits.attributes.supportsAtomic :=
    requestArbiter.io.out.bits.attributes.supportsAtomic
  io.downstreamRequest.bits.attributes.supportsPartial :=
    requestArbiter.io.out.bits.attributes.supportsPartial

  // Generic source-tag decode remains the default contract.
  private val responseSource =
    if (compactQualifiedTxnIds) 0.U(sourceBits.W)
    else io.downstreamResponse.bits.txnId(downstreamTxnIdBits - 1, clientTxnIdBits)
  private val responseLocalTxnId =
    if (compactQualifiedTxnIds) 0.U(clientTxnIdBits.W)
    else io.downstreamResponse.bits.txnId(clientTxnIdBits - 1, 0)

  if (compactQualifiedTxnIds) {
    val compactId = io.downstreamResponse.bits.txnId
    val compactValid = compactId <= 4.U
    val compactSource = Mux(
      compactId <= 2.U,
      0.U(sourceBits.W),
      Mux(compactId === 3.U, 1.U(sourceBits.W), 2.U(sourceBits.W))
    )
    val compactLocalTxn =
      Mux(compactId <= 2.U, compactId(clientTxnIdBits - 1, 0), 0.U)

    for (client <- 0 until clientCount) {
      val selected = compactValid && compactSource === client.U
      io.clients(client).response.valid := io.downstreamResponse.valid && selected
      io.clients(client).response.bits.txnId := compactLocalTxn
      io.clients(client).response.bits.rdata := io.downstreamResponse.bits.rdata
      io.clients(client).response.bits.fault := io.downstreamResponse.bits.fault
      io.clients(client).response.bits.last := io.downstreamResponse.bits.last
    }

    io.downstreamResponse.ready := MuxCase(
      false.B,
      (0 until clientCount).map { client =>
        (compactValid && compactSource === client.U) -> io.clients(client).response.ready
      }
    )

    when(io.downstreamResponse.valid) {
      assert(compactValid,
        "SoC memory hub received an invalid compact transaction ID")
    }
  } else {
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
}
