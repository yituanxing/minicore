# Product SPI mode on Nexys Video microSD, remapped from pinned upstream
# board/nexys-video/sdc.xdc. Source provenance is enforced by the build gate.
# DAT1/DAT2 are deliberately unused in SPI mode; card detect is not consumed
# by the current Product SPI driver and must not be treated as a boot success.
set_property -dict { PACKAGE_PIN W19 IOSTANDARD LVCMOS33 IOB TRUE } [get_ports sdio_clk]
set_property -dict { PACKAGE_PIN W20 IOSTANDARD LVCMOS33 IOB TRUE } [get_ports sdio_cmd]
set_property -dict { PACKAGE_PIN V19 IOSTANDARD LVCMOS33 IOB TRUE } [get_ports sdio_dat0]
set_property -dict { PACKAGE_PIN U18 IOSTANDARD LVCMOS33 IOB TRUE } [get_ports sdio_dat3]
set_property -dict { PACKAGE_PIN V20 IOSTANDARD LVCMOS33 } [get_ports sdio_reset]
