# Accurate 7-series part probe and optional identical-RTL 50 MHz OOC synthesis.
# This deliberately does not build a DDR3 PHY, PLL, board XDC or bitstream.
foreach key {SEVEN_SERIES_PART SEVEN_SERIES_RTL_DIR SEVEN_SERIES_OUTPUT_DIR SEVEN_SERIES_MODE} {
  if {![info exists ::env($key)] || $::env($key) eq ""} {
    puts stderr "ERROR: missing $key"
    exit 2
  }
}
set part $::env(SEVEN_SERIES_PART)
set rtl_dir $::env(SEVEN_SERIES_RTL_DIR)
set out_dir $::env(SEVEN_SERIES_OUTPUT_DIR)
set mode $::env(SEVEN_SERIES_MODE)
set top AetherCoreProductFpgaUartDdrBoard
set reference_xdc [file join [file dirname [file normalize [info script]]] artix7_product_ooc.xdc]
if {$mode ni {probe synth}} {
  puts stderr "ERROR: invalid 7-series operation: $mode"
  exit 2
}
file mkdir $out_dir
if {[catch {
  set matches [get_parts -quiet $part]
  if {[llength $matches] != 1 || [lindex $matches 0] ne $part} {
    error "Vivado does not recognize the exact reviewed part: $part"
  }
  create_project -in_memory -part $part
  set fp [open [file join $out_dir device.txt] w]
  puts $fp $part
  close $fp
  if {$mode eq "synth"} {
    set files [lsort [glob -nocomplain -directory $rtl_dir *.sv]]
    if {[llength $files] == 0} { error "missing SystemVerilog files" }
    if {![file isfile $reference_xdc]} { error "missing Product 50 MHz XDC" }
    read_verilog -sv {*}$files
    read_xdc $reference_xdc
    synth_design -mode out_of_context -top $top -part $part
    if {[llength [get_ports -quiet clock]] != 1} { error "Product clock port missing" }
    set clock [get_clocks -quiet p0_product_ui_clock]
    if {[llength $clock] != 1} { error "Product 50 MHz clock missing" }
    if {[expr {abs(double([get_property PERIOD $clock]) - 20.0)}] > 0.001} {
      error "Product OOC clock is not 20 ns"
    }
    report_utilization -hierarchical -file [file join $out_dir utilization.rpt]
    report_timing_summary -file [file join $out_dir timing_synth.rpt]
    write_checkpoint -force [file join $out_dir synth.dcp]
  }
} message options]} {
  puts stderr "SEVEN_SERIES_${mode}_FAILED: $message"
  puts stderr [dict get $options -errorinfo]
  exit 1
}
puts "SEVEN_SERIES_${mode}_DONE: $part (no board physical implementation)"
exit 0
