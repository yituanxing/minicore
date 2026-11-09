# Import the Nexys Video reference DDR3 MIG + SmartConnect hierarchy, only.
# Reuses procedures from MIT-licensed vivado-risc-v, pinned and verified by
# tools/fpga/nexys_video_reference_gate.py. Not a complete Product board top.

foreach key {NEXYS_REFERENCE_DIR NEXYS_OUTPUT_DIR} {
    if {![info exists ::env($key)] || $::env($key) eq ""} {
        puts stderr "NEXYS_IMPORT_FAILED: missing $key"
        exit 2
    }
}
if {[string first "2026.1" [version -short]] < 0} {
    puts stderr "NEXYS_IMPORT_FAILED: reference requires Vivado 2026.1"
    exit 2
}

set reference_dir [file normalize $::env(NEXYS_REFERENCE_DIR)]
set output_dir [file normalize $::env(NEXYS_OUTPUT_DIR)]
set reference_tcl [file join $reference_dir board nexys-video riscv-2026.1.tcl]
set reset_source [file join $reference_dir board mem-reset-control.v]
set importer [file join [file dirname [info script]] nexys_video_ddr_import.tcl]
if {![file isfile $reference_tcl] || ![file isfile $reset_source]} {
    puts stderr "NEXYS_IMPORT_FAILED: missing pinned DDR reference TCL/reset source"
    exit 2
}
if {[file exists $output_dir]} {
    puts stderr "NEXYS_IMPORT_FAILED: output must not already exist"
    exit 2
}

if {[catch {
    create_project aether_nexys_ddr $output_dir -part xc7a200tsbg484-1
    set_property BOARD_PART digilentinc.com:nexys_video:part0:1.1 [current_project]
    add_files -norecurse $reset_source
    source $importer
    ::aether_nexys::import_reference_ddr $reference_tcl

    create_bd_design aether_nexys_ddr
    create_hier_cell_DDR [current_bd_instance .] DDR

    # Deliberately leave the DDR AXI source, 50/200 MHz clocks, XADC temp,
    # reset and board I/O unconnected. These must be provided by the Product
    # physical top with the correct clock/reset CDC before validation/P&R.
    make_bd_intf_pins_external [get_bd_intf_pins DDR/S00_AXI]
    make_bd_intf_pins_external [get_bd_intf_pins DDR/ddr3_sdram]
    save_bd_design
    write_bd_tcl -force [file join $output_dir aether_nexys_ddr_imported.tcl]
    set fp [open [file join $output_dir IMPORT_ONLY.txt] w]
    puts $fp "NEXYS_DDR_HIERARCHY_IMPORTED_ONLY"
    puts $fp "MIG narrow-burst source patched to 1; not yet generated, calibrated, validated, placed or routed"
    close $fp
} message options]} {
    puts stderr "NEXYS_IMPORT_FAILED: $message"
    puts stderr [dict get $options -errorinfo]
    exit 1
}

puts "NEXYS_DDR_HIERARCHY_IMPORTED_ONLY: $output_dir"
exit 0
