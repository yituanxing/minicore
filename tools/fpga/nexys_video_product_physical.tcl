# Continue the already-validated, pinned Product Nexys Video BD project through
# real 2026.1 synthesis and routing.  This is post-route evidence ONLY: neither
# a generated bitstream nor FPGA DDR calibration nor Debian login is asserted.
#
# The separate logical-BD script deliberately remains unchanged and never
# invokes synthesis.  A second Vivado process opens its saved project here.

if {![info exists ::env(NEXYS_OUTPUT_DIR)] || $::env(NEXYS_OUTPUT_DIR) eq ""} {
    puts stderr "NEXYS_PHYSICAL_FAILED: missing NEXYS_OUTPUT_DIR"
    exit 2
}
if {[llength [info commands version]] == 0 ||
    [string first "2026.1" [version -short]] < 0} {
    puts stderr "NEXYS_PHYSICAL_FAILED: pinned Nexys MIG requires Vivado 2026.1"
    exit 2
}

set output_dir [file normalize $::env(NEXYS_OUTPUT_DIR)]
set project_file [file join $output_dir aether_nexys_product.xpr]
set logical_marker [file join $output_dir LOGICAL_BD_ONLY.txt]
set evidence_dir [file join $output_dir physical_reports]
if {![file isfile $project_file] || ![file isfile $logical_marker]} {
    puts stderr "NEXYS_PHYSICAL_FAILED: pinned, logically validated Vivado project missing"
    exit 2
}
if {[file exists $evidence_dir]} {
    puts stderr "NEXYS_PHYSICAL_FAILED: refusing to overwrite prior route evidence"
    exit 2
}

# A failed run may leave partial reports for diagnosis; it NEVER writes the
# final route-evidence marker, which the host validator requires verbatim.
file mkdir $evidence_dir
if {[catch {
    open_project $project_file
    set part [get_property PART [current_project]]
    if {$part ne "xc7a200tsbg484-1"} {
        error "unexpected FPGA part $part (expected xc7a200tsbg484-1)"
    }
    set board_part digilentinc.com:nexys_video:part0:1.1
    if {[llength [get_board_parts -quiet $board_part]] != 1} {
        error "Nexys Video Digilent board files are missing: $board_part"
    }

    set bd_files [get_files -quiet *.bd]
    if {[llength $bd_files] != 1 ||
        [file tail [lindex $bd_files 0]] ne "aether_nexys_product.bd"} {
        error "the one expected aether_nexys_product.bd was not found"
    }
    generate_target all $bd_files
    make_wrapper -files $bd_files -top -add
    set wrappers [get_files -quiet *aether_nexys_product_wrapper.v]
    if {[llength $wrappers] != 1} {
        error "Vivado failed to add the generated BD top-level wrapper"
    }
    set_property top aether_nexys_product_wrapper [get_filesets sources_1]
    update_compile_order -fileset sources_1

    # Project Mode lets Vivado manage generated MIG/SmartConnect/clock IP and
    # out-of-context IP synth runs.  Direct synth_design on the BD is invalid.
    launch_runs synth_1 -jobs 2
    wait_on_run synth_1
    if {![string match "*Complete*" [get_property STATUS [get_runs synth_1]]]} {
        error "Vivado synthesis run did not complete"
    }
    open_run synth_1
    report_utilization -file [file join $evidence_dir synth_utilization.rpt]
    report_timing_summary -report_unconstrained \
        -file [file join $evidence_dir synth_timing_summary.rpt]
    write_checkpoint -force [file join $evidence_dir synth.dcp]
    close_design

    launch_runs impl_1 -to_step route_design -jobs 2
    wait_on_run impl_1
    if {![string match "*Complete*" [get_property STATUS [get_runs impl_1]]]} {
        error "Vivado implementation did not finish route_design"
    }
    open_run impl_1
    report_utilization -file [file join $evidence_dir route_utilization.rpt]
    report_route_status -file [file join $evidence_dir route_status.rpt]
    report_clocks -file [file join $evidence_dir route_clocks.rpt]
    check_timing -verbose -file [file join $evidence_dir route_check_timing.rpt]
    report_timing_summary -report_unconstrained -check_timing_verbose \
        -file [file join $evidence_dir route_timing_summary.rpt]
    report_drc -file [file join $evidence_dir route_drc.rpt]
    write_checkpoint -force [file join $evidence_dir routed.dcp]

    # Do not silently accept an unclocked design, an unconstrained-only path,
    # negative setup/hold slack, or any DRC violations.  Other unconstrained
    # interface constraints remain separately visible in check_timing.
    if {[llength [get_clocks -quiet]] == 0} {
        error "no timing clocks found in the routed design"
    }
    set setup_paths [get_timing_paths -delay_type max -max_paths 1 -no_report_unconstrained]
    set hold_paths [get_timing_paths -delay_type min -max_paths 1 -no_report_unconstrained]
    if {[llength $setup_paths] != 1 || [llength $hold_paths] != 1} {
        error "no constrained setup/hold paths in routed design"
    }
    set setup_slack [get_property SLACK [lindex $setup_paths 0]]
    set hold_slack [get_property SLACK [lindex $hold_paths 0]]
    foreach slack [list $setup_slack $hold_slack] {
        if {![string is double -strict $slack] ||
            $slack <= -1e100 || $slack >= 1e100} {
            error "invalid routed timing slack $slack"
        }
    }
    if {$setup_slack < 0 || $hold_slack < 0} {
        error "routed timing violation: setup=$setup_slack hold=$hold_slack ns"
    }
    set drc_violations [get_drc_violations]
    if {[llength $drc_violations] != 0} {
        error "routed design has [llength $drc_violations] DRC violations; review route_drc.rpt"
    }

    # This marker is deliberately written LAST, after every route and static
    # timing check.  It does not certify I/O timing completeness or hardware.
    set marker [open [file join $evidence_dir route-evidence.txt] w]
    puts $marker "NEXYS_ROUTED_TIMING_ONLY"
    puts $marker "part=$part"
    puts $marker "vivado_version=[version -short]"
    puts $marker "setup_wns_ns=$setup_slack"
    puts $marker "hold_whs_ns=$hold_slack"
    puts $marker "drc_violations=0"
    puts $marker "bitstream_generated=false"
    puts $marker "ddr_board_calibrated=false"
    puts $marker "fpga_debian_login=false"
    close $marker
} failure options]} {
    puts stderr "NEXYS_PHYSICAL_FAILED: $failure"
    puts stderr [dict get $options -errorinfo]
    exit 1
}

puts "NEXYS_ROUTED_TIMING_ONLY: $evidence_dir (real route + constrained timing; no board PASS)"
exit 0
