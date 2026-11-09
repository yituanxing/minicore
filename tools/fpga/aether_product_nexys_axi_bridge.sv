// Product-to-Vivado-BD interface, not a complete physical board top.
// Product's 0x80000000..0x8fffffff RAM has already been translated to a
// 28-bit zero-based AXI address. The leading zero selects the lower 256MiB
// of the 29-bit / 512MiB Nexys Video MIG aperture; do not subtract again.
// Actual clock/reset/DDR/XADC/SD pad wiring lives in the BD Tcl.
module aether_product_nexys_axi_bridge (
    (* X_INTERFACE_INFO = "xilinx.com:signal:clock:1.0 cpu_clk_50 CLK" *)
    (* X_INTERFACE_PARAMETER = "XIL_INTERFACENAME cpu_clk_50, ASSOCIATED_BUSIF M_AXI, ASSOCIATED_RESET cpu_aresetn, FREQ_HZ 50000000" *)
    input  wire        cpu_clk_50,
    (* X_INTERFACE_INFO = "xilinx.com:signal:reset:1.0 cpu_aresetn RST" *)
    (* X_INTERFACE_PARAMETER = "XIL_INTERFACENAME cpu_aresetn, POLARITY ACTIVE_LOW" *)
    input  wire        cpu_aresetn,
    input  wire        clock_locked,
    input  wire        ddr_mem_ok,
    input  wire        board_image_failed,
    input  wire        usb_uart_rxd,
    output wire        usb_uart_txd,
    output wire        sdio_clk,
    output wire        sdio_cmd,
    input  wire        sdio_dat0,
    output wire        sdio_dat3,
    output wire        cpu_released,
    output wire        boot_fault,

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWID" *)
    output wire [3:0]  M_AXI_AWID,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWADDR" *)
    (* X_INTERFACE_PARAMETER = "XIL_INTERFACENAME M_AXI, PROTOCOL AXI4, ADDR_WIDTH 29, DATA_WIDTH 64, ID_WIDTH 4, HAS_BURST 1, HAS_LOCK 1, HAS_CACHE 1, HAS_PROT 1, HAS_QOS 1, SUPPORTS_NARROW_BURST 1" *)
    output wire [28:0] M_AXI_AWADDR,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWLEN" *)
    output wire [7:0]  M_AXI_AWLEN,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWSIZE" *)
    output wire [2:0]  M_AXI_AWSIZE,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWBURST" *)
    output wire [1:0]  M_AXI_AWBURST,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWLOCK" *)
    output wire        M_AXI_AWLOCK,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWCACHE" *)
    output wire [3:0]  M_AXI_AWCACHE,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWPROT" *)
    output wire [2:0]  M_AXI_AWPROT,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWQOS" *)
    output wire [3:0]  M_AXI_AWQOS,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWVALID" *)
    output wire        M_AXI_AWVALID,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWREADY" *)
    input  wire        M_AXI_AWREADY,

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI WDATA" *)
    output wire [63:0] M_AXI_WDATA,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI WSTRB" *)
    output wire [7:0]  M_AXI_WSTRB,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI WLAST" *)
    output wire        M_AXI_WLAST,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI WVALID" *)
    output wire        M_AXI_WVALID,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI WREADY" *)
    input  wire        M_AXI_WREADY,

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI BID" *)
    input  wire [3:0]  M_AXI_BID,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI BRESP" *)
    input  wire [1:0]  M_AXI_BRESP,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI BVALID" *)
    input  wire        M_AXI_BVALID,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI BREADY" *)
    output wire        M_AXI_BREADY,

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARID" *)
    output wire [3:0]  M_AXI_ARID,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARADDR" *)
    output wire [28:0] M_AXI_ARADDR,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARLEN" *)
    output wire [7:0]  M_AXI_ARLEN,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARSIZE" *)
    output wire [2:0]  M_AXI_ARSIZE,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARBURST" *)
    output wire [1:0]  M_AXI_ARBURST,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARLOCK" *)
    output wire        M_AXI_ARLOCK,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARCACHE" *)
    output wire [3:0]  M_AXI_ARCACHE,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARPROT" *)
    output wire [2:0]  M_AXI_ARPROT,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARQOS" *)
    output wire [3:0]  M_AXI_ARQOS,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARVALID" *)
    output wire        M_AXI_ARVALID,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARREADY" *)
    input  wire        M_AXI_ARREADY,

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI RID" *)
    input  wire [3:0]  M_AXI_RID,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI RDATA" *)
    input  wire [63:0] M_AXI_RDATA,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI RRESP" *)
    input  wire [1:0]  M_AXI_RRESP,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI RLAST" *)
    input  wire        M_AXI_RLAST,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI RVALID" *)
    input  wire        M_AXI_RVALID,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI RREADY" *)
    output wire        M_AXI_RREADY
);
    wire [27:0] product_awaddr;
    wire [27:0] product_araddr;
    assign M_AXI_AWADDR = {1'b0, product_awaddr};
    assign M_AXI_ARADDR = {1'b0, product_araddr};

    AetherCoreProductFpgaUartDdrBoard product (
        .clock(cpu_clk_50),
        .reset(~cpu_aresetn),
        .io_serialRx(usb_uart_rxd),
        .io_serialTx(usb_uart_txd),
        .io_spiSclk(sdio_clk),
        .io_spiMosi(sdio_cmd),
        .io_spiMiso(sdio_dat0),
        .io_spiCsN(sdio_dat3),
        .io_rawClockLocked(clock_locked),
        .io_rawDdrCalibrated(ddr_mem_ok),
        .io_boardImageFailed(board_image_failed),
        .io_cpuReleased(cpu_released),
        .io_bootFault(boot_fault),

        .io_localDdrAxi_aw_bits_id(M_AXI_AWID),
        .io_localDdrAxi_aw_bits_addr(product_awaddr),
        .io_localDdrAxi_aw_bits_len(M_AXI_AWLEN),
        .io_localDdrAxi_aw_bits_size(M_AXI_AWSIZE),
        .io_localDdrAxi_aw_bits_burst(M_AXI_AWBURST),
        .io_localDdrAxi_aw_bits_lock(M_AXI_AWLOCK),
        .io_localDdrAxi_aw_bits_cache(M_AXI_AWCACHE),
        .io_localDdrAxi_aw_bits_prot(M_AXI_AWPROT),
        .io_localDdrAxi_aw_bits_qos(M_AXI_AWQOS),
        .io_localDdrAxi_aw_valid(M_AXI_AWVALID),
        .io_localDdrAxi_aw_ready(M_AXI_AWREADY),

        .io_localDdrAxi_w_bits_data(M_AXI_WDATA),
        .io_localDdrAxi_w_bits_strb(M_AXI_WSTRB),
        .io_localDdrAxi_w_bits_last(M_AXI_WLAST),
        .io_localDdrAxi_w_valid(M_AXI_WVALID),
        .io_localDdrAxi_w_ready(M_AXI_WREADY),

        .io_localDdrAxi_b_bits_id(M_AXI_BID),
        .io_localDdrAxi_b_bits_resp(M_AXI_BRESP),
        .io_localDdrAxi_b_valid(M_AXI_BVALID),
        .io_localDdrAxi_b_ready(M_AXI_BREADY),

        .io_localDdrAxi_ar_bits_id(M_AXI_ARID),
        .io_localDdrAxi_ar_bits_addr(product_araddr),
        .io_localDdrAxi_ar_bits_len(M_AXI_ARLEN),
        .io_localDdrAxi_ar_bits_size(M_AXI_ARSIZE),
        .io_localDdrAxi_ar_bits_burst(M_AXI_ARBURST),
        .io_localDdrAxi_ar_bits_lock(M_AXI_ARLOCK),
        .io_localDdrAxi_ar_bits_cache(M_AXI_ARCACHE),
        .io_localDdrAxi_ar_bits_prot(M_AXI_ARPROT),
        .io_localDdrAxi_ar_bits_qos(M_AXI_ARQOS),
        .io_localDdrAxi_ar_valid(M_AXI_ARVALID),
        .io_localDdrAxi_ar_ready(M_AXI_ARREADY),

        .io_localDdrAxi_r_bits_id(M_AXI_RID),
        .io_localDdrAxi_r_bits_data(M_AXI_RDATA),
        .io_localDdrAxi_r_bits_resp(M_AXI_RRESP),
        .io_localDdrAxi_r_bits_last(M_AXI_RLAST),
        .io_localDdrAxi_r_valid(M_AXI_RVALID),
        .io_localDdrAxi_r_ready(M_AXI_RREADY)
    );
endmodule
