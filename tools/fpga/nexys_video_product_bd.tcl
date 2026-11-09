# Product AXI4 -> Nexys Video DDR hierarchy *logical integration* in Vivado.
# The original source remains MIT-licensed upstream eugene-tarassov/vivado-risc-v.
# No statement about physical place/route, Fmax or Debian login is made here.

foreach key {NEXYS_REFERENCE_DIR NEXYS_OUTPUT_DIR NEXYS_PRODUCT_RTL_DIR} {
    if {![info exists ::env($key)] || $::env($key) eq ""} {
        puts stderr "NEXYS_PRODUCT_BD_FAILED: missing $key"
        exit 2
    }
}
if {[llength [info commands version]] == 0 ||
    [string first "2026.1" [version -short]] < 0} {
    puts stderr "NEXYS_PRODUCT_BD_FAILED: Vivado 2026.1 required"
    exit 2
}

set reference_dir [file normalize $::env(NEXYS_REFERENCE_DIR)]
set rtl_dir [file normalize $::env(NEXYS_PRODUCT_RTL_DIR)]
set output_dir [file normalize $::env(NEXYS_OUTPUT_DIR)]
set tools_dir [file dirname [file normalize [info script]]]
set reference_tcl [file join $reference_dir board nexys-video riscv-2026.1.tcl]
set reset_source [file join $reference_dir board mem-reset-control.v]
set bridge_source [file join $tools_dir aether_product_nexys_axi_bridge.sv]
set sv_files [lsort [glob -nocomplain -directory $rtl_dir *.sv]]

if {[file exists $output_dir]} {
    puts stderr "NEXYS_PRODUCT_BD_FAILED: output directory must be new"
    exit 2
}
if {[llength $sv_files] == 0 || ![file isfile $reference_tcl] ||
    ![file isfile $reset_source] || ![file isfile $bridge_source]} {
    puts stderr "NEXYS_PRODUCT_BD_FAILED: pinned source, bridge or exported Product RTL missing"
    exit 2
}

if {[catch {
    create_project aether_nexys_product $output_dir -part xc7a200tsbg484-1
    set_property BOARD_PART digilentinc.com:nexys_video:part0:1.1 [current_project]

    add_files -norecurse $reset_source
    add_files -norecurse $bridge_source
    add_files -norecurse {*}$sv_files
    add_files -fileset constrs_1 -norecurse [file join $reference_dir board nexys-video top.xdc]
    add_files -fileset constrs_1 -norecurse [file join $reference_dir board nexys-video uart.xdc]
    add_files -fileset constrs_1 -norecurse [file join $tools_dir nexys_video_product_spi_sd.xdc]
    update_compile_order -fileset sources_1

    source [file join $tools_dir nexys_video_ddr_import.tcl]
    ::aether_nexys::import_reference_ddr $reference_tcl

    create_bd_design aether_nexys_product
    create_hier_cell_DDR [current_bd_instance .] DDR
    set product [create_bd_cell -type module -reference aether_product_nexys_axi_bridge Product]
    if {$product eq "" || [llength [get_bd_intf_pins -quiet Product/M_AXI]] != 1} {
        error "Product bridge was not recognized as a complete AXI4 master"
    }
    connect_bd_intf_net [get_bd_intf_pins Product/M_AXI] [get_bd_intf_pins DDR/S00_AXI]

    set ddr3 [create_bd_intf_port -mode Master -vlnv xilinx.com:interface:ddrx_rtl:1.0 ddr3_sdram]
    connect_bd_intf_net $ddr3 [get_bd_intf_pins DDR/ddr3_sdram]

    # Exact upstream 100 MHz input, 50 MHz Product/SmartConnect S00 clock,
    # 200 MHz MIG reference and distinct MIG UI clock on SmartConnect aclk1.
    set sys_clock [create_bd_port -dir I -type clk sys_clock]
    set_property -dict [list CONFIG.FREQ_HZ {100000000}] $sys_clock
    set reset [create_bd_port -dir I -type rst reset]
    set_property -dict [list CONFIG.POLARITY {ACTIVE_LOW}] $reset
    set clk [create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_0]
    set_property -dict [list \
        CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {50.000} \
        CONFIG.CLKOUT2_REQUESTED_OUT_FREQ {200.000} \
        CONFIG.CLKOUT2_USED {true} \
        CONFIG.NUM_OUT_CLKS {2} \
        CONFIG.PRIM_SOURCE {No_buffer} \
        CONFIG.USE_PHASE_ALIGNMENT {true} \
        CONFIG.USE_RESET {false}] $clk
    connect_bd_net $sys_clock [get_bd_pins $clk/clk_in1]
    connect_bd_net [get_bd_pins $clk/clk_out1] [get_bd_pins Product/cpu_clk_50] [get_bd_pins DDR/axi_clock]
    connect_bd_net [get_bd_pins $clk/clk_out2] [get_bd_pins DDR/clock_200MHz]
    connect_bd_net [get_bd_pins $clk/locked] [get_bd_pins Product/clock_locked] [get_bd_pins DDR/clock_ok]

    # Reference DDR/sys_reset is active high; DDR/axi_reset is active LOW.
    # MIG must leave reset before calibration; Product's own readiness
    # qualifier observes DDR/mem_ok and synchronizes release to 50 MHz.
    set reset_inv [create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 reset_inv]
    set_property -dict [list CONFIG.C_OPERATION {not} CONFIG.C_SIZE {1}] $reset_inv
    connect_bd_net $reset [get_bd_pins $reset_inv/Op1]
    connect_bd_net [get_bd_pins $reset_inv/Res] [get_bd_pins DDR/sys_reset]

    set reset_ctrl [create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 axi_reset_ctrl]
    set_property -dict [list CONFIG.C_EXT_RESET_HIGH {1}] $reset_ctrl
    connect_bd_net [get_bd_pins $clk/clk_out1] [get_bd_pins $reset_ctrl/slowest_sync_clk]
    connect_bd_net [get_bd_pins $clk/locked] [get_bd_pins $reset_ctrl/dcm_locked]
    connect_bd_net [get_bd_pins $reset_inv/Res] [get_bd_pins $reset_ctrl/ext_reset_in]
    connect_bd_net [get_bd_pins $reset_ctrl/peripheral_aresetn] \
        [get_bd_pins Product/cpu_aresetn] [get_bd_pins DDR/axi_reset]
    connect_bd_net [get_bd_pins DDR/mem_ok] [get_bd_pins Product/ddr_mem_ok]

    # The reference MIG requires real XADC temperature input. Preserve
    # the known wizard configuration, not an arbitrary tied temperature.
    set xadc [create_bd_cell -type ip -vlnv xilinx.com:ip:xadc_wiz:3.3 XADC]
    set_property -dict [list \
        CONFIG.ADC_OFFSET_AND_GAIN_CALIBRATION {true} \
        CONFIG.ADC_OFFSET_CALIBRATION {true} \
        CONFIG.CHANNEL_ENABLE_VBRAM {true} \
        CONFIG.CHANNEL_ENABLE_VCCAUX {true} \
        CONFIG.CHANNEL_ENABLE_VCCINT {true} \
        CONFIG.CHANNEL_ENABLE_VP_VN {true} \
        CONFIG.ENABLE_TEMP_BUS {true} \
        CONFIG.SENSOR_OFFSET_AND_GAIN_CALIBRATION {true} \
        CONFIG.SENSOR_OFFSET_CALIBRATION {true} \
        CONFIG.SEQUENCER_MODE {Continuous} \
        CONFIG.TEMPERATURE_ALARM_RESET {50} \
        CONFIG.TEMPERATURE_ALARM_TRIGGER {60} \
        CONFIG.VCCAUX_ALARM {false} \
        CONFIG.VCCINT_ALARM {false} \
        CONFIG.XADC_STARUP_SELECTION {channel_sequencer}] $xadc
    connect_bd_net [get_bd_pins $xadc/temp_out] [get_bd_pins DDR/device_temp]
    connect_bd_net [get_bd_pins $clk/clk_out1] [get_bd_pins $xadc/s_axi_aclk]
    connect_bd_net [get_bd_pins $reset_ctrl/peripheral_aresetn] [get_bd_pins $xadc/s_axi_aresetn]

    # Product owns exactly one serial UART (1Mbaud uploader, then Linux 16550)
    # and four SPI-mode SD wires. No Rocket or AXI IO peripheral is imported.
    foreach {name direction product_pin} {
        usb_uart_rxd I usb_uart_rxd
        usb_uart_txd O usb_uart_txd
        sdio_clk O sdio_clk
        sdio_cmd O sdio_cmd
        sdio_dat0 I sdio_dat0
        sdio_dat3 O sdio_dat3
    } {
        set port [create_bd_port -dir $direction $name]
        connect_bd_net $port [get_bd_pins Product/$product_pin]
    }
    set sd_reset [create_bd_port -dir O sdio_reset]
    # The upstream SD controller drives reset low when held in reset; Product
    # doesn't own an SD reset register, so release after synchronized reset.
    connect_bd_net $sd_reset [get_bd_pins $reset_ctrl/peripheral_aresetn]

    set no_external_image_fault [create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 no_external_image_fault]
    set_property -dict [list CONFIG.CONST_VAL {0} CONFIG.CONST_WIDTH {1}] $no_external_image_fault
    connect_bd_net [get_bd_pins $no_external_image_fault/dout] [get_bd_pins Product/board_image_failed]

    # Local 28-bit offset covers 256 MiB at 0, not an additional 0x80000000.
    assign_bd_address -offset 0x00000000 -range 0x10000000 \
        -target_address_space [get_bd_addr_spaces Product/M_AXI] \
        [get_bd_addr_segs DDR/mig_7series_0/memmap/memaddr] -force

    validate_bd_design
    save_bd_design
    write_bd_tcl -force [file join $output_dir product_nexys_bd_validated.tcl]
    set marker [open [file join $output_dir LOGICAL_BD_ONLY.txt] w]
    puts $marker "PRODUCT_NEXYS_LOGICAL_BD_ONLY"
    puts $marker "DDR/SmartConnect + Product AXI + clocks/reset/XADC/SPI/UART; no generated bitstream, P&R, DDR training, or Debian login"
    close $marker
} failure options]} {
    puts stderr "NEXYS_PRODUCT_BD_FAILED: $failure"
    puts stderr [dict get $options -errorinfo]
    exit 1
}

puts "PRODUCT_NEXYS_LOGICAL_BD_ONLY: $output_dir"
exit 0
