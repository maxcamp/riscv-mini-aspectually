// See LICENSE for license details.

package mini

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

object Const {
  val PC_START = 0x200
  val PC_EVEC = 0x100
}

// The Datapath requires a host (???), an instruction cache, a data cache, and a set of control signals.
// Call Flipped() because the outputs of the Caches/Control are inputs to the Datapath and vice-versa
class DatapathIO(xlen: Int) extends Bundle {
  val host = new HostIO(xlen)
  val icache = Flipped(new CacheIO(xlen, xlen))
  val dcache = Flipped(new CacheIO(xlen, xlen))
  val ctrl = Flipped(new ControlSignals)
}

// Two registers save the state between the fetch stage and the execute state.
// These two registers hold the fetched instruction and the current program counter (PC).
class FetchExecutePipelineRegister(xlen: Int) extends Bundle {
  val inst = chiselTypeOf(Instructions.NOP)
  val pc = UInt(xlen.W)
}

// 4+ registers save the state between the execute stage and the writeback stage.
// We still need the current instruction and the current PC.
// We also store the output of the ALU and the input to the control status register 
class ExecuteWritebackPipelineRegister(xlen: Int) extends Bundle {
  val inst = chiselTypeOf(Instructions.NOP)
  val pc = UInt(xlen.W)
  val alu = UInt(xlen.W)
  val csr_in = UInt(xlen.W)
}

// Datapath Module, takes in configuration that inclues xlen (32 always)
class Datapath(val conf: CoreConfig) extends Module {

  // IO for this module
  val io = IO(new DatapathIO(conf.xlen))

  // Datapath makes use of 5+ other modules
  val csr = Module(new CSR(conf.xlen))                          // Control status register (CSR)
  val regFile = Module(new RegFile(conf.xlen))                  // 32 (?) registers in a file 
  val alu = Module(conf.makeAlu(conf.xlen))                     // Arithmetic logic unit (ALU)
  val immGen = Module(conf.makeImmGen(conf.xlen))               // ???
  val brCond = Module(conf.makeBrCond(conf.xlen))               // Branch condition decider

  import Control._

  /** Pipeline State Registers **/

  /***** Fetch / Execute Registers *****/
  // Initialize the FE registers
  val fe_reg = RegInit(
    (new FetchExecutePipelineRegister(conf.xlen)).Lit(
      _.inst -> Instructions.NOP,
      _.pc -> 0.U
    )
  )

  /***** Execute / Write Back Registers *****/
  // Initialize the EW registers
  val ew_reg = RegInit(
    (new ExecuteWritebackPipelineRegister(conf.xlen)).Lit(
      _.inst -> Instructions.NOP,
      _.pc -> 0.U,
      _.alu -> 0.U,
      _.csr_in -> 0.U
    )
  )

  /***** Control signals *****/
  // Declare the width of these control registers
  // Cloning the widths (strange chisel style)
  val st_type = Reg(io.ctrl.st_type.cloneType)                  // Store type (byte, half-word, or word if a store instruction)
  val ld_type = Reg(io.ctrl.ld_type.cloneType)                  // Load type
  val wb_sel = Reg(io.ctrl.wb_sel.cloneType)                    // Write-back select (?)
  val wb_en = Reg(Bool())                                       // Write-back enabled flag (?)
  val csr_cmd = Reg(io.ctrl.csr_cmd.cloneType)                  // CSR command (if a csr-related instruction)
  val illegal = Reg(Bool())                                     // Illegal instruction flag
  val pc_check = Reg(Bool())                                    // PC check flag (?)

  /***** Fetch *****/
  // started is a a register connected to the reset signal
  val started = RegNext(reset.asBool)
  // stall flag to halt the pipeline when waiting on the cache (if cache is not idle)
  val stall = !io.icache.resp.valid || !io.dcache.resp.valid
  // Start the PC at it's declared start value minus 4 bytes
  val pc = RegInit(Const.PC_START.U(conf.xlen.W) - 4.U(conf.xlen.W))
  // Next Program Counter (where to find the next instruction)
  val next_pc = MuxCase(
    pc + 4.U,                                                                                 // Default is increment by 4 (PC_4)
    IndexedSeq(
      stall -> pc,                                                                            // If stalling do not increment
      csr.io.expt -> csr.io.evec,                                                             // If CSR exception
      (io.ctrl.pc_sel === PC_EPC) -> csr.io.epc,                                              // Only the ERET instruction (?)
      ((io.ctrl.pc_sel === PC_ALU) || (brCond.io.taken)) -> (alu.io.sum >> 1.U << 1.U),       // ALU computes next instruction location, used in case of a branch (aligned to double byte)
      (io.ctrl.pc_sel === PC_0) -> pc                                                         // Some instructions incremement by 0 because they might stall 
    )
  )
  // Fetch the instruction
  // If any of the 4 conditions is true, the next instruction is no-op (NOP)
  // Otherwise the next instruction is the result of the instruction cache
  // The conditions are just started, an instruction kill as determined by Control, a branch was taken, or a CSR exception
  val inst =
    Mux(started || io.ctrl.inst_kill || brCond.io.taken || csr.io.expt, Instructions.NOP, io.icache.resp.bits.data)
  pc := next_pc
  // Give the next PC as input to the instruction cache
  io.icache.req.bits.addr := next_pc
  io.icache.req.bits.data := 0.U
  io.icache.req.bits.mask := 0.U
  io.icache.req.valid := !stall
  io.icache.abort := false.B

  // Pipelining – If not stalling, pass the results of this stage to the next stage
  when(!stall) {
    fe_reg.pc := pc
    fe_reg.inst := inst
  }

  /***** Execute *****/
  io.ctrl.inst := fe_reg.inst

  // regFile read
  // Get the destination and 2x source addresses from the correct instruction bits
  val rd_addr = fe_reg.inst(11, 7)
  val rs1_addr = fe_reg.inst(19, 15)
  val rs2_addr = fe_reg.inst(24, 20)
  // Feed these as inputs to the register file
  regFile.io.raddr1 := rs1_addr
  regFile.io.raddr2 := rs2_addr

  // gen immdeates
  // ???
  immGen.io.inst := fe_reg.inst
  immGen.io.sel := io.ctrl.imm_sel

  // bypass
  // Skip straight to writeback if WB_ALU is on and rshazard is true (???)
  val wb_rd_addr = ew_reg.inst(11, 7)
  val rs1hazard = wb_en && rs1_addr.orR && (rs1_addr === wb_rd_addr)                  // orR is an or reduction, true if any bit is 1. Equivalent to rs1_addr != 0
  val rs2hazard = wb_en && rs2_addr.orR && (rs2_addr === wb_rd_addr)
  val rs1 = Mux(wb_sel === WB_ALU && rs1hazard, ew_reg.alu, regFile.io.rdata1)
  val rs2 = Mux(wb_sel === WB_ALU && rs2hazard, ew_reg.alu, regFile.io.rdata2)

  // ALU operations
  // Feed the correct inputs to the ALU
  // If the instruction is not explicitly using the data from the source registers, 
  //  instead feed the ALU the PC and the immGen output.
  // Recall the ALU may be determing the next PC
  alu.io.A := Mux(io.ctrl.A_sel === A_RS1, rs1, fe_reg.pc)
  alu.io.B := Mux(io.ctrl.B_sel === B_RS2, rs2, immGen.io.out)
  alu.io.alu_op := io.ctrl.alu_op

  // Branch condition calc
  // Feed the necessary inputs to the branch condition decider
  brCond.io.rs1 := rs1
  brCond.io.rs2 := rs2
  brCond.io.br_type := io.ctrl.br_type

  // D$ access
  // Data cache access. 
  // Data cache address is determined by first N (?) - 2 bits of ALU out
  // Offset (byte shift) is determined by last 2 bits of ALU out
  val daddr = Mux(stall, ew_reg.alu, alu.io.sum) >> 2.U << 2.U
  val woffset = (alu.io.sum(1) << 4.U).asUInt | (alu.io.sum(0) << 3.U).asUInt
  io.dcache.req.valid := !stall && (io.ctrl.st_type.orR || io.ctrl.ld_type.orR)
  io.dcache.req.bits.addr := daddr
  io.dcache.req.bits.data := rs2 << woffset
  io.dcache.req.bits.mask := MuxLookup(
    Mux(stall, st_type, io.ctrl.st_type),                     // If stalling, use st_type == 0 == ST_XXX
    "b0000".U,                                                // Default value (ST_XXX)
    Seq(
      ST_SW -> "b1111".U, 
      ST_SH -> ("b11".U << alu.io.sum(1, 0)),
      ST_SB -> ("b1".U << alu.io.sum(1, 0))
    )
  )

  // Pipelining
  // Re-initialize all registers to 0 on reset or exception
  when(reset.asBool || !stall && csr.io.expt) {
    st_type := 0.U
    ld_type := 0.U
    wb_en := false.B
    csr_cmd := 0.U
    illegal := false.B
    pc_check := false.B
  }
  // Normal pipelining, if no exception
  .elsewhen(!stall && !csr.io.expt) {
    ew_reg.pc := fe_reg.pc                                                  // Forward PC and instr
    ew_reg.inst := fe_reg.inst
    ew_reg.alu := alu.io.out
    ew_reg.csr_in := Mux(io.ctrl.imm_sel === IMM_Z, immGen.io.out, rs1)
    st_type := io.ctrl.st_type
    ld_type := io.ctrl.ld_type
    wb_sel := io.ctrl.wb_sel
    wb_en := io.ctrl.wb_en
    csr_cmd := io.ctrl.csr_cmd
    illegal := io.ctrl.illegal
    pc_check := io.ctrl.pc_sel === PC_ALU
  }

  // Load
  // Last two bits of ALU output are again the byte offset
  val loffset = (ew_reg.alu(1) << 4.U).asUInt | (ew_reg.alu(0) << 3.U).asUInt
  val lshift = io.dcache.resp.bits.data >> loffset
  val load = MuxLookup(
    ld_type,
    io.dcache.resp.bits.data.zext,
    Seq(
      LD_LH -> lshift(15, 0).asSInt,
      LD_LB -> lshift(7, 0).asSInt,
      LD_LHU -> lshift(15, 0).zext,
      LD_LBU -> lshift(7, 0).zext
    )
  )

  // CSR access
  csr.io.stall := stall
  csr.io.in := ew_reg.csr_in
  csr.io.cmd := csr_cmd
  csr.io.inst := ew_reg.inst
  csr.io.pc := ew_reg.pc
  csr.io.addr := ew_reg.alu
  csr.io.illegal := illegal
  csr.io.pc_check := pc_check
  csr.io.ld_type := ld_type
  csr.io.st_type := st_type
  io.host <> csr.io.host

  // Regfile Write
  val regWrite =
    MuxLookup(
      wb_sel,                                     // Select data to write based on WB_SEL
      ew_reg.alu.zext,                            // Default is to 0-extend (WB_ALU)
      Seq(
        WB_MEM -> load, 
        WB_PC4 -> (ew_reg.pc + 4.U).zext, 
        WB_CSR -> csr.io.out.zext
      )
    ).asUInt

  // Inputs to the register file
  regFile.io.wen := wb_en && !stall && !csr.io.expt
  regFile.io.waddr := wb_rd_addr
  regFile.io.wdata := regWrite

  // Abort store when there's an excpetion
  io.dcache.abort := csr.io.expt

  // TODO: re-enable through AOP
//  if (p(Trace)) {
//    printf(
//      "PC: %x, INST: %x, REG[%d] <- %x\n",
//      ew_reg.pc,
//      ew_reg.inst,
//      Mux(regFile.io.wen, wb_rd_addr, 0.U),
//      Mux(regFile.io.wen, regFile.io.wdata, 0.U)
//    )
//  }
}
