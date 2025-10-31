#import "../../dvdbr3o.typ/src/dvdbr3o.typ": *

#show: dvdbr3otypst.with(
  title: [DCS209 Lab1 \ 实验报告],
  subtitle: [],
  author: [代骏泽],
  xno: [24363012],
  bib: bibliography("reference.bib"),
)

= 实验报告

== 环境配置

参照实验手册，本实验选择了 Windows 下的 Docker 环境方案.

== 任务一：单元测试

=== 正确性测试原理

定义 `with ChiselScalatestTester` 的一个类作为单元测试，其中还 `extends AnyFlatSpec` 来实现 `behavior of` 和 `it should` 的“优雅”单元测试信息声明.

在 `it should` 子句中定义测试 `test` 子句. `test` 是一个 Builder，可以理解为构造函数，接受一个 `Module` 作为参数，最后再接受一个 lambda 回调来定制测试的具体行为. 在最后声明回调前调用一个返回自身的修改函数 `.withAnnotation()` 定制标注 (也是单元测试的信息). 

在回调中，参数是先前构造时用的模块，函数体过程式地声明测试行为. 关键是以下几个操作:

1. `clock.step()` $=>$ 时钟步进.
2. `Input.poke()` $=>$ 向给定 `Input` 输入给定值.
3. `Output.expect()` $=>$ 检查给定 `Output` 是否通过测试.

通过这几个主要函数地搭配，实现 $f(t, i)=o$ 的时序单元测试检查.

=== 波形变化解释



== 任务二：程序原理

=== 程序运行原理

=== 波形变化解释

= 后记

== 遇到困难

=== 环境配置

其实在实验手册以及课程项目 repo 的帮助下，让整个环境 work 起来并不难，毕竟再不济也有 Docker. 但是我想讲的是我一开始尝试在原生 Windows (without WSL/Docker)下搭建环境的尝试.

==== java 相关

首先当然是安装构建系统以及配套所需的 jvm/jre

```powershell
scoop bucket add java
scoop install sbt
scoop install graalvm19-jdk11
```

#info[
  注意 java 版本的选择，测试 jvm21 是无法兼容作业配的环境的，而 jvm19 是可以的

  当然你还可以 `scoop install jenv` 管理 jvm 环境
]

==== verilator 相关

现在尝试运行 test 其实已经大差不差了，至少 java 相关的环境不会报错：

```powershell
cd lab1
sbt test
```

但是会报错找不到 `verilator_bin`：

```
java.io.IOException: Cannot run program "verilator_bin" (in directory "C:\Users\DvdBr3o\devenv\dcs209\lab1"): CreateProcess error=2
```

所以现在只差一个 `verilator` 我们就完事大吉了！但巧也不巧的是，不同于 `iverilog`，`verilator` 恰好不支持原生 Windows build。你在 scoop 上找不到它的 distribution，在它 github repo release 里也找不到 Windows build. #strike[这真是太棒了！]

而当你翻看 `verilator` 的 github repo 时，你会惊讶的发现它竟然是个 all in C++ with CMakeLists.txt... 而当你尝试 clone 下来 build 时发现它还不是那种 "another Makefile" 的 CMake project，它在 Windows 下好像真的可以 build 出来. 事实上，它 repo 的 `./ci` 下还有个 `./ci/ci-win-compile.ps1`. #strike[留个 build script 但不给官方 build release？有点意思.]

然而最后虽然可以 build 出来，但是程序是残缺的. 不仅版本号的 semver 残缺，而且实际运行也会报错. 不知道 verilator 依赖的哪个库发神经还是他自己有问题，竟然不报 Platform Unsupported 而是报一个莫名其妙的内部错误

```
Internal Error: C:\Users\DvdBr3o\devenv\2025-fall-yatcpu-repo\verilator\src\V3Error.cpp:129:Attempted v3errorPrep inside v3errorPrep...v3errorEnd
```

所以这个方式基本破产. 如果 Chisel3 能支持 iverilog (这个是支持 windows 的) 作为 verilog 仿真后端，那么就能实现原生 Windows 下完成作业了. 但我不打算继续深究下去了，懒得翻 Chisel 和 sbt 的手册了，最后还是选择了 Docker，唉.

=== 三个 CPUTest

==== 项目形式不当

见 @suggest 第二条，大概就是采用仓库的形式分发作业框架有一定问题. 一开始三个 CPU Test 全错，但是前面测试全都正确. 后面发现作业仓库有更新，甚至还有 merge 操作，导致在原始版本仓库上做修改后的本地目录完全无法 pull repo，全是 conflict (毕竟有 merge). 后来重新拉取并重写后发现变为错两个，说明一开始的框架实现可能有问题. 

==== Instruction Decode 单元测试欠缺

最后发现在 Instruction Decode 模块下，由于理论课缺失，对 `memory_write` 的含义不敏感不清晰，导致一开始实现是

```scala
io.memory_write_enable := opcode === !(InstructionTypes.L)
```

因为 `InstrcutionDecoderTest.scala` 的实现并未检测所有指令类型的 `io.memory_write_enable`，导致测试通过. 而这会导致之后的实际程序中，当不应该 save to memory 时，硬件实现错误的赋值到了内存中，导致预期内存中的值为空值或者 ALU result 的 fraction. 

在 fibonacci 和 quicksort 的单元测试中一步步溯源 `io.mem_debug_read_address` 依赖的模块端口，先是 `MemoryAccess` 后是 `InstructionDecode`，最后回想起 InstructionDecode 实现中对 `memory_write` 概念的疑惑. 仔细思考只有 L 型指令使得 `memory_read_enable` 为真，然后拍拍脑袋才想起来 *L for load*，那就是只有它*读内存*咯. 与之相对的*写内存*就是 *S for save* 了，于是应该为只有 S 型指令使得 `memory_write_enable` 为真:

```scala
io.memory_write_enable := opcode === InstructionTypes.S
```

然后测试全部通过.

== 课程建议 <suggest>

有点像无病呻吟所以不分小标题了，大概就几句小牢骚:

1. 工具选择. 我不知道 Chisel 在硬件领域这块的权威性，也不知道是不是我们院老师们都有点 jvm 情节 #strike[(提问：为什么没有 lua 情节/ quickjs 情节/python 情节/modern cpp 情节?)]. Chisel 确实比 verilog 语义上开起来更现代更优雅，工具链也相比没那么古老了. 但是它环境真的很难配，java 环境很重，用在 non serving 的硬件验证&综合这块感觉过于笨重了. 特别是嘴上说“增量编译”，实则每次 test 还是要等好久编译，甚至还有不止编译的事情，开发特别慢. 而且环境配置上最后其实基本都靠 docker 一把糊过去. 或许可以选择更好配环境的工具链替代 Chisel.

2. 项目形式. 我第一节课直接 clone 了课程项目仓库 (然而实则 fork 了也一样). 很快填完了 Lab1 的空，test 基本全对，但是 `cpu.io.mem_debug_read_data` 在 Fibonacci, Quicksort 和 ByteAccess 三个 test 中始终为空 `0x00...`. 按理说其他 test 通过说明填空部分实现的 CPU 功能没有问题，于是我一直翻代码试图修改填空部分外的代码. 然而时间有限，没找到哪里有问题. 最后发现课程仓库竟然还有新 commit (甚至还是 yesterday). Even worse 甚至还有 merge，导致我想直接 `git pull --rebase` 时直接冒出一大串冲突... 最终要手动复制填空部分，重新 clone 仓库再粘贴回去，最后甚至还要祈祷老师和助教别再来 commit 了，至少别再来 merge.
