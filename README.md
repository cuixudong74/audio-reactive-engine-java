# audio-reactive-engine-java

流式音频分析与反应引擎：从本地回环或内存管道接收分块 PCM 采样流，持续输出
频谱帧（`SpectrumFrame`）、节拍/起音事件（`BeatEvent`）与间隙通知
（`GapNotice`），供可视化与节奏互动等上层应用消费。整条链路纯本地、无外部
服务依赖，`mvn test` 即可复现全部行为。

## 快速开始

```java
AnalysisConfig config = AnalysisConfig.defaults();
try (AudioAnalysisEngine engine = new AudioAnalysisEngine(config)) {
    engine.start();
    engine.submit(new SampleChunk("loopback", 0, 44100, 2, interleavedSamples));
    List<EngineEvent> events = engine.poll(100, TimeUnit.MILLISECONDS);
}
```

Spring Boot 应用中引擎以 Bean 形式提供（见 `EngineConfiguration`），所有参数
可通过 `application.properties` 的 `audio.analysis.*` 配置。

## 分析模型

处理管线（单工作线程，全链路确定性）：

```
SampleChunk → 声道混合 → 重组(去重/排序/间隙) → 重采样 → 分帧+加窗+FFT → 频谱帧
                                                              └→ 谱通量起音检测 → 节拍事件
```

1. **声道混合**：多声道按固定顺序（ch0..n-1）以双精度求算术平均混为单声道。
2. **重组**：按绝对帧号重组，重复与重叠部分被裁剪丢弃，乱序块进入有界缓冲。
3. **重采样**：线性插值。输出第 k 个采样位于源位置 `k * srcRate / dstRate`，
   位置由整数计数器计算（非累加），因此任意分块切分结果逐位一致。
4. **分帧**：第 k 帧起始于流绝对位置 `k * hopSize`，`hopSize =
   round(windowSize * (1 - overlapRatio))`。只有窗口被完全填满才输出帧；
   尾部不足一个窗口的采样被保留，绝不输出半帧。
5. **加窗 + FFT**：对称窗 `w[n] = f(n/(N-1))`，基 2 迭代 FFT（double 精度、
   固定运算顺序），输出 `windowSize/2 + 1` 个幅度 bin。RMS 能量在加窗后的
   帧上计算。
6. **起音/节拍检测**：谱通量模型——相邻帧对数幅度正差按 bin 求和为 flux；
   当 `flux > max(onsetSensitivity * mean(最近32帧flux), onsetFluxFloor)` 时
   触发事件。`strength = flux / threshold`，`strength >= strongBeatRatio`
   为 `BEAT`（强拍），否则为 `ONSET`（弱起音）。

### 误报抑制

- **静音门**：RMS 低于 `silenceThresholdRms` 的帧绝不产生事件，纯静音与
  低于阈值的噪声不会触发。
- **自适应阈值**：阈值跟随最近 32 帧的局部能量动态，稳态信号与稳态噪声
  （flux 围绕均值波动）不会触发；`onsetSensitivity` 是漏检/误报的取舍旋钮
  （调大 → 误报少、漏检多）。
- **阈值下限**：`onsetFluxFloor` 保证从全静音中开始的起音也能触发。
- **不应期**：一次起音后 `minOnsetIntervalMs` 内不再报告新起音。
- **对数压缩**：log 幅度压缩使稳态宽带噪声不会主导通量。

## 配置项（`AnalysisConfig`）

| 参数 | 默认 | 说明 |
|---|---|---|
| `targetSampleRate` | 44100 | 分析采样率，输入被确定性地重采样到该速率 |
| `windowSize` | 1024 | FFT 窗口（必须为 2 的幂） |
| `windowType` | HANN | RECTANGLE / HANN / HAMMING / BLACKMAN |
| `overlapRatio` | 0.5 | 相邻窗口重叠比例，[0, 0.9] |
| `silenceThresholdRms` | 1e-4 | 静音判定 RMS 阈值 |
| `onsetSensitivity` | 1.5 | 自适应阈值倍率（漏检/误报取舍） |
| `onsetFluxFloor` | 1.0 | 起音阈值绝对下限 |
| `strongBeatRatio` | 2.0 | 强拍/弱起音分界（按 strength） |
| `minOnsetIntervalMs` | 60 | 起音不应期 |
| `gapPolicy` | FILL_SILENCE | 间隙策略：FILL_SILENCE / SKIP |
| `maxReorderFrames` | 2^20 | 乱序缓冲上界（帧），超过即声明间隙 |
| `inputQueueCapacity` | 256 | 输入队列容量（背压） |
| `outputQueueCapacity` | 1024 | 输出队列容量（背压） |

队列容量与 `maxReorderFrames` 是结构性参数，构造时固定；其余参数均支持
热更新。

## 一致性语义

- **分块无关**：输出只取决于逻辑采样序列（绝对帧号），与分块大小、投递
  顺序、重复次数无关。同一段音频任意切分、乱序、重复投递，输出事件逐条
  一致（有自动化测试保证）。
- **确定性**：固定输入 + 固定配置 → 输出逐条一致且可复现（所有浮点运算
  顺序固定，无时间依赖、无哈希序依赖）。
- **时间轴单调**：输出事件时间戳单调不减。输入时间戳回退（重复/重叠块）
  被裁剪；前向跳变按 `gapPolicy` 处理：
  - `FILL_SILENCE`：缺失段补零，时间轴与源时间轴保持对齐；
  - `SKIP`：缺失段被压缩掉，时间轴按实际收到的采样推进。
  两种策略都会先输出 `GapNotice`（携带缺失帧数）。为保持单调，
  `GapNotice` 的时间戳取下一帧的起始时刻。补零长度以
  `maxReorderFrames` 为上界（超出部分按 SKIP 处理），保证内存有界。
- **窗口边界**：首帧从流位置 0 开始；非矩形窗两端系数为 0（Hamming 为
  0.08），边界采样对频谱无贡献或贡献固定；不足一窗的尾部采样被保留不
  输出。
- **原子性**：事件以不可变对象整体入队，取消、超时、关闭都不会产生半个
  事件；所有状态迁移只发生在命令边界，分析状态不会停在中间态。
- **背压**：输入/输出均为有界队列。消费端变慢 → 输出队列满 → 工作线程
  阻塞 → 输入队列满 → `submit` 返回 `false`（立即、不阻塞）。内存占用
  由配置封顶；处理延迟由队列容量与单帧处理时间上界决定。
- **热更新**：`updateConfig` 返回新版本号；新配置对**之后提交**的采样
  生效，已产出的事件保留原版本号且永不改写。每个事件携带
  `configVersion`，可据此解释任意配置的生效范围。切换时帧计数器、绝对
  采样位置与未消费采样被携带到新分析器，时间轴不跳变、不倒流；检测器
  的自适应阈值历史保留（窗口大小变化时频谱记忆重置）。
- **重启**：`reset()` 清空全部分析状态并丢弃未消费的输出事件，时间轴
  归零重启；同一输入在 reset 后重放会得到与首次完全一致的事件序列，
  不会出现重复或残留事件。

## 测试

`mvn test` 覆盖：乱序、重复、重叠、丢帧（间隙）、分块切分一致性、
多声道混合一致性、重采样分块一致性、FFT/窗函数确定性、静音与稳态噪声
不误报、强拍/弱起音区分、不应期、背压有界、热更新生效范围、重启复现、
关闭原子性。
