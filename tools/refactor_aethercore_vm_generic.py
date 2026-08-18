#!/usr/bin/env python3
from pathlib import Path

path = Path('src/main/scala/aethercore/core/AetherCore.scala')
text = path.read_text()


def replace(old: str, new: str, count: int = 1) -> None:
    global text
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f'refactor ownership mismatch: expected {count} occurrence(s), found {actual}: {old[:120]!r}')
    text = text.replace(old, new)

replace(
'''  private val busBytes = config.platform.busBytes
  private val supervisorTimerCause =''',
'''  private val busBytes = config.platform.busBytes
  private val vmGeometry = config.isa.orderedPageTableGeometries.headOption
  private val vmPteBits = vmGeometry.map(_.pteBits).getOrElse(32)
  private val vmPteBytes = vmGeometry.map(_.pteBytes).getOrElse(4)
  private val supervisorTimerCause ='''
)

replace(
'''  require(!config.isa.hasSv32 || paddrBits >= 34, "Sv32 requires a PA width of at least 34 bits")''',
'''  vmGeometry.foreach { geometry =>
    require(
      paddrBits >= geometry.architecturalPhysicalAddressBits,
      s"${geometry.name} requires PA>=${geometry.architecturalPhysicalAddressBits}, got $paddrBits"
    )
  }'''
)

replace(
'''    val ptw = if (config.isa.hasSv32) Some(new PageTableReadBusIO(paddrBits)) else None''',
'''    val ptw = if (config.isa.hasPagedVirtualMemory)
      Some(new PageTableReadBusIO(paddrBits, vmPteBits))
    else None'''
)

replace(
'''  val dataVm = if (config.isa.hasSv32) Some(Module(new Sv32DataPathAdapter(paddrBits))) else None
  val fetchVm = if (config.isa.hasSv32) Some(Module(new Sv32InstructionFetchAdapter(paddrBits))) else None
  val compressedFetch = if (config.isa.hasC) Some(Module(new Rv32CParcelController(xlen))) else None
  val ptwArbiter = if (config.isa.hasSv32) Some(Module(new Sv32PtwArbiter(paddrBits))) else None
  val ptwPmp = if (config.isa.hasSv32) Some(Module(new PmpChecker(xlen, PmpConstants.MaxEntries, paddrBits))) else None

  val ifIdSfenceVma = if (config.isa.hasSv32) Sv32SystemInstruction.isSfenceVma(ifId.inst) else false.B
  val idExSfenceVma = if (config.isa.hasSv32) Sv32SystemInstruction.isSfenceVma(idEx.inst) else false.B
  val memWbSfenceVma = if (config.isa.hasSv32) Sv32SystemInstruction.isSfenceVma(memWb.inst) else false.B''',
'''  val dataVm = vmGeometry.map(geometry => Module(new DataPathAdapter(geometry, paddrBits)))
  val fetchVm = vmGeometry.map(geometry => Module(new InstructionFetchAdapter(geometry, paddrBits)))
  val compressedFetch = if (config.isa.hasC) Some(Module(new Rv32CParcelController(xlen))) else None
  val ptwArbiter = vmGeometry.map(geometry => Module(new PtwArbiter(geometry, paddrBits)))
  val ptwPmp = if (config.isa.hasPagedVirtualMemory)
    Some(Module(new PmpChecker(xlen, PmpConstants.MaxEntries, paddrBits)))
  else None

  val ifIdSfenceVma = if (config.isa.hasPagedVirtualMemory) SystemInstruction.isSfenceVma(ifId.inst) else false.B
  val idExSfenceVma = if (config.isa.hasPagedVirtualMemory) SystemInstruction.isSfenceVma(idEx.inst) else false.B
  val memWbSfenceVma = if (config.isa.hasPagedVirtualMemory) SystemInstruction.isSfenceVma(memWb.inst) else false.B'''
)

replace(
'''  val dataPteRdata = WireDefault(0.U(32.W))''',
'''  val dataPteRdata = WireDefault(0.U(vmPteBits.W))'''
)

# Every remaining hasSv32 use in AetherCore owns generic paged-VM composition,
# not Sv32 geometry. The actual geometry lives in vmGeometry and the adapters.
text = text.replace('config.isa.hasSv32', 'config.isa.hasPagedVirtualMemory')

replace('''    fetch.io.virtualAddress := fetchVirtualAddress(31, 0)''', '''    fetch.io.virtualAddress := fetchVirtualAddress''')
replace('''    pmp.io.bytes := 4.U''', '''    pmp.io.bytes := vmPteBytes.U''')
replace('''    vm.io.virtualAddress := exMem.result(31, 0)''', '''    vm.io.virtualAddress := exMem.result''')
replace(
'''    vm.io.wdata := Mux(atomicWriteRequest, atomicWriteData, exMem.storeData)(31, 0)
    vm.io.wmask := storeMask(3, 0)''',
'''    vm.io.wdata := Mux(atomicWriteRequest, atomicWriteData, exMem.storeData)
    vm.io.wmask := storeMask'''
)
replace('''    vm.io.dataRdata := io.dmem.rdata(31, 0)''', '''    vm.io.dataRdata := io.dmem.rdata''')

# The shared adapters must be the only production VM datapath after this slice.
for forbidden in (
    'new Sv32DataPathAdapter',
    'new Sv32InstructionFetchAdapter',
    'new Sv32PtwArbiter',
    'config.isa.hasSv32',
    'fetchVirtualAddress(31, 0)',
    'exMem.result(31, 0)',
    'io.dmem.rdata(31, 0)',
):
    if forbidden in text:
        raise SystemExit(f'legacy Sv32 production ownership remains: {forbidden}')

path.write_text(text)
print('aethercore_vm_refactor=PASS')
