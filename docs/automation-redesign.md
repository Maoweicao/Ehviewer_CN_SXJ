# 自动化任务系统重构设计

> 替代原"定时任务（Scheduled Task）"机制，从单一时间触发升级为"时间 / 事件 / 手动"多触发源。

## 一、设计目标

1. **统一触发模型**：把"定时执行"与"事件触发"合并为同一套"触发器 + 条件 + 动作"模型。
2. **解决现有 Bug**：
   - `handleTaskCompleted()` 无人调用，重复任务永远停在 `RUNNING` 状态。
   - `DownloadListener` 是单字段，后注册会覆盖先注册（`DownloadService` 与 `ScheduledTaskManager` 互相覆盖）。
3. **降低 UI 理解成本**：
   - 废除"任务组"作为独立 UI 实体的概念。
   - 每条任务一张卡片，"When / If / Then"三段式可视化。
4. **可扩展**：新增事件触发器或动作只需注册，不改核心代码。

## 二、命名映射

| 旧 | 新 |
|---|---|
| 定时任务（ScheduledTask） | **自动化任务**（AutomationTask） |
| 延时条件（DelayCondition） | **触发器**（AutomationTrigger） |
| 任务组（TaskGroup） | **取消**，合并进任务的 `executionMode + actions` |
| 任务项 | **动作**（AutomationAction） |
| `ScheduledTaskManager` | `AutomationManager` |
| `ScheduledTaskLogger` | `AutomationLogger` |
| `ScheduledTaskPersistence` | `AutomationPersistence` |
| 设置入口"定时任务" | **自动化任务** |

## 三、架构

```
AutomationManager
├── ScheduleDispatcher          复用 Cron + 30s 轮询
├── EventTriggerDispatcher     订阅所有事件源
├── ConditionEvaluator         WiFi / 充电 / 屏幕 / 时段
├── ActionExecutor             BackgroundTaskManager + 完成回调 + 重试
├── EventTriggerRegistry       事件元数据注册表
├── TaskRegistry               既有动作元数据
├── AutomationPersistence      SharedPreferences + JSON
└── AutomationLogger           复用现有日志
```

## 四、核心模型

```kotlin
sealed class AutomationTrigger : Parcelable {
    data class Schedule(val cron: String?, val repeat: RepeatMode, ...) : AutomationTrigger()
    data class Event(val type: EventTriggerType, val filter: EventFilter? = null) : AutomationTrigger()
    object Manual : AutomationTrigger()
}

enum class EventTriggerType {
    // 下载
    DOWNLOAD_FINISHED, ALL_DOWNLOADS_COMPLETED, DOWNLOAD_FAILED, DOWNLOAD_ADDED,
    // 网络
    NETWORK_AVAILABLE, NETWORK_LOST, WIFI_CONNECTED,
    // 充电
    CHARGING_STARTED, CHARGING_STOPPED,
    // 生命周期
    APP_FOREGROUND, APP_BACKGROUND,
    // 数据变化
    FAVORITE_ADDED, FAVORITE_REMOVED, HISTORY_ADDED, LOCAL_GALLERY_ADDED,
    // 文件 / Intent
    FILE_CREATED, FILE_DELETED, CUSTOM_INTENT_RECEIVED,
}

data class AutomationConditions(
    val requireWifi: Boolean = false,
    val requireNoMetered: Boolean = false,
    val requireCharging: Boolean = false,
    val requireScreenOff: Boolean = false,
    val timeWindow: TimeWindow? = null,
    val weekdayMask: Int = 0,
    val minBatteryPercent: Int = 0,
)

data class AutomationAction(
    val id: String,
    val taskClassName: String,
    val displayName: String,
    val iconRes: Int = 0,
    val params: String? = null,
    val enabled: Boolean = true,
)

data class AutomationTask(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val trigger: AutomationTrigger,
    val conditions: AutomationConditions = AutomationConditions(),
    val actions: List<AutomationAction>,
    val executionMode: ExecutionMode,        // SEQUENTIAL / PARALLEL
    val retryConfig: RetryConfig,
    val order: Int = 0,
    val createdAt: Long,
    var lastExecutedAt: Long? = null,
    var executionCount: Int = 0,
)
```

## 五、UI 方案

### 5.1 列表页

- 顶部 Chips 筛选：`全部 / 定时 / 事件 / 手动`
- 每条任务一张 `MaterialCardView`，**无嵌套**
- 卡片内容：
  - Header：`[触发器图标] [触发器描述] [启用开关]`
  - Condition Row（可选）：徽章式条件 `🔒仅WiFi · 🔋充电中 · 🕒02-05`
  - Actions：`→ 动作1`，`→ 动作2`（顺序）或 `|| 动作1 || 动作2`（并行）
  - Footer：`[▶ 运行] [✎ 编辑] [⋮ 更多]`

### 5.2 三步向导

- **Step 1**：选触发方式（定时 / 事件 / 手动）
- **Step 2**：配置条件（可全跳过）
- **Step 3**：添加动作 + 选择执行方式（顺序 / 并行）

## 六、关键修复

| 问题 | 修复 |
|---|---|
| `handleTaskCompleted` 永远不被调用 | `AutomationManager` 订阅 `BackgroundTaskStatusManager.TaskChangeListener` |
| `DownloadListener` 单字段被覆盖 | `DownloadManager` 改为 `addDownloadListener` / `removeDownloadListener` 多播；保留旧 API 但 deprecated |
| 旧 `ScheduledTask` 数据迁移 | `AutomationPersistence.load()` 优先读新格式；旧 JSON 自动转换为新格式并备份 |
| 任务组合并 | `TaskGroup.executionMode` + `groupId` 集合 → 转为单个任务的 `executionMode + actions` 顺序 |

## 七、实施阶段

| 阶段 | 状态 | 范围 |
|---|---|---|
| P0 | ✅ | DownloadListener 多播化 + TaskChangeListener 接入（修 Bug） |
| P1 | ✅ | 数据模型 + 持久化 + 迁移 + 事件注册表 |
| P2 | ✅ | 事件源接入（下载/网络/前后台/收藏/历史/本地画廊/电池） |
| P3 | ✅ | ActionExecutor + ConditionEvaluator + ScheduleDispatcher |
| P4 | ✅ | UI：AutomationActivity + WizardActivity + AutomationLogActivity + 设置入口 |
| P5 | ✅ | 清理旧 UI（删除 ScheduledTaskActivity/Adapter/LogActivity/AddScheduledTaskDialog/TaskGroupManagerDialog 及对应布局与菜单）+ 编译通过 |

## 八、文件清单（截至 P3）

```
app/src/main/java/com/hippo/ehviewer/task/automation/
├── AutomationEnums.kt          RepeatMode / ExecutionMode / RetryMode / AutomationState
├── EventTriggerType.kt        18 个内置事件 + Category + FilterSupport
├── EventTriggerRegistry.kt    元数据注册表
├── AutomationTrigger.kt       sealed: Schedule / Event / Manual + EventFilter
├── AutomationAction.kt        单个动作
├── AutomationConditions.kt    AutomationConditions + TimeWindow
├── AutomationTask.kt          AutomationTask + RetryConfig
├── AutomationLogger.kt        日志（轮转 + 导出）
├── AutomationPersistence.kt   SharedPreferences + 旧数据迁移
├── AutomationManager.kt       主类（初始化/调度/执行/重试/完成回调）
├── EventTriggerDispatcher.kt  18 个事件源适配（下载/网络/收藏/历史/本地画廊/电池/前台后台/文件/Intent）
└── ConditionEvaluator.kt      WiFi/充电/屏幕/电池/时段/工作日

修改：
app/src/main/java/com/hippo/ehviewer/download/
├── DownloadManager.java       DownloadListener 多播化
└── DownloadService.kt         切换到 addDownloadListener/removeDownloadListener

app/src/main/java/com/hippo/ehviewer/EhDB.java
└── putHistoryInfo 触发 HistoryListener（用于 HISTORY_ADDED 事件）

app/build.gradle
└── 新增 androidx.lifecycle:lifecycle-process + lifecycle-runtime-ktx

app/src/main/res/values/strings.xml
└── 新增 ~80 个 automation_* 字符串（含事件名 / 条件 / 向导）

P4 新增文件：
app/src/main/java/com/hippo/ehviewer/ui/automation/
├── AutomationActivity.java    列表主页（卡片 + 筛选 Chips）
├── WizardActivity.java       三步向导（触发 / 条件 / 动作）
└── AutomationLogActivity.java 执行日志查看

app/src/main/res/layout/
├── activity_automation.xml          列表页
├── item_automation.xml              卡片项（When/If/Then 三段式）
├── activity_automation_wizard.xml  向导容器
├── wizard_step_trigger.xml
├── wizard_step_conditions.xml
├── wizard_step_actions.xml
├── activity_automation_log.xml
└── item_automation_log.xml

app/src/main/AndroidManifest.xml
└── 注册 3 个新 Activity
```