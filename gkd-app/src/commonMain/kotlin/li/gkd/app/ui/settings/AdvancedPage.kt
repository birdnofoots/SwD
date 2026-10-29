package li.gkd.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import li.gkd.app.didi.DidiConfig
import androidx.compose.ui.Modifier
import li.gkd.app.ui.component.gkTopAppBarScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import li.gkd.app.network.AppLinks
import li.gkd.app.resources.*
import li.gkd.app.settings.SettingsRepository
import li.gkd.app.ui.MainViewModel
import li.gkd.app.ui.component.GkAlertDialog
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.ui.component.GkScaffold
import li.gkd.app.ui.component.GkSettingItem
import li.gkd.app.ui.component.GkSettingsDialog
import li.gkd.app.ui.component.GkSizedIconButton
import li.gkd.app.didi.DidiCloudConfig
import li.gkd.app.ui.component.GkOutlinedTextField
import li.gkd.app.ui.component.GkTextSwitch
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.component.autoFocus
import li.gkd.app.ui.navigation.A11yEventLogRoute
import li.gkd.app.ui.navigation.ActivityLogRoute
import li.gkd.app.ui.navigation.CrashReportRoute
import li.gkd.app.ui.navigation.ExternalCallsRoute
import li.gkd.app.ui.navigation.QuickSettingsTilesRoute
import li.gkd.app.ui.navigation.SnapshotPageRoute
import li.gkd.app.ui.navigation.DidiReportRoute
import li.gkd.app.ui.navigation.SnapshotSettingsRoute
import li.gkd.app.ui.platform.UiHost
import li.gkd.app.ui.style.TABULAR_NUMBERS_FONT_FEATURE
import li.gkd.app.ui.style.itemHorizontalPadding
import li.gkd.app.ui.style.itemVerticalPadding
import li.gkd.app.ui.style.titleItemPadding
import li.gkd.app.util.Constants
import li.gkd.app.util.ToastUtils
import org.jetbrains.compose.resources.stringResource

@Composable
fun AdvancedPage(
    host: UiHost,
) {
    val mainVm = MainViewModel.requireCurrent()
    val vm = viewModel { AdvancedViewModel() }
    val store by SettingsRepository.settings.collectAsStateWithLifecycle()
    val httpRunning: Boolean = httpRunning()

    val serverAddresses: List<String> = httpServerAddresses()

    val buttonServiceRunning: Boolean = snapshotButtonRunning()

    val activityServiceRunning: Boolean = activityMonitorRunning()

    val eventServiceRunning: Boolean = eventMonitorRunning()

    val trackServiceRunning: Boolean = trackServiceRunning()

    val showTrackNotice by vm.showTrackNotice.collectAsStateWithLifecycle()
    if (showTrackNotice)
        GkAlertDialog(
            onDismissRequest = { vm.setShowTrackNotice(false) },
            title = { Text(stringResource(Res.string.usage_notice)) },
            text = { Text(stringResource(Res.string.track_overlay_usage_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.setShowTrackNotice(false)
                        host.setTrackServiceEnabled(true)
                    }
                ) {
                    Text(stringResource(Res.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.setShowTrackNotice(false) }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    val showEditPortDialog by vm.showEditPortDialog.collectAsStateWithLifecycle()
    val showHttpSettingsDialog by vm.showHttpSettingsDialog.collectAsStateWithLifecycle()

    if (showHttpSettingsDialog) {
        GkSettingsDialog(
            title = stringResource(Res.string.http_settings_title),
            onDismissRequest = { vm.setShowHttpSettingsDialog(false) },
        ) {
            GkSettingItem(
                title = stringResource(Res.string.http_port),
                subtitle = store.httpServerPort.toString(),
                imageVector = GkIcons.Edit,
                onClickLabel = stringResource(Res.string.http_port_edit),
                onClick = {
                    vm.editPort(store.httpServerPort)
                },
            )
            GkTextSwitch(
                title = stringResource(Res.string.http_clear_subscription),
                subtitle = stringResource(Res.string.http_clear_subscription_description),
                checked = store.autoClearMemorySubs,
                onCheckedChange = { enabled ->
                    SettingsRepository.updateSettings { it.copy(autoClearMemorySubs = enabled) }
                },
            )
        }
    }

    val portDraft by vm.portDraft.collectAsStateWithLifecycle()
    if (showEditPortDialog) {
        EditHttpPortDialog(
            value = portDraft,
            onValueChange = vm::setPortDraft,
            onDismissRequest = { vm.closeEditPortDialog() },
            onConfirm = { text ->
                val oldPort = SettingsRepository.settings.value.httpServerPort
                if (SettingsRepository.saveHttpServerPort(text)) {
                    if (oldPort != text.toInt()) {
                        host.httpServerPortChanged()
                        ToastUtils.show(Res.string.update_success.getSync())
                    }
                    vm.closeEditPortDialog()
                } else {
                    ToastUtils.show(Res.string.http_port_input_hint.getSync())
                }
            },
        )
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    GkScaffold(
        modifier = Modifier.gkTopAppBarScroll(scrollBehavior),
        topBar = {
            GkTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GkIconButton(
                        imageVector = GkIcons.ArrowBack,
                        onClick = mainVm.navigator::pop,
                    )
                },
                title = { Text(text = stringResource(Res.string.advanced_settings)) },
            )
        },
    ) { contentPadding ->
        Column(
            modifier =
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)
        ) {
            AdvancedSectionTitle(
                stringResource(Res.string.advanced_background_running),
                showTop = false,
            )
            GkTextSwitch(
                title = stringResource(Res.string.status_overlay_keep_alive),
                checked = store.enableStatusOverlay,
                onCheckedChange = vm::requestStatusOverlayEnabled,
            )

            AdvancedSectionTitle(stringResource(Res.string.advanced_snapshot_capture))
            GkSettingItem(
                title = stringResource(Res.string.snapshot_records),
                onClick = { mainVm.navigator.navigate(SnapshotPageRoute) },
            )
            GkTextSwitch(
                title = stringResource(Res.string.snapshot_button_label),
                subtitle = stringResource(Res.string.snapshot_button_description),
                checked = buttonServiceRunning,
                onCheckedChange = host::setSnapshotButtonEnabled,
            )
            GkSettingItem(
                title = stringResource(Res.string.snapshot_settings),
                onClick = { mainVm.navigator.navigate(SnapshotSettingsRoute) },
            )

            AdvancedSectionTitle(stringResource(Res.string.advanced_live_debug))
            HttpServiceItem(
                running = httpRunning,
                settingsSelected = showHttpSettingsDialog,
                port = store.httpServerPort,
                serverAddresses = serverAddresses,
                onSettingsClick = { vm.setShowHttpSettingsDialog(true) },
                onRunningChange = host::setHttpServiceEnabled,
                onAddressClick = mainVm.textDialog::showUrl,
            )
            GkTextSwitch(
                title = stringResource(Res.string.activity_service_label),
                subtitle = stringResource(Res.string.activity_service_description),
                checked = activityServiceRunning,
                onCheckedChange = host::setActivityMonitorEnabled,
            )
            GkTextSwitch(
                title = stringResource(Res.string.event_service_label),
                subtitle = stringResource(Res.string.event_service_description),
                checked = eventServiceRunning,
                onCheckedChange = host::setEventMonitorEnabled,
            )
            GkTextSwitch(
                title = stringResource(Res.string.track_overlay),
                subtitle = stringResource(Res.string.track_overlay_description),
                checked = trackServiceRunning,
                onCheckedChange = { if (it) vm.setShowTrackNotice(true) else host.setTrackServiceEnabled(false) },
            )
            GkSettingItem(
                title = stringResource(Res.string.external_calls_title),
                onClick = { mainVm.navigator.navigate(ExternalCallsRoute) },
            )
            GkSettingItem(
                title = stringResource(Res.string.quick_settings_tiles_title),
                onClick = { mainVm.navigator.navigate(QuickSettingsTilesRoute) },
            )

            AdvancedSectionTitle(stringResource(Res.string.advanced_logs_diagnostics))
            GkSettingItem(
                title = stringResource(Res.string.activity_switch_logs),
                onClick = { mainVm.navigator.navigate(ActivityLogRoute) },
            )
            GkSettingItem(
                title = stringResource(Res.string.a11y_event_logs),
                onClick = { mainVm.navigator.navigate(A11yEventLogRoute) },
            )
            GkSettingItem(
                title = stringResource(Res.string.crash_reports),
                onClick = { mainVm.navigator.navigate(CrashReportRoute) },
            )

            AdvancedSectionTitle(stringResource(Res.string.advanced_upload_share))
            GkSettingItem(
                title = stringResource(Res.string.github_cookie_label),
                subtitle = stringResource(Res.string.upload_links_description),
                suffix = stringResource(Res.string.tutorial_view),
                suffixUnderline = true,
                onSuffixClick = { mainVm.navigator.openWebPage(AppLinks.CookieHelp) },
                imageVector = GkIcons.Edit,
                onClick = mainVm.githubUpload::editCookie,
            )

            // ===== DidiGKD 滴滴自动化 开关组 =====
            AdvancedSectionTitle("DidiGKD 滴滴自动化")
            var didiTapEnabled by remember { mutableStateOf(DidiConfig.tapEnabled.value) }
            // 双向同步: HTTP POST /api/didi_tap 改了值, UI 也能刷新(每2秒)
            LaunchedEffect(Unit) {
                while (true) { delay(2000); didiTapEnabled = DidiConfig.tapEnabled.value }
            }
            GkTextSwitch(
                title = "真实点击",
                subtitle = "开=真点击抢/不抢  关=影子模式(只决策+存档不点)",
                checked = didiTapEnabled,
                onCheckedChange = { en -> didiTapEnabled = en; DidiConfig.tapEnabled.value = en },
            )
            GkSettingItem(
                title = "控制流 flow.js",
                subtitle = "热更新: 改文件后下一单生效, 语法错自动回退上一版",
            )
            GkSettingItem(
                title = "决策报告",
                subtitle = "查看每单的截图/价格/决策(无需网页)",
                onClick = { mainVm.navigator.navigate(DidiReportRoute) },
            )
            // ===== 云端日志上传（静默后台 · 增量）=====
            AdvancedSectionTitle("云端日志上传")
            DidiCloudConfig.load()
            var cloudOn by remember { mutableStateOf(DidiCloudConfig.enabled.value) }

            // 云端配置可能被「激活码开通」改写(自动置 enabled=true)，这里同步让开关即时反映
            LaunchedEffect(Unit) {
                while (true) {
                    delay(2000)
                    cloudOn = DidiCloudConfig.enabled.value
                }
            }
            var cloudShots by remember { mutableStateOf(DidiCloudConfig.uploadShots.value) }
            var cloudUrl by remember { mutableStateOf(DidiCloudConfig.baseUrl.value) }
            var cloudToken by remember { mutableStateOf(DidiCloudConfig.token.value) }
            GkTextSwitch(
                title = "静默上传到云端",
                subtitle = "后台自动增量上传记录与截图，无通知无弹窗；关闭则只存本机",
                checked = cloudOn,
                onCheckedChange = { en ->
                    cloudOn = en
                    DidiCloudConfig.enabled.value = en
                    DidiCloudConfig.save()
                },
            )
            GkTextSwitch(
                title = "上传截图",
                subtitle = "截图含出发地/目的地；流量紧张可关，只传文字记录",
                checked = cloudShots,
                onCheckedChange = { en ->
                    cloudShots = en
                    DidiCloudConfig.uploadShots.value = en
                    DidiCloudConfig.save()
                },
            )
            GkOutlinedTextField(
                value = cloudUrl,
                onValueChange = {
                    cloudUrl = it
                    DidiCloudConfig.baseUrl.value = it.trim()
                    DidiCloudConfig.save()
                },
                label = { Text("服务器地址") },
                placeholder = { Text("http://IP:端口") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            GkOutlinedTextField(
                value = cloudToken,
                onValueChange = {
                    cloudToken = it
                    DidiCloudConfig.token.value = it.trim()
                    DidiCloudConfig.save()
                },
                label = { Text("用户 Token") },
                placeholder = { Text("激活码换取的一串字符") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // 激活码开通：填码 → 服务端换 Token → 自动保存
            val scope = rememberCoroutineScope()
            var cloudCode by remember { mutableStateOf("") }
            var cloudMsg by remember { mutableStateOf("") }
            GkOutlinedTextField(
                value = cloudCode,
                onValueChange = { cloudCode = it },
                label = { Text("激活码") },
                placeholder = { Text("DK-XXXX-XXXX-XXXX") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            GkSettingItem(
                title = "用激活码开通",
                subtitle = if (cloudMsg.startsWith("OK")) cloudMsg.removePrefix("OK:")
                else if (cloudMsg.startsWith("ERR")) "开通失败：" + cloudMsg.removePrefix("ERR:")
                else "填好激活码后点这里，自动换取 Token 并开始工作",
                onClick = {
                    val c = cloudCode
                    // 网络请求放 IO；回到主线程再改 Compose 状态(直接在后台线程赋值不安全)
                    scope.launch(Dispatchers.IO) {
                        val r = didiCloudActivate(c)
                        withContext(Dispatchers.Main) {
                            cloudMsg = r
                            if (r.startsWith("OK")) cloudCode = ""
                        }
                    }
                },
            )
            GkSettingItem(
                title = "立即同步一次",
                subtitle = "不等巡检周期，马上把新增数据传上去",
                onClick = { Thread { didiCloudSyncNow() }.start() },
            )
            GkPageBottomSpace()
        }
    }
}

expect fun UiHost.setSnapshotButtonEnabled(enabled: Boolean)

expect fun UiHost.setHttpServiceEnabled(enabled: Boolean)

expect fun UiHost.httpServerPortChanged()

expect fun UiHost.setActivityMonitorEnabled(enabled: Boolean)

expect fun UiHost.setEventMonitorEnabled(enabled: Boolean)

expect fun UiHost.setTrackServiceEnabled(enabled: Boolean)

expect fun didiCloudSyncNow(): Boolean

expect fun didiCloudActivate(code: String): String

@Composable expect fun httpRunning(): Boolean

@Composable expect fun httpServerAddresses(): List<String>

@Composable expect fun snapshotButtonRunning(): Boolean

@Composable expect fun activityMonitorRunning(): Boolean

@Composable expect fun eventMonitorRunning(): Boolean

@Composable expect fun trackServiceRunning(): Boolean

@Composable
private fun AdvancedSectionTitle(title: String, showTop: Boolean = true) {
    Text(
        text = title,
        modifier = Modifier.titleItemPadding(showTop = showTop),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun HttpServiceItem(
    running: Boolean,
    settingsSelected: Boolean,
    port: Int,
    serverAddresses: List<String>,
    onSettingsClick: () -> Unit,
    onRunningChange: (Boolean) -> Unit,
    onAddressClick: (String) -> Unit,
) {
    val addressStyle =
        MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR_NUMBERS_FONT_FEATURE)
    val addressItem: @Composable (String, String) -> Unit = { host, type ->
        Text(
            text = stringResource(Res.string.http_address_description, host, port, type),
            style = addressStyle,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier.fillMaxWidth()
                    .clickable(
                        onClickLabel = stringResource(Res.string.http_view_addresses, type),
                        onClick = { onAddressClick("http://${host}:${port}") },
                    )
                    .padding(vertical = 2.dp),
        )
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .clickable(
                        onClickLabel = stringResource(Res.string.http_toggle_service),
                        onClick = { onRunningChange(!running) },
                    )
                    .padding(
                        start = itemHorizontalPadding,
                        top = itemVerticalPadding,
                        end = itemHorizontalPadding,
                        bottom = 4.dp,
                    )
        ) {
            GkTextSwitch(
                modifier = Modifier.fillMaxWidth(),
                paddingDisabled = true,
                title = stringResource(Res.string.http_service_label),
                subtitle = stringResource(Res.string.http_service_description),
                suffixIcon = {
                    GkSizedIconButton(
                        size = 32.dp,
                        iconSize = 20.dp,
                        onClickLabel = stringResource(Res.string.http_settings_open),
                        onClick = onSettingsClick,
                        imageVector = GkIcons.PageInfo,
                        contentDescription = stringResource(Res.string.http_settings_button),
                        tint =
                            if (settingsSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                    )
                },
                checked = running,
                onCheckedChange = onRunningChange,
                onClick = null,
            )
        }
        AnimatedVisibility(visible = running) {
            Column(
                modifier =
                    Modifier.padding(
                        start = itemHorizontalPadding,
                        top = 0.dp,
                        end = itemHorizontalPadding,
                        bottom = 4.dp,
                    )
            ) {
                serverAddresses.forEach { host ->
                    addressItem(host, stringResource(if (host == Constants.loopbackHost) Res.string.network_local_device else Res.string.network_lan))
                }
            }
        }
    }
}

@Composable
private fun EditHttpPortDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    GkAlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(text = stringResource(Res.string.http_port)) },
        text = {
            OutlinedTextField(
                value = value,
                placeholder = { Text(text = stringResource(Res.string.http_port_input_hint)) },
                onValueChange = onValueChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().autoFocus(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(
                        text = stringResource(Res.string.port_input_length, value.length),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                    )
                },
            )
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = value.isNotEmpty(),
                onClick = { onConfirm(value) },
            ) {
                Text(text = stringResource(Res.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(Res.string.action_cancel))
            }
        },
    )
}
