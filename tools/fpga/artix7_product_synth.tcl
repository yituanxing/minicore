# Out-of-context synthesis of the exact Product Small RV64GC UART/DDR/SPI-SD top.
# localDdrAxi is an INTERNAL future MIG connection, not physical DDR pins.
# Without a real MIG/PLL/XDC/board, do not call this placed/routed timing.
foreach key {ARTIX7_PART ARTIX7_RTL_DIR ARTIX7_OUTPUT_DIR} {
  if {![info exists ::env($key)] || $::env($key) eq ""} {
    puts stderr "ERROR: missing $key"
    exit 2
  }
}
set part $::env(ARTIX7_PART)
set rtl_dir $::env(ARTIX7_RTL_DIR)
set out_dir $::env(ARTIX7_OUTPUT_DIR)
set top AetherCoreProductFpgaUartDdrBoard
set reference_xdc [file join [file dirname [file normalize [info script]]] artix7_product_ooc.xdc]
if {![file isfile $reference_xdc]} {
  puts stderr "ERROR: missing 50 MHz Product OOC clock constraints: $reference_xdc"
  exit 2
}
set files [lsort [glob -nocomplain -directory $rtl_dir *.sv]]
if {[llength $files] == 0} {
  puts stderr "ERROR: no generated SystemVerilog in $rtl_dir"
  exit 2
}
file mkdir $out_dir

if {[catch {
  create_project -in_memory -part $part
  read_verilog -sv {*}$files
  # UG903/UG901: read synthesis clock constraints BEFORE synth_design so the
  # mapper is timing-driven, not merely given a post-synthesis timing label.
  read_xdc $reference_xdc
  synth_design -mode out_of_context -top $top -part $part
  if {[llength [get_ports -quiet clock]] != 1} {
    error "expected exactly one Product clock input"
  }
  set reference_clock [get_clocks -quiet p0_product_ui_clock]
  if {[llength $reference_clock] != 1} {
    error "expected one 50 MHz Product OOC reference clock"
  }
  if {[expr {abs(double([get_property PERIOD $reference_clock]) - 20.0)}] > 0.001} {
    error "Product OOC clock period differs from required 20 ns"
  }
  report_utilization -hierarchical -file [file join $out_dir utilization.rpt]
  report_timing_summary -file [file join $out_dir timing_synth.rpt]
  write_checkpoint -force [file join $out_dir synth.dcp]
} message options]} {
  puts stderr "ARTIX7_SYNTH_FAILED: $message"
  puts stderr [dict get $options -errorinfo]
  exit 1
}
puts "ARTIX7_LOGIC_SYNTH_ONLY: $part $top; no MIG, board XDC, P&R or Fmax qualification"
exit 0
