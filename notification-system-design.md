# 通知系统架构设计

## 核心痛点分析：电话通知慢的问题

关键解决思路：**异步 + 回调 + 状态机**，所有通知通道统一抽象为异步模型。

## 1. 分层架构

```
┌─────────────────────────────────────────┐
│            API Layer (Controller)        │
├─────────────────────────────────────────┤
│          Notification Service           │
├─────────────────────────────────────────┤
│  Dispatcher  │  EscalationMgr  │  Retry  │
├──────────────┴──────────────────────────┤
│         Event Bus (Async Queue)          │
├──────┬──────┬──────┬────────────────────┤
│Email │ SMS  │ Phone│  AckHandler (Web)  │
│Handler│Handler│Handler│                   │
└──────┴──────┴──────┴────────────────────┘
```

## 2. 核心表设计

```sql
-- 通知任务
notification_task (
  id, title, creator, status, current_level,
  created_at, updated_at
)

-- 通知层级
notification_level (
  id, task_id, level_order, retry_count, retry_interval_sec,
  escalation_time_sec, min_ack_count, status
)

-- 通知群组
notification_group (
  id, level_id, group_name
)

-- 群组用户
group_user (
  id, group_id, user_id, user_name, phone, email
)

-- 群组渠道配置 (哪些通知方式)
group_channel_config (
  id, group_id, channel_type  -- EMAIL/SMS/PHONE
)

-- 通知记录 (每个用户 x 每个渠道 一条)
notification_record (
  id, task_id, level_id, group_id, user_id,
  channel_type, status, retry_times, next_retry_at,
  sent_at, ack_at, fail_reason, version
)

-- 升级记录
escalation_record (
  id, task_id, from_level, to_level, escalated_at
)
```

## 3. 核心组件伪代码

### 事件模型：统一异步事件

```java
// 统一事件
public class NotificationEvent {
    Long recordId;
    EventType type;  // SEND, SENT, FAILED, RETRY, ESCALATE, ACK
}

// 事件总线
@Component
public class NotificationEventBus {
    private final AsyncEventBus eventBus; // Guava / Spring Async

    public void publish(NotificationEvent event) {
        eventBus.post(event);
    }
}
```

### 发送任务：完全异步

```java
@Service
public class NotificationDispatcher {
    
    public void dispatchTask(Long taskId) {
        NotificationTask task = taskRepo.findById(taskId);
        NotificationLevel level = getCurrentLevel(task);
        List<NotificationRecord> records = buildRecords(task, level);
        
        // 批量创建记录，全部异步发送
        for (NotificationRecord record : records) {
            recordRepo.save(record);
            // 立即发布发送事件，不等待结果
            eventBus.publish(new NotificationEvent(record.getId(), SEND));
        }
        
        // 启动升级定时监控
        escalationScheduler.monitor(task, level);
    }
    
    // 订阅 SEND 事件
    @Subscribe
    public void handleSend(NotificationEvent event) {
        NotificationRecord record = recordRepo.findById(event.getRecordId());
        ChannelHandler handler = handlerFactory.get(record.getChannelType());
        // 异步发送，不阻塞 - 通过CompletableFuture/回调
        handler.sendAsync(record, result -> {
            if (result.isSuccess()) {
                recordRepo.updateStatus(record.getId(), SUCCESS);
            } else {
                recordRepo.updateStatus(record.getId(), FAILED);
                // 触发重试
                eventBus.publish(new NotificationEvent(record.getId(), RETRY));
            }
        });
    }
}
```

### PhoneHandler：回调模式解决慢问题

```java
@Component
public class PhoneHandler implements ChannelHandler {
    
    @Override
    public void sendAsync(NotificationRecord record, Callback callback) {
        // 1. 立即返回，不阻塞
        phoneService.callAsync(record.getPhone(), record.getMessage())
            .onComplete((callResult) -> {
                // 2. 电话打完回调（可能几分钟后）
                callback.onResult(new SendResult(callResult.isSuccess()));
            });
        
        // 或者推送到消息队列，由电话服务消费
        // rabbitTemplate.convertAndSend("phone.call.queue", record);
    }
}

// 电话服务回调接口
@RestController
@RequestMapping("/callback")
public class PhoneCallbackController {
    
    @PostMapping("/phone/{callId}")
    public void phoneCallback(@PathVariable String callId, @RequestBody CallbackResult result) {
        NotificationRecord record = recordRepo.findByCallId(callId);
        // 直接更新状态 + 触发后续流程
        recordRepo.updateStatus(record.getId(), result.isSuccess() ? SUCCESS : FAILED);
        eventBus.publish(new NotificationEvent(record.getId(), result.isSuccess() ? SENT : FAILED));
    }
}
```

### 重试机制：定时扫描 + 时间轮

```java
@Component
public class RetryScheduler {
    
    // 每分钟扫描需要重试的记录
    @Scheduled(fixedRate = 60_000)
    public void processRetries() {
        List<NotificationRecord> pendingRetry = 
            recordRepo.findByStatusAndNextRetryAtBefore(FAILED, LocalDateTime.now());
        
        for (NotificationRecord record : pendingRetry) {
            NotificationLevel level = levelRepo.findById(record.getLevelId());
            
            if (record.getRetryTimes() >= level.getRetryCount()) {
                recordRepo.updateStatus(record.getId(), RETRY_EXHAUSTED);
                continue; // 重试用尽，记录最终失败
            }
            
            // 更新重试次数 + 下次重试时间
            recordRepo.incrementRetry(record.getId(), 
                LocalDateTime.now().plusSeconds(level.getRetryIntervalSec()));
            
            // 重新发送
            eventBus.publish(new NotificationEvent(record.getId(), SEND));
        }
    }
}
```

### 升级（Escalation）引擎

```java
@Component
public class EscalationScheduler {
    
    public void monitor(NotificationTask task, NotificationLevel level) {
        // 延迟 escalation_time_sec 后检查
        ScheduledFuture<?> future = taskScheduler.schedule(
            () -> checkEscalation(task, level),
            Instant.now().plusSeconds(level.getEscalationTimeSec())
        );
    }
    
    private void checkEscalation(NotificationTask task, NotificationLevel level) {
        long ackCount = recordRepo.countAckByLevel(task.getId(), level.getId());
        long totalUsers = recordRepo.countUsersByLevel(task.getId(), level.getId());
        
        if (ackCount >= level.getMinAckCount()) {
            // 应答足够，流程结束
            taskRepo.updateStatus(task.getId(), COMPLETED);
            return;
        }
        
        // 未达最低应答人数，升级
        NotificationLevel nextLevel = levelRepo.findNextLevel(task.getId(), level.getLevelOrder());
        if (nextLevel != null) {
            // 创建升级记录，触发下一级通知
            escalationRepo.save(new EscalationRecord(task.getId(), level.getId(), nextLevel.getId()));
            taskRepo.updateCurrentLevel(task.getId(), nextLevel.getId());
            dispatcher.dispatchTask(task.getId());  // 重新走下一级
        } else {
            taskRepo.updateStatus(task.getId(), ESCALATION_EXHAUSTED);
        }
    }
}
```

### Web 应答

```java
@RestController
@RequestMapping("/api/notification")
public class AckController {
    
    @PostMapping("/ack/{recordId}")
    public void acknowledge(@PathVariable Long recordId, @RequestBody AckRequest request) {
        NotificationRecord record = recordRepo.findById(recordId);
        if (record.getStatus() == SENT || record.getStatus() == SUCCESS) {
            recordRepo.ack(recordId, LocalDateTime.now());
            eventBus.publish(new NotificationEvent(recordId, ACK));
        }
    }
}
```

### Spring 配置

```java
@Configuration
@EnableAsync
@EnableScheduling
public class NotificationConfig {
    
    @Bean
    public AsyncEventBus notificationEventBus(NotificationDispatcher dispatcher) {
        AsyncEventBus eventBus = new AsyncEventBus(Executors.newFixedThreadPool(20));
        eventBus.register(dispatcher);
        eventBus.register(retryHandler);
        eventBus.register(escalationHandler);
        return eventBus;
    }
}
```

---

## 为什么这个设计能解决 QPS 问题

| 传统同步方式 | 异步回调方式 |
|---|---|
| 电话通知阻塞线程等待结果（可能几分钟） | 提交后立即返回（毫秒级） |
| QPS = 线程数 / 平均等待时间 ≈ 非常低 | QPS = 线程数 / 提交耗时 ≈ 非常高 |
| 大量线程被长时间占用 | 少量线程即可处理大量请求 |

**关键点**：所有通知通道统一为 `sendAsync(record, callback)` 接口，就连短信/邮件也使用同样的异步模型，系统对上游永远不阻塞。慢通道（电话）通过回调/消息队列+CQRS解耦，状态更新的延迟完全被业务接受。
