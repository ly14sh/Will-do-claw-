package com.antgskds.calendarassistant.service.capsule.miui

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import com.antgskds.calendarassistant.MainActivity
import com.antgskds.calendarassistant.R
import com.antgskds.calendarassistant.core.util.OsUtils
import com.antgskds.calendarassistant.data.model.EventTags
import com.antgskds.calendarassistant.data.model.EventType
import com.antgskds.calendarassistant.data.state.CapsuleUiState
import com.antgskds.calendarassistant.service.capsule.CapsuleDisplayModel
import com.antgskds.calendarassistant.service.capsule.CapsuleService
import com.antgskds.calendarassistant.service.capsule.CapsuleUiUtils
import com.antgskds.calendarassistant.service.capsule.IconUtils
import com.antgskds.calendarassistant.service.receiver.EventActionReceiver
import com.antgskds.calendarassistant.xposed.MiuiIslandAction
import com.antgskds.calendarassistant.xposed.MiuiIslandDispatcher
import com.antgskds.calendarassistant.xposed.MiuiIslandRequest
import com.antgskds.calendarassistant.xposed.XposedModuleStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object MiuiIslandManager {
    private const val TAG = "MiuiIslandManager"
    private const val MAX_TIMEOUT_SECS = 3600
    private const val MIN_TIMEOUT_SECS = 5
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    @Volatile private var lastRequestKey: String? = null
    @Volatile private var lastNotifId: Int? = null

    private data class ActionPayload(
        val actions: List<MiuiIslandAction>,
        val actionTitle: String?,
        val actionIntentUri: String?
    )

    fun update(context: Context, capsules: List<CapsuleUiState.Active.CapsuleItem>) {
        // 检查设置是否启用了超级岛通知
        val settings = com.antgskds.calendarassistant.data.repository.AppRepository.getInstance(context).settings.value
        val isFocusEnabled = settings.isHyperOsFocusNotificationEnabled
        
        // 优先使用官方 API（Android 14+ HyperOS 且设置开启）
        if (isFocusEnabled && isHyperOsWithOfficialFocusSupport()) {
            sendOfficialFocusNotification(context, capsules)
            return
        }
        
        // 回退到 Xposed 模式
        if (!isAvailable()) return
        val target = selectTargetCapsule(capsules) ?: run {
            clear(context)
            return
        }

        val isNewTarget = lastNotifId == null || lastNotifId != target.notifId
        val request = buildRequest(context, target, isNewTarget)
        val requestKey = buildRequestKey(request)
        if (requestKey == lastRequestKey) return

        val previousNotifId = lastNotifId
        if (previousNotifId != null && previousNotifId != request.notifId) {
            sendDismiss(context, previousNotifId)
        }

        lastRequestKey = requestKey
        lastNotifId = request.notifId
        MiuiIslandDispatcher.sendBroadcast(context, request)
        Log.d(TAG, "send island via Xposed: ${request.title} | ${request.content} | actions=${request.actions.size}")
    }

    fun clear(context: Context) {
        if (!isAvailable()) return
        lastNotifId?.let { sendDismiss(context, it) }
        lastRequestKey = null
        lastNotifId = null
    }

    private fun sendDismiss(context: Context, notifId: Int) {
        MiuiIslandDispatcher.sendBroadcast(
            context,
            MiuiIslandRequest(
                title = "",
                content = "",
                notifId = notifId,
                dismissIsland = true,
                showNotification = false,
            )
        )
        Log.d(TAG, "dismiss island: $notifId")
    }

    private fun isAvailable(): Boolean = 
        isHyperOsWithOfficialFocusSupport() || (OsUtils.isHyperOS() && XposedModuleStatus.isActive())
    
    // 小米官方岛通知支持检测
    fun isHyperOsWithOfficialFocusSupport(): Boolean {
        return OsUtils.isHyperOS() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }
    
    // 使用官方 API 发送通知
    fun sendOfficialFocusNotification(context: Context, capsules: List<CapsuleUiState.Active.CapsuleItem>) {
        if (!isHyperOsWithOfficialFocusSupport()) return
        
        val target = selectTargetCapsule(capsules) ?: run {
            clear(context)
            return
        }
        
        val display = target.display
        val title = display.primaryText.ifBlank { display.shortText }
        val content = buildContent(display, MiuiIslandRequest.TEMPLATE_TEXT_ICON_ACTION)
        
        // 构建官方 miui.focus.param JSON
        val miuiParams = buildMiuiFocusParams(context, title, content, target)
        
        // 通过通知发送官方参数
        sendFocusNotificationViaNotification(context, target.notifId, title, content, miuiParams)
    }
    
    private fun buildMiuiFocusParams(
        context: Context,
        title: String,
        content: String,
        item: CapsuleUiState.Active.CapsuleItem
    ): String {
        val display = item.display
        // 构建符合小米官方格式的 JSON 参数
        val paramV2 = mutableMapOf<String, Any>(
            "business" to "calendar_assistant",
            "islandFirstFloat" to true,
            "enableFloat" to true,
            "timeout" to 720,
            "updatable" to true,
            "reopen" to "reopen"
        )
        
        // 岛属性数据
        val islandData = mutableMapOf<String, Any>(
            "islandProperty" to 1,
            "islandTimeout" to computeTimeout(item),
            "dismissIsland" to false
        )
        
        // 大岛内容数据
        val bigIslandArea = mutableMapOf<String, Any>(
            "type" to "text",
            "title" to title,
            "summary" to content
        )
        
        islandData["bigIslandArea"] = bigIslandArea
        
        // 小岛内容数据
        val smallIslandArea = mutableMapOf<String, Any>(
            "type" to "text",
            "content" to content
        )
        islandData["smallIslandArea"] = smallIslandArea
        
        paramV2["param_island"] = islandData
        
        // 通知内容数据
        val contentData = mutableMapOf<String, Any>(
            "title" to title,
            "summary" to content
        )
        paramV2["content"] = contentData
        
        // 息屏显示数据
        paramV2["aodData"] = mapOf("aodTitle" to display.shortText)
        
        // 状态栏焦点信息
        paramV2["tickerData"] = mapOf("ticker" to display.shortText)
        
        val root = mapOf("param_v2" to paramV2)
        return Json.encodeToString(root)
    }
    
    private fun sendFocusNotificationViaNotification(
        context: Context,
        notificationId: Int,
        title: String,
        content: String,
        miuiFocusParams: String
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "miui_focus_channel",
                "小米岛通知",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "小米官方岛通知"
            notificationManager.createNotificationChannel(channel)
            android.app.Notification.Builder(context, "miui_focus_channel")
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }
        
        val icon = Icon.createWithResource(context, R.drawable.ic_notification_small)
        
        val notification = builder
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(content)
            .setExtras(android.os.Bundle().apply {
                putString("miui.focus.param", miuiFocusParams)
            })
            .setOngoing(true)
            .setPriority(android.app.Notification.PRIORITY_HIGH)
            .build()
        
        notificationManager.notify(notificationId, notification)
        Log.d(TAG, "Sent official focus notification: $title")
    }

    private fun selectTargetCapsule(
        capsules: List<CapsuleUiState.Active.CapsuleItem>
    ): CapsuleUiState.Active.CapsuleItem? {
        if (capsules.isEmpty()) return null
        val now = System.currentTimeMillis()
        val candidates = capsules.filter { isCapsuleActive(it, now) }
        if (candidates.isEmpty()) return null
        return candidates.sortedWith(
            compareByDescending<CapsuleUiState.Active.CapsuleItem> { it.startMillis }
                .thenByDescending { it.endMillis }
        ).first()
    }

    private fun isCapsuleActive(item: CapsuleUiState.Active.CapsuleItem, now: Long): Boolean {
        val extraMillis = if (item.type == CapsuleService.TYPE_PICKUP ||
            item.type == CapsuleService.TYPE_PICKUP_EXPIRED
        ) {
            5 * 60 * 1000L
        } else {
            0L
        }
        return now < item.endMillis + extraMillis
    }

    private fun buildRequest(
        context: Context,
        item: CapsuleUiState.Active.CapsuleItem,
        isNewTarget: Boolean
    ): MiuiIslandRequest {
        val display = item.display
        val title = display.primaryText.ifBlank { display.shortText }
        val actionsPayload = buildActions(context, item)
        val actions = actionsPayload.actions
        val templateType = resolveTemplateType(item, display, actions)
        val content = buildContent(display, templateType)
        val (iconLight, iconDark) = buildEventIcons(context, item)
        val appIcon = buildAppIcon(context)
        val contentIntent = createContentPendingIntent(context, item)
        val timeout = computeTimeout(item)
        val highlightColor = formatHighlightColor(item.color)
        val tagText = null
        val hintTitle = if (templateType == MiuiIslandRequest.TEMPLATE_TEXT_ICON_ACTION) {
            buildHintTitle(item, display)
        } else {
            null
        }
        val summaryStatus = buildSummaryStatus(item)
        val summaryTitle = buildSummaryTitle(display)

        return MiuiIslandRequest(
            title = title,
            content = content,
            icon = iconLight,
            iconDark = iconDark,
            appIcon = appIcon,
            summaryStatus = summaryStatus,
            summaryTitle = summaryTitle,
            notifId = item.notifId,
            timeoutSecs = timeout,
            firstFloat = isNewTarget,
            enableFloat = true,
            showNotification = true,
            highlightColor = highlightColor,
            dismissIsland = false,
            contentIntent = contentIntent,
            actions = actions,
            templateType = templateType,
            tagText = tagText,
            hintTitle = hintTitle,
            actionTitle = actionsPayload.actionTitle,
            actionIntentUri = actionsPayload.actionIntentUri,
        )
    }

    private fun buildContent(display: CapsuleDisplayModel, templateType: Int): String {
        val candidates = listOf(
            display.secondaryText,
            display.tertiaryText,
            display.expandedText?.lineSequence()?.firstOrNull()
        )
        val filtered = candidates.mapNotNull { sanitizeLine(it) }
            .let { values ->
                if (templateType == MiuiIslandRequest.TEMPLATE_TEXT_ICON_ACTION) {
                    values.filterNot { isTimeRange(it) }
                } else {
                    values
                }
            }
        val content = filtered
            .distinct()
            .joinToString(" · ")
            .ifBlank { display.primaryText }
        return truncate(content, 42)
    }

    private fun buildEventIcons(
        context: Context,
        item: CapsuleUiState.Active.CapsuleItem
    ): Pair<Icon?, Icon?> {
        val iconResId = IconUtils.getSmallIconForCapsule(item)
        val bitmap = CapsuleUiUtils.drawableToBitmap(context, iconResId)
        if (bitmap != null) {
            val icon = Icon.createWithBitmap(bitmap)
            return icon to icon
        }
        val fallback = Icon.createWithResource(context, iconResId)
        return fallback to fallback
    }

    private fun buildAppIcon(context: Context): Icon? {
        return try {
            val appInfo = context.applicationInfo
            val drawable = context.packageManager.getApplicationIcon(appInfo)
            val bitmap = CapsuleUiUtils.drawableToBitmap(drawable)
            Icon.createWithBitmap(bitmap)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildActions(
        context: Context,
        item: CapsuleUiState.Active.CapsuleItem
    ): ActionPayload {
        val action = item.display.action ?: return emptyActionPayload()
        val broadcastIntent = Intent(context, EventActionReceiver::class.java).apply {
            this.action = action.receiverAction
            putExtra(EventActionReceiver.EXTRA_EVENT_ID, item.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            item.id.hashCode() + 11,
            broadcastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return ActionPayload(
            actions = listOf(MiuiIslandAction(action.label, pendingIntent)),
            actionTitle = action.label,
            actionIntentUri = broadcastIntent.toUri(Intent.URI_INTENT_SCHEME)
        )
    }

    private fun emptyActionPayload(): ActionPayload = ActionPayload(emptyList(), null, null)

    private fun createContentPendingIntent(
        context: Context,
        item: CapsuleUiState.Active.CapsuleItem
    ): PendingIntent {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (item.display.tapOpensPickupList) {
                putExtra("openPickupList", true)
            }
        }
        return PendingIntent.getActivity(
            context,
            item.id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun computeTimeout(item: CapsuleUiState.Active.CapsuleItem): Int {
        val remaining = (item.endMillis - System.currentTimeMillis()) / 1000
        if (remaining <= 0) return MIN_TIMEOUT_SECS
        return remaining.toInt().coerceIn(MIN_TIMEOUT_SECS, MAX_TIMEOUT_SECS)
    }

    private fun formatHighlightColor(color: Int): String {
        val rgb = color and 0x00FFFFFF
        return String.format("#%06X", rgb)
    }

    private fun buildRequestKey(request: MiuiIslandRequest): String {
        val actionsKey = request.actions.joinToString("|") { it.title }
        return listOf(
            request.notifId.toString(),
            request.title,
            request.content,
            request.highlightColor ?: "",
            actionsKey,
            request.templateType.toString(),
            request.tagText ?: "",
            request.hintTitle ?: "",
            request.actionTitle ?: "",
            request.actionIntentUri ?: "",
            request.summaryStatus ?: "",
            request.summaryTitle ?: ""
        ).joinToString("::")
    }

    private fun resolveTemplateType(
        item: CapsuleUiState.Active.CapsuleItem,
        display: CapsuleDisplayModel,
        actions: List<MiuiIslandAction>
    ): Int {
        return when (item.type) {
            CapsuleService.TYPE_OCR_PROGRESS,
            CapsuleService.TYPE_OCR_RESULT -> MiuiIslandRequest.TEMPLATE_TEXT_ICON
            else -> if (display.action != null && actions.isNotEmpty()) {
                MiuiIslandRequest.TEMPLATE_TEXT_ICON_ACTION
            } else {
                MiuiIslandRequest.TEMPLATE_TEXT_ICON
            }
        }
    }

    private fun resolveTagText(item: CapsuleUiState.Active.CapsuleItem): String {
        return when (item.eventType) {
            EventTags.TRAIN -> "火车"
            EventTags.TAXI -> "用车"
            EventTags.PICKUP -> if (isFoodPickup(item.description)) "取餐" else "取件"
            EventTags.GENERAL -> "日程"
            EventType.COURSE -> "课程"
            else -> when (item.type) {
                CapsuleService.TYPE_PICKUP,
                CapsuleService.TYPE_PICKUP_EXPIRED -> if (isFoodPickup(item.description)) "取餐" else "取件"
                else -> "日程"
            }
        }
    }

    private fun buildHintTitle(
        item: CapsuleUiState.Active.CapsuleItem,
        display: CapsuleDisplayModel
    ): String {
        val timeRange = formatTimeRange(item)
        if (timeRange != null) return timeRange
        val candidate = display.tertiaryText
            ?: display.secondaryText
            ?: display.primaryText
        return truncate(sanitizeLine(candidate) ?: display.primaryText, 18)
    }

    private fun formatTimeRange(item: CapsuleUiState.Active.CapsuleItem): String? {
        if (item.startMillis <= 0 || item.endMillis <= 0) return null
        return try {
            val zone = ZoneId.systemDefault()
            val start = Instant.ofEpochMilli(item.startMillis).atZone(zone).toLocalTime()
            val end = Instant.ofEpochMilli(item.endMillis).atZone(zone).toLocalTime()
            "${start.format(TIME_FORMATTER)}-${end.format(TIME_FORMATTER)}"
        } catch (_: Exception) {
            null
        }
    }

    private fun buildSummaryStatus(item: CapsuleUiState.Active.CapsuleItem): String {
        return when (item.type) {
            CapsuleService.TYPE_OCR_RESULT -> "已完成"
            CapsuleService.TYPE_OCR_PROGRESS,
            CapsuleService.TYPE_NETWORK_SPEED -> "进行中"
            else -> {
                val now = System.currentTimeMillis()
                if (now < item.startMillis) "即将进行" else "进行中"
            }
        }
    }

    private fun buildSummaryTitle(display: CapsuleDisplayModel): String {
        val raw = display.shortText.ifBlank { display.primaryText }
        val clean = sanitizeLine(raw) ?: raw
        return truncate(clean, 18)
    }

    private fun isFoodPickup(description: String?): Boolean {
        return description?.startsWith("【取餐】") == true
    }

    private fun isTimeRange(value: String): Boolean {
        val text = value.trim()
        return Regex("\\d{1,2}:\\d{2}(-\\d{1,2}:\\d{2})?").containsMatchIn(text)
    }

    private fun sanitizeLine(value: String?): String? {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return clean.replace("\n", " ").replace("\r", " ")
    }

    private fun truncate(text: String, max: Int): String {
        if (text.length <= max) return text
        return text.take(max - 3) + "..."
    }
}
