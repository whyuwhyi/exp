# ===================== DC Top Script (TSMC N22 / 1 GHz) =====================
# 设计与顶层
set design_name EXPFP32
set design_id 14
set design_top_name $design_name
# ---------- 统一参数（在这里改就行） ----------
# 目标频率 MHz（自动推导周期 ns）
set clk_freq_mhz          1410.0
set constraint_clk_period [expr {1000.0 / $clk_freq_mhz}]   ;# 1GHz -> 1.000 ns
# 1GHz 起步建议（pre-CTS）
set clk_latency_ns                0.10   ;# 时钟延迟占位（network 视为占位）
set clk_uncertainty_setup_ns      0.10   ;# setup 不确定度（含 jitter+skew+margin）
set clk_uncertainty_hold_ns       0.03   ;# hold 不确定度（小）
set clk_transition_ns             0.10   ;# 理想时钟过渡时间占位
# ---------- 工艺库（保持你的路径/名不变） ----------
set target_library  { /pdk/tsmc/n22/CLN22ULP/SC/TSMCHOME/digital/Front_End/timing_power_noise/NLDM/tcbn22ulpbwp7t30p140_100d/tcbn22ulpbwp7t30p140tt0p9v25c.db }
set link_library    { * /pdk/tsmc/n22/CLN22ULP/SC/TSMCHOME/digital/Front_End/timing_power_noise/NLDM/tcbn22ulpbwp7t30p140_100d/tcbn22ulpbwp7t30p140tt0p9v25c.db }
# ---------- 目录 ----------
set workdir dc_work_$design_id
set datadir $workdir/data
set rptdir  $workdir/report
file mkdir $workdir
file mkdir $datadir
file mkdir $rptdir
define_design_lib $workdir -path ./$workdir
set sh_command_log_file $workdir/command.log
set_app_var alib_library_analysis_path $workdir
set_svf $datadir/${design_name}.svf
# （可选）显式单位，避免歧义（保持 ns）
set_units -time ns
# ---------- 读入/综合前 ----------
analyze -work $workdir -format sverilog "../rtl/${design_name}.sv"
elaborate $design_top_name -work $workdir
current_design $design_top_name
uniquify
link
check_design
# ---------- 工艺/线负载 ----------
set_operating_conditions -library tcbn22ulpbwp7t30p140tt0p9v25c tt0p9v25c
set_wire_load_model -name ZeroWireload -library tcbn22ulpbwp7t30p140tt0p9v25c
# ---------- 时钟与时序约束（变量驱动） ----------
create_clock [get_ports clock] -name clock \
  -period $constraint_clk_period \
  -waveform [list 0 [expr {$constraint_clk_period/2.0}]]
# latency（占位/预算）
set_clock_latency $clk_latency_ns [get_clocks clock]
# 过渡时间（rise/fall & min/max）
set_clock_transition -min -fall $clk_transition_ns [get_clocks clock]
set_clock_transition -max -fall $clk_transition_ns [get_clocks clock]
set_clock_transition -max -rise $clk_transition_ns [get_clocks clock]
set_clock_transition -min -rise $clk_transition_ns [get_clocks clock]
# 不确定度（分别设置 setup/hold）
set_clock_uncertainty -setup $clk_uncertainty_setup_ns [get_clocks clock]
set_clock_uncertainty -hold  $clk_uncertainty_hold_ns  [get_clocks clock]
set_dont_touch_network [all_clocks]
set_fix_multiple_port_nets -all -buffer_constants
set verilogout_no_tri true
remove_unconnected_ports [get_cells -hier {*}]
# ---------- 综合与优化 ----------
check_design
compile_ultra
compile -incr -only_design_rule
change_names -rules verilog -hierarchy

# ---------- 详细报告（Power & Area Breakdown） ----------
# ===== 基本报告 =====
report_area > $rptdir/area.rpt
report_timing -delay_type max > $rptdir/timing_setup.rpt
report_timing -delay_type min > $rptdir/timing_hold.rpt
report_power > $rptdir/power.rpt

# ===== Area Breakdown =====
# 按层次结构分解面积
report_area -hierarchy -nosplit > $rptdir/area_hierarchy.rpt
# 按模块类型分解面积  
report_area -designware > $rptdir/area_designware.rpt
# 物理面积分解（包含布线估计）
report_area -physical > $rptdir/area_physical.rpt
# 按组合逻辑和时序逻辑分类
report_area -nosplit > $rptdir/area_detailed.rpt

# ===== Power Breakdown =====
# 按层次结构分解功耗
report_power -hierarchy -levels 3 > $rptdir/power_hierarchy.rpt
# 详细功耗分析（包含开关活动）
report_power -analysis_effort medium -verbose > $rptdir/power_detailed.rpt
# 按单元功耗分解
report_power -cell_power > $rptdir/power_cell.rpt
# 按网络功耗分解
report_power -net_power > $rptdir/power_net.rpt
# 按时钟域分解功耗
report_power -clock_network > $rptdir/power_clock.rpt

# ===== 资源利用率报告 =====
# 按资源类型分解
report_resources > $rptdir/resources.rpt
# 按引用计数排序的单元报告
report_reference -hierarchy > $rptdir/reference.rpt

# ===== QoR（Quality of Results）汇总 =====
report_qor > $rptdir/qor_summary.rpt
report_constraints -all_violators > $rptdir/constraints_violations.rpt

# ===== 自定义汇总报告 =====
# 创建一个汇总文件包含关键指标
set summary_file [open "$rptdir/breakdown_summary.rpt" w]
puts $summary_file "==============================================="
puts $summary_file "Design: $design_name"
puts $summary_file "Frequency: ${clk_freq_mhz} MHz"
puts $summary_file "==============================================="

# 获取总面积
set total_area [get_attribute [current_design] area]
puts $summary_file "Total Area: $total_area um^2"

# 获取总功耗
set total_power [get_attribute [current_design] total_power]
puts $summary_file "Total Power: $total_power mW"

# 获取各层次模块的面积和功耗
puts $summary_file "\n--- Hierarchical Breakdown ---"
foreach_in_collection hier_cell [get_cells -hier *] {
    set cell_name [get_object_name $hier_cell]
    set cell_area [get_attribute $hier_cell area]
    set cell_power [get_attribute $hier_cell total_power]
    if {$cell_area > 0.01} {  # 只显示面积大于0.01的模块
        puts $summary_file "Module: $cell_name, Area: $cell_area um^2, Power: $cell_power mW"
    }
}

close $summary_file

# ---------- 导出 ----------
write -hierarchy -format ddc     -output $datadir/$design_name.ddc
write -hierarchy -format verilog -output $datadir/${design_name}_post.v
write_sdf $datadir/${design_name}_dc.sdf

# ===================== End =====================
