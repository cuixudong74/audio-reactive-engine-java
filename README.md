# Audio Reactive Engine (Java)

流式音频分析与反应引擎：从本地回环 / 内存管道接收分块多声道采样流，持续输出
频谱帧与起音/节拍事件，供可视化与节奏互动等上层应用消费。整条链路纯本地、
零外部服务依赖，可用 `mvn test` 完整复现验证。

## 处理流水线

```
AudioChunk (分块、可乱序/重复/带间隙)
  └─ ChunkReassembler   按绝对帧号去重、重排、修剪；间隙在 finish() 时按策略物化
  └─ 声道混合           多声道 → 单声道，算术平均（确定）
  └─ StreamResampler    线性插值重采样，输出位置只依赖绝对采样序号
  └─ SpectrumAnalyzer   重叠分帧 + 窗函数 + FFT → SpectrumFrame(幅度谱, 能量)
  └─ OnsetDetector      谱通量 + 中位数自适应阈值 → ONSET 事件
  └─ BeatTracker        IOI 中位数估速 + 局部网格对齐 → BEAT 事件
  └─ 有界输出队列        EngineOutput.Frame / EngineOutput.Event（原子单位）
```

## 分析模型

- **频谱**：窗长 `windowSize`（2 的幂），hop = `windowSize × (1 - overlap)`。
  每个窗输出 `windowSize/2 + 1` 个幅度 bin 与总能量。帧时间戳 = 窗起始采样
  位置 / 引擎采样率，严格递增。
- **起音**：谱通量（相邻帧幅度正差之和，按 bin 数归一）超过
  `max(noiseFloor, 历史窗口中位数 × onsetThreshold)` 且为局部极大时触发；
  之后进入 `minOnsetIntervalSeconds` 不应期。中位数阈值对稀疏强起音鲁棒、
  对稳态噪声紧致；另有 8 帧预热期抑制启动误报。静音与稳态噪声不产生事件。
- **强度**：起音强度 = 该峰值通量 / 历史窗口最大通量（≤1），≥0.5 为 STRONG。
- **节拍**：积累 ≥4 个起音后锁定速度（IOI 中位数，钳制在 0.25–2.0 s，
  即 30–240 BPM）；之后若某起音与前一起音的间隔接近节拍周期的整数倍
  （容差 15%），则产生 BEAT 事件；支撑起音强度 ≥0.6 为 STRONG 拍，
  否则为 WEAK 拍。速度锁定前不产生任何节拍，噪声无法伪造节拍网格。

## 配置项

### EngineConfig（一次运行内固定）

| 项 | 含义 |
|---|---|
| `sampleRate` | 引擎（输出）采样率；不同速率的输入被确定性重采样到该速率 |
| `outputQueueCapacity` | 有界输出队列容量（背压边界） |
| `overflowPolicy` | `BLOCK`（阻塞至超时后报错停止）或 `DROP_OLDEST`（丢弃最旧项） |
| `offerTimeout` | `BLOCK` 策略下生产者等待队列空位的最长时间 |
| `gapPolicy` | `ZERO_FILL`（间隙补零）或 `SKIP`（丢弃缺失帧、时间轴压实） |

### AnalysisParams（可热更新）

| 项 | 含义 |
|---|---|
| `windowSize` / `windowType` / `overlap` | 分帧窗长（2 的幂）、窗函数、重叠率 |
| `onsetThreshold` | 自适应阈值倍率：调大 → 少误报多漏检；调小 → 相反 |
| `noiseFloor` | 绝对通量下限，低于该值永不产生事件 |
| `minOnsetIntervalSeconds` | 起音不应期 |

## 一致性语义

1. **分块不变性**：同一段音频无论怎样切分、乱序或重复投递，输出逐条一致
   （测试：`chunkingDoesNotAffectOutput`、`outOfOrderAndDuplicateDeliveryMatchesOneShot`）。
   采样身份由绝对帧号定义，重复帧先写先得、只发射一次。
2. **时间轴不倒流**：与已发射区间重叠的迟到块被修剪或丢弃；帧时间戳与事件
   序列号均单调。
3. **间隙**：间隙只在 `finish()` 时按 `gapPolicy` 物化，与到达顺序无关；
   `finish()` 后到达的块被丢弃。
4. **窗口边界**：窗未被采样完全覆盖时不发射；`finish()` 时对末尾不足一窗的
   采样补零后发射恰好一帧（幂等）。
5. **可复现**：固定输入 + 固定配置 ⇒ 输出逐条一致；`reset()` 开启新运行，
   同输入必得同输出，事件序号从 0 重计，无跨运行泄漏。
6. **背压**：处理在提交线程上内联执行，消费端变慢时有界队列自然回压生产者；
   `BLOCK` 超时后引擎报错停止（`error()` 可见），内存占用有界。
7. **取消/超时原子性**：帧与事件都是原子输出单位，`cancel()` 立即停止且不
   吐出半帧/半条事件；队列中已完成的项仍可被消费，END 标记保证消费者
   总能观察到终止。
8. **热更新**：`updateAnalysisParams` 在下一个帧边界生效——已缓冲未成帧的
   采样按新参数重新分帧，检测器自适应状态重置，节拍记忆保留；已产出的
   帧与事件永不改写。变更点前后帧索引连续、时间轴连续，生效范围可由帧的
   bin 数（窗长）直接观察。

## 构建与测试

```bash
mvn compile   # 编译
mvn test      # 34 个测试：乱序/重复/丢帧/参数变更/重启/背压/取消/一致性
```

## 最小使用示例

```java
EngineConfig config = EngineConfig.defaults(44100);
AudioEngine engine = new AudioEngine(config, AnalysisParams.defaults());

// 生产者线程：分块提交（可乱序、可重复）
engine.submit(new AudioChunk(0, 0, 48000, samples, 2));
engine.finish();

// 消费者线程
EngineOutput item;
while ((item = engine.take()) != null) {
    switch (item) {
        case EngineOutput.Frame f -> render(f.frame().magnitudesRef());
        case EngineOutput.Event e -> flash(e.event());
    }
}
```
