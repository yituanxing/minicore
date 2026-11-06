# Synthesizable Product RV64GC board-neutral top: one 50 MHz Product/DDR UI clock.
# OOC synthesis reference ONLY. Board PLL, CDC, DDR PHY and pin constraints
# must be supplied for a concrete board before any routed timing claim.
create_clock -name p0_product_ui_clock -period 20.000 [get_ports clock]
