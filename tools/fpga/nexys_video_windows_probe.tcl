# Native Windows Vivado tool/board readiness only. No project, synthesis,
# routing, bitstream, board boot or DDR calibration is performed here.
if {[llength [info commands version]] == 0 ||
    [string first "2026.1" [version -short]] < 0} {
    puts stderr "NEXYS_WINDOWS_NOT_READY: Vivado 2026.1 required"
    exit 2
}
set part xc7a200tsbg484-1
if {[llength [get_parts -quiet $part]] != 1} {
    puts stderr "NEXYS_WINDOWS_NOT_READY: missing exact Artix-7 part $part"
    exit 2
}
set board_part digilentinc.com:nexys_video:part0:1.1
if {[llength [get_board_parts -quiet $board_part]] != 1} {
    puts stderr "NEXYS_WINDOWS_NOT_READY: install Digilent board definition $board_part"
    exit 2
}
puts "WINDOWS_VIVADO_READY_ONLY: Vivado [version -short] part=$part board=$board_part (no routed timing or FPGA PASS)"
exit 0
