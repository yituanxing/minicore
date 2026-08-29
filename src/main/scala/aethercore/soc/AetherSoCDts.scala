package aethercore.soc

/**
  * Deterministic DTS renderer for the qualified AetherSoC v0 board.
  *
  * All physical addresses and interrupt-topology values come from
  * AetherSoCBoardSpec. The renderer owns only standard device-tree syntax and
  * Linux/OpenSBI-compatible node bindings.
  */
object AetherSoCDts {
  private def hex(value: BigInt): String = "0x" + value.toString(16)

  private def cells64(value: BigInt): String = {
    val hi = (value >> 32) & BigInt("ffffffff", 16)
    val lo = value & BigInt("ffffffff", 16)
    s"${hex(hi)} ${hex(lo)}"
  }

  private def reg64(base: BigInt, size: BigInt): String =
    s"<${cells64(base)} ${cells64(size)}>"

  private def escape(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")

  private def nodeAddress(value: BigInt): String = value.toString(16)

  def render(
      board: AetherSoCBoardSpec,
      isa: String = "rv64ima_zicsr_zifencei",
      mmu: String = "sv39",
      bootargs: Option[String] = None
  ): String = {
    require(isa.startsWith("rv64i"), s"AetherSoC v0 DTS requires RV64I-derived ISA, got $isa")
    require(mmu == "sv39", s"AetherSoC v0 DTS currently supports Sv39, got $mmu")

    val map = board.addressMap
    val chosenBootargs = bootargs
      .filter(_.nonEmpty)
      .map(value => s"""        bootargs = "${escape(value)}";
""")
      .getOrElse("")

    s"""/dts-v1/;

/ {
    #address-cells = <2>;
    #size-cells = <2>;
    compatible = "aethercore,aethersoc-v0", "aethercore,rv64";
    model = "AetherSoC v0 RV64 Sv39";

    chosen {
        stdout-path = "/soc/serial@${nodeAddress(map.uartBase)}";
${chosenBootargs}    };

    cpus {
        #address-cells = <1>;
        #size-cells = <0>;
        timebase-frequency = <${board.timebaseFrequencyHz}>;

        cpu@0 {
            device_type = "cpu";
            reg = <0>;
            status = "okay";
            compatible = "riscv";
            riscv,isa = "${escape(isa)}";
            mmu-type = "riscv,${escape(mmu)}";

            cpu_intc: interrupt-controller {
                #interrupt-cells = <1>;
                interrupt-controller;
                compatible = "riscv,cpu-intc";
            };
        };
    };

    memory@${nodeAddress(map.ramBase)} {
        device_type = "memory";
        reg = ${reg64(map.ramBase, map.ramBytes)};
    };

    soc {
        #address-cells = <2>;
        #size-cells = <2>;
        compatible = "simple-bus";
        ranges;

        bootrom@${nodeAddress(map.bootRomBase)} {
            compatible = "aethercore,bootrom";
            reg = ${reg64(map.bootRomBase, map.bootRomBytes)};
        };

        plic: interrupt-controller@${nodeAddress(map.plicBase)} {
            compatible = "sifive,plic-1.0.0", "riscv,plic0";
            reg = ${reg64(map.plicBase, map.plicBytes)};
            #address-cells = <0>;
            #interrupt-cells = <1>;
            interrupt-controller;
            riscv,ndev = <${board.plicSourceCount}>;
            interrupts-extended = <&cpu_intc 0xffffffff &cpu_intc ${board.supervisorExternalInterruptId}>;
        };

        serial@${nodeAddress(map.uartBase)} {
            compatible = "ns16550a";
            reg = ${reg64(map.uartBase, map.uartBytes)};
            clock-frequency = <${board.uartClockFrequencyHz}>;
            current-speed = <${board.uartBaud}>;
            reg-shift = <0>;
            reg-io-width = <1>;
            interrupt-parent = <&plic>;
            interrupts = <${board.uartPlicSourceId}>;
            status = "okay";
        };

        mtimer@${nodeAddress(map.mtimeAddress)} {
            compatible = "riscv,aclint-mtimer";
            reg = <${cells64(map.mtimeAddress)} ${cells64(8)}
                   ${cells64(map.mtimecmpAddress)} ${cells64(8)}>;
            reg-names = "mtime", "mtimecmp";
            interrupts-extended = <&cpu_intc ${board.machineTimerInterruptId}>;
        };
    };
};
"""
  }
}
