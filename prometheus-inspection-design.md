# Prometheus 智能巡检系统设计

## 总体架构建议：MCP Server + Skill 双轨

**MCP Server** 负责"计算与数据" — 连接 Prometheus、做统计分析、返回结构化异常数据。
**Skill** 负责"思考与方法论" — 教 AI Agent 如何巡检、怎么判异常、如何关联分析、怎么写报告。

```
┌─────────────────────────────────────────────────────┐
│                   AI Agent (LLM)                      │
│  ┌─────────────────────────────────────────────────┐ │
│  │          Prometheus 巡检 Skill (.md)             │ │
│  │  (分析框架 / 异常阈值 / 关联规则 / 报告模板)     │ │
│  └──────────────┬──────────────────────────────────┘ │
└─────────────────┼────────────────────────────────────┘
                  │ MCP Protocol
┌─────────────────┴────────────────────────────────────┐
│            Prometheus MCP Server (Python)             │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────┐ │
│  │  Raw     │ │  Baseline│ │ Anomaly  │ │Correlate│ │
│  │  Query   │ │  Engine  │ │ Detector │ │ Engine  │ │
│  └──────────┘ └──────────┘ └──────────┘ └─────────┘ │
│  ┌──────────────────────────────────────────────────┐ │
│  │       Cacher + Rate Limiter (抗高QPS)           │ │
│  └──────────────────────────────────────────────────┘ │
└──────────────────────┬───────────────────────────────┘
                       │ HTTP
┌──────────────────────┴───────────────────────────────┐
│               Prometheus Server                       │
└──────────────────────────────────────────────────────┘
```

---

## 一、MCP Server 详细设计

### 暴露的工具列表

```python
# ============ 工具1: 原始查询 ============
@mcp.tool()
def query_prometheus(
    query: str, 
    time: Optional[str] = None
) -> QueryResult

@mcp.tool()
def query_range_prometheus(
    query: str,
    start: str,  # "2024-01-01T00:00:00Z" or "-24h"
    end: str,
    step: str = "15s"
) -> TimeseriesResult

# ============ 工具2: 核心分析 ============
@mcp.tool()
def analyze_metric(
    query: str,
    lookback_days: int = 7,           # 基线窗口
    anomaly_method: str = "zscore",   # zscore | iqr | deviation | baseline
    sensitivity: float = 2.0,         # z-score阈值
    start: str = "-24h",
    end: str = "now",
    step: str = "5m"
) -> AnalysisReport
# 返回: 异常时间点列表 + 统计摘要 + 严重级别

# ============ 工具3: 全量巡检 ============
@mcp.tool()
def run_inspection(
    metric_patterns: List[str],       # ["node_*", "container_*", ...]
    methodologies: List[str],          # ["zscore", "rate", "level_shift"]
    severity_threshold: str = "warning", # info | warning | critical
    start: str = "-24h",
    end: str = "now"
) -> InspectionReport
# 返回: 所有异常指标分类汇总

# ============ 工具4: 关联分析 ============
@mcp.tool()
def correlate_anomaly(
    metric_name: str,
    start: str,
    end: str,
    scope: str = "related"  # 按label/service/namespace关联
) -> CorrelationResult
# 返回: 可能的上下游指标变化、原因分析线索

# ============ 工具5: 获取指标目录 ============
@mcp.tool()
def list_available_metrics(
    pattern: str = ".*",
    category: str = "all"  # all | counter | gauge | histogram
) -> List[MetricMeta]

# ============ 工具6: 获取历史基线 ============
@mcp.tool()
def get_baseline(
    query: str,
    baseline_period: str = "7d",    # 对比周期
    time_window: str = "30m",       # 时间对齐窗口(±15min)
) -> BaselineResult
```

### 核心分析算法(MCP Server 内实现)

```python
# ========== 方法一: Z-Score (基于历史基线) ==========
class ZScoreDetector:
    """
    核心思想: 同天同时间段比较
    比如要判断今天10:00是否异常，看过去N天10:00的数据分布
    处理周期性规律 (白天高/晚上低)
    """
    def analyze(self, metric: str, current_ts: datetime) -> AnomalyScore:
        # 1. 获取过去N天 同时段 数据 (当前时间 ±30min 窗口)
        historical = self.query_range(
            metric,
            start = current_ts - timedelta(days=N) - timedelta(minutes=30),
            end   = current_ts + timedelta(minutes=30),
            step  = "1m"
        )
        # 2. 剔除NaN, 计算均值与标准差
        mean = np.nanmean(historical.values)
        std  = np.nanstd(historical.values)
        # 3. 计算当前值的Z-Score
        z = (current_value - mean) / (std + 1e-10)  # 防除零
        # 4. 分级
        if abs(z) >= 3:   return CRITICAL
        if abs(z) >= 2:   return WARNING
        return NORMAL
    """
    Z-Score分级依据:
    |Z| < 2   → 正常 (95% 落在2σ内)
    2 < |Z| < 3 → 警告 (约4.6%概率)
    |Z| > 3   → 严重 (约0.3%概率)
    """

# ========== 方法二: 同比环比 (Baseline Shift) ==========
class BaselineShiftDetector:
    """
    适用: 有稳定周期但均值会缓慢漂移的指标
    今天均值 vs 上周同比均值
    比如: 每周一业务量就是比周二高, 不能误报
    """
    def detect(self, query: str, current_period: tuple, baseline_period: tuple):
        current  = self.avg(query, *current_period)
        baseline = self.avg(query, *baseline_period)
        deviation = (current - baseline) / baseline * 100
        return Anomaly(deviation_pct=deviation, severity=self._rate(deviation))

# ========== 方法三: 速率突变 (Rate Spike) ==========
class RateSpikeDetector:
    """
    适用: Counter类型 (requests_total, errors_total)
    计算 rate()[5m], 和历史rate分布比较
    突然飙升或骤降 → 异常
    """
    def detect(self, counter_query: str, lookback: str = "1h"):
        current_rate = self.query(f"rate({counter_query}[5m])")
        hist_rates = self.query_range(f"rate({counter_query}[5m])", start=f"-{lookback}")
        # 用 MAD (中位数绝对偏差) 抗噪
        median = np.median(hist_rates)
        mad = np.median(np.abs(hist_rates - median))
        modified_z = 0.6745 * (current_rate - median) / (mad + 1e-10)
        return modified_z

# ========== 方法四: 水平偏移 (Level Shift) ==========
class LevelShiftDetector:
    """
    适用: Gauge类型 (memory, cpu)
    短时均值 vs 长时均值, 差值持续异常 → 异常
    """
    def detect(self, query):
        short_avg = self.avg(query, window="5m")  # 短期滑动平均
        long_avg  = self.avg(query, window="1h")  # 长期滑动平均
        shift = abs(short_avg - long_avg) / long_avg
        duration = self.consecutive_anomaly_minutes(query, threshold=shift > 0.2)
        return Anomaly(shift_pct=shift, duration_min=duration)
```

---

## 二、Skill 文件核心内容设计

Skill 应该定义 AI **如何思考 + 用什么方法论分析**，而不是重复MCP的功能。

```markdown
# Prometheus 巡检 Skill

## 1. 指标分类体系 (USE + RED 方法)

在进行异常检测前，先对指标进行分类，不同类别使用不同阈值：

| 分类 | 例子 | 默认阈值 | 检测方法 |
|------|------|---------|---------|
| **U**tilization(饱和度) | CPU, Memory, Disk, Network | >85% WARNING, >95% CRITICAL | Z-Score + Level Shift |
| **S**aturation(饱和) | Queue Length, Drops | >0 持续>5m | Rate Spike |
| **E**rrors(错误) | 5xx, error count | >1% of total | Rate Spike + Baseline |
| **R**equests(流量) | QPS, RPS | 同比波动>50% | Baseline Shift |
| **D**uration(延迟) | P50/P95/P99 Latency | P95>500ms| Z-Score |

## 2. 异常判定优先级

```
先判定 -> 是否完全宕机 | 完全不可用
  └-> 无 -> 判定 -> 错误率飙升
     └-> 无 -> 判定 -> 延迟恶化
        └-> 无 -> 判定 -> 资源饱和(CPU/内存/磁盘)
           └-> 无 -> 判定 -> 流量异常
              └-> 无 -> ✅ 一切正常
```

## 3. 多维度异常认定标准

### 3.1 时间维度
- **瞬时尖刺**(<5min): 标记为 Notice, 不告警
- **持续异常**(>15min): 标记为 Warning
- **长期漂移**(>1h): 标记为 Critical, 需要根因分析

### 3.2 严重程度矩阵

| 指标类型 | 轻度异常(1σ-2σ) | 中度异常(2σ-3σ) | 严重异常(>3σ) |
|---------|----------------|----------------|-------------|
| CPU | >80% | >90% | >95% |
| 内存 | >80% | >90% | >95% |
| P99延迟 | >200ms | >500ms | >2s |
| 错误率 | >0.1% | >1% | >5% |
| QPS | ±30% | ±50% | ±80% |

## 4. 巡检执行工作流

```
Step 1: 获取所有管理的指标清单
  -> list_available_metrics(pattern="*")

Step 2: 筛选关键指标 (排除明显不重要的)
  -> 保留: node_*, container_*, prometheus_http_*, 业务metrics
  -> 排除: go_*, process_* (运行时指标通常无业务意义)

Step 3: 批量分析异常
  -> run_inspection(methodologies=["zscore", "ratespike"], severity="warning")

Step 4: 对发现的每个异常进行根因关联
  -> correlate_anomaly(metric_name)

Step 5: 生成巡检报告
  -> 格式见下方模板
```

## 5. 关联分析规则

当发现指标A异常时，按以下顺序排查：

```
延迟升高(P99↑)
  ├── CPU是否饱和? -> 扩容
  ├── 错误率是否上升? -> 排查bug
  ├── QPS是否突增? -> 限流
  └── 慢查询/GC? -> 优化代码

CPU飙升
  ├── 哪个进程? -> 容器级别区分
  ├── 是否伴随OOM? -> 内存泄漏
  └── 定时任务? -> cron触发

内存上涨
  ├── 持续上涨不释放 -> 内存泄漏
  ├── 瞬涨后平缓 -> 业务高峰
  └── OOM频繁 -> 需要扩容
```

## 6. 巡检报告模板 (AI输出格式)

```
# Prometheus 巡检报告 (过去24h)
**巡检时间**: {time}
**异常指标数**: {critical_count} Critical + {warning_count} Warning

## 异常列表（按严重程度排序）

### Critical
| 指标 | 当前值 | 基线值 | 偏差 | 持续时间 | 推荐操作 |
|------|--------|--------|------|---------|---------|
| {metric}| {val}| {baseline}| {dev}%| {duration}| {action}|

### Warning
...

## 根因分析
- 发现 {X} 和 {Y} 同时异常 → 推断可能原因是 {Z}

## 趋势总结
- CPU在过去24h呈 {上升/下降/稳定} 趋势
- 流量在 {时间段} 达到峰值

## 建议
1. ...
2. ...
```
```

---

## 三、完整巡检工作流 (AI Agent 执行)

```
用户: "巡检过去24小时所有服务的异常指标"

AI Agent:

1. [Load Skill] 加载Prometheus巡检Skill
   → 获得方法论、阈值、流程

2. [MCP] list_available_metrics("node_*")
   → 返回所有node_exporter指标

3. [MCP] run_inspection(metric_patterns=["node_*"], start="-24h")
   → 返回异常指标列表:
     [
       {metric: "node_cpu_utilization", host: "web-01", 
        severity: CRITICAL, z_score: 4.2, current: 97%, baseline: 45%,
        duration: "32min"},
       {metric: "node_memory_MemAvailable", host: "web-01",
        severity: WARNING, z_score: 2.5, current: 12%, baseline: 35%,
        duration: "15min"},
       ...
     ]

4. [MCP] correlate_anomaly("node_cpu_utilization{host=web-01}")
   → 返回:
     - 关联的QPS从 1000→5000 (5x增长)
     - 关联的nginx_5xx在上升
     - web-02 CPU正常

5. [AI分析] 根据Skill中的关联规则:
   "CPU飙升 + QPS突增 + 同集群其他节点正常" 
   → 推断: web-01流量分配不均或被DDoS
   → 建议: 检查负载均衡策略, 考虑限流

6. [AI输出] 生成巡检报告
```

---

## 四、如何定义"异常"（核心方法论总结）

### 我的推荐策略：分层判定

```
第一层: 硬性阈值 (Hard Threshold)
  作用: 兜底，防止统计方法漏报
  规则: CPU > 95% 持续5min → CRITICAL (不管基线)
        错误率 > 5% → CRITICAL

第二层: 统计异常 (Statistical Anomaly)
  作用: 发现非预期变化
  规则: Z-Score > 3 (默认2σ-3σ可配置)
        同比偏差 > 50%

第三层: 趋势异常 (Trend Anomaly) 
  作用: 发现缓慢劣化
  规则: 连续N个周期均值持续偏移
        斜率持续为负/正超过阈值

最终判定逻辑:
  if 硬性阈值触发:
      return CRITICAL (无需统计)
  elif Z-score > 3:
      if 持续时间 > 15min:
          return CRITICAL
      else:
          return WARNING
  elif 趋势偏移 > 2周期:
      return WARNING
  else:
      return NORMAL
```

---

## 五、性能优化方案（应对大量指标）

| 场景 | 优化策略 |
|------|---------|
| 监控1000+指标 | MCP Server内做批量查询，一次 `query_range` 拉取所有数据，内存计算 |
| 24h数据量大 | 分层下采样: 24h数据用5m步长，P99/P95聚合 |
| 频繁巡检 | 基线结果缓存(Redis)，基线每1h更新一次，不重复拉取 |
| 多用户并发 | MCP无状态，PromQL查询本身轻量，Prometheus单机可抗2w+ QPS |

### MCP Server 缓存策略

```python
class BaselineCache:
    """
    基线数据7天不变，不用每次重新查
    """
    def get_baseline(self, metric: str, window: str) -> Baseline:
        key = f"baseline:{metric}:{window}"
        cached = redis.get(key)
        if cached:
            return cached
        baseline = self._compute_baseline(metric, window)
        redis.setex(key, 3600, baseline)  # 1h过期
        return baseline
```

---

## 总结建议

| 组件 | 做什么 | 不做什么 |
|------|--------|---------|
| **MCP Server** | 连接Prometheus、计算统计量、Cache、返回结构化数据 | 不做业务决策、不做根因推理 |
| **Skill** | 定义分析框架、阈值体系、关联规则、报告模板 | 不写代码、不连数据库 |

**核心原则**: Skill 给 AI 装了"脑子"，MCP Server 给了 AI "手和眼"。AI 利用 Skill 中的方法论，调用 MCP 工具，完成从数据采集 → 异常检测 → 根因分析 → 报告生成的全流程。
