# Nexys Video DDR hierarchy import from a pinned, externally provided MIT source.
# Original hierarchy: Eugene Tarassov, vivado-risc-v (MIT, Copyright 2021-2025).
# The original LICENSE.md must remain with the reference source checkout.
#
# This file intentionally imports only two reference *procedures* without
# sourcing its full Rocket/ethernet/DMA/SDIO/IO Block Design. It does not
# connect Product AXI, clocks, XADC, pads or generate a bitstream by itself.

namespace eval ::aether_nexys {
    variable expected_tcl_blob da8f413e7fc5a69481e64043c9e7df038acf60bc
    variable procedure_names {
        write_mig_file_riscv_mig_7series_0_0
        create_hier_cell_DDR
    }
}

# Split a TCL script into complete top-level commands. info complete handles
# nested procedures and quoted XML; no command from upstream is evaluated here.
proc ::aether_nexys::extract_ddr_commands {source_text} {
    variable procedure_names
    set found [dict create]
    set current ""
    foreach line [split $source_text "\n"] {
        append current $line "\n"
        if {![info complete $current]} { continue }
        foreach name $procedure_names {
            if {[regexp -- "^\\s*proc\\s+$name\\s+" $current]} {
                if {[dict exists $found $name]} {
                    error "reference has duplicate DDR procedure $name"
                }
                dict set found $name $current
            }
        }
        set current ""
    }
    if {![info complete $current]} { error "reference ends with incomplete Tcl" }
    foreach name $procedure_names {
        if {![dict exists $found $name]} {
            error "reference is missing DDR procedure $name"
        }
    }

    set name write_mig_file_riscv_mig_7series_0_0
    set before {<C0_S_AXI_SUPPORTS_NARROW_BURST>0</C0_S_AXI_SUPPORTS_NARROW_BURST>}
    set after {<C0_S_AXI_SUPPORTS_NARROW_BURST>1</C0_S_AXI_SUPPORTS_NARROW_BURST>}
    set command [dict get $found $name]
    if {[llength [regexp -all -inline -- $before $command]] != 1} {
        error "reference MIG narrow setting changed; refuse auto-patch"
    }
    # The Product source emits single-beat SIZE=0/1/2/3, so a MIG configured
    # with narrow=0 is not safe behind SmartConnect (AMD PG247).
    dict set found $name [string map [list $before $after] $command]
    return $found
}

proc ::aether_nexys::import_reference_ddr {reference_tcl_path} {
    variable expected_tcl_blob
    variable procedure_names
    if {[llength [info commands create_bd_cell]] == 0} {
        error "NEXYS_VIVADO_REQUIRED: this import must execute inside Vivado"
    }
    if {![file isfile $reference_tcl_path]} {
        error "NEXYS_REFERENCE_MISSING: $reference_tcl_path"
    }
    # Git blob SHA is pinned to the full upstream file. Do not silently use a
    # current branch, an edited reference, or an unrelated Nexys board design.
    set blob [string trim [exec git hash-object -- $reference_tcl_path]]
    if {$blob ne $expected_tcl_blob} {
        error "NEXYS_REFERENCE_CHANGED: expected $expected_tcl_blob, got $blob"
    }
    set fd [open $reference_tcl_path r]
    set source_text [read $fd]
    close $fd
    set selected [::aether_nexys::extract_ddr_commands $source_text]
    foreach name $procedure_names {
        uplevel #0 [dict get $selected $name]
    }
    puts "NEXYS_DDR_PROCEDURES_IMPORTED: pinned reference; MIG narrow=1; no physical qualification"
}
