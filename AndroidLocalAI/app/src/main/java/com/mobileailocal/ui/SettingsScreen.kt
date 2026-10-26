package com.mobileailocal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobileailocal.R
import java.util.Locale
import kotlin.math.roundToInt

/** 設定画面(指示書 03 を参照)。 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onOpenExtensions: () -> Unit = {},
    onOpenApi: () -> Unit = {},
    onOpenWorkspace: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()

    // スライダーは操作中のみ画面内の状態動かし、指を離したときに VM へ保存する
    var temperature by remember { mutableStateOf(settings.params.temperature) }
    var topP by remember { mutableStateOf(settings.params.topP) }
    var maxTokens by remember { mutableStateOf(settings.params.maxTokens.toFloat()) }
    var contextLength by remember { mutableStateOf(settings.params.contextLength.toFloat()) }
    var threads by remember { mutableStateOf(settings.params.threads.toFloat()) }

    // 保存値が変わったら状態を合わせ直す
    LaunchedEffect(settings.params) {
        val p = settings.params
        temperature = p.temperature
        topP = p.topP
        maxTokens = p.maxTokens.toFloat()
        contextLength = p.contextLength.toFloat()
        threads = p.threads.toFloat()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SectionHeader(stringResource(R.string.settings_theme))
        RadioRow(stringResource(R.string.theme_system), "system", settings.theme) { vm.setTheme(it) }
        RadioRow(stringResource(R.string.theme_light), "light", settings.theme) { vm.setTheme(it) }
        RadioRow(stringResource(R.string.theme_dark), "dark", settings.theme) { vm.setTheme(it) }

        SectionHeader(stringResource(R.string.settings_language))
        RadioRow(stringResource(R.string.lang_system), "system", settings.language) { vm.setLanguage(it) }
        // 言語名は各言語の自称なので strings.xml に入れず固定文字列にする
        RadioRow("日本語", "ja", settings.language) { vm.setLanguage(it) }
        RadioRow("English", "en", settings.language) { vm.setLanguage(it) }
        RadioRow("简体中文", "zh-CN", settings.language) { vm.setLanguage(it) }
        RadioRow("繁體中文", "zh-TW", settings.language) { vm.setLanguage(it) }

        SectionHeader(stringResource(R.string.settings_storage))
        Text(stringResource(R.string.settings_storage_value, formatStorage(storage.first), formatStorage(storage.second)))

        SectionHeader(stringResource(R.string.settings_extensions))
        Row(
            Modifier.fillMaxWidth().clickable { onOpenExtensions() }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.ext_js_title), modifier = Modifier.weight(1f))
            Text(
                stringResource(if (settings.jsEnabled) R.string.settings_extensions_on else R.string.settings_extensions_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // material-icons の依存が無いので矢印は文字で出す
            Text(
                "›",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().clickable { onOpenWorkspace() }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_workspace_entry), modifier = Modifier.weight(1f))
            Text(
                stringResource(if (settings.workspaceEnabled) R.string.settings_extensions_on else R.string.settings_extensions_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "›",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        SectionHeader(stringResource(R.string.settings_api))
        Row(
            Modifier.fillMaxWidth().clickable { onOpenApi() }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_api_row))
                Text(
                    stringResource(R.string.settings_api_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "›",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        SectionHeader(stringResource(R.string.settings_auto_compress_header))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_auto_compress), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_auto_compress_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.autoCompress, onCheckedChange = { vm.setAutoCompress(it) })
        }

        SectionHeader(stringResource(R.string.settings_download))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_wifi_only), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_wifi_only_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.wifiOnly, onCheckedChange = { vm.setWifiOnly(it) })
        }

        SectionHeader(stringResource(R.string.settings_generation))
        Text(
            stringResource(R.string.settings_gen_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ValueSlider(
            name = stringResource(R.string.gen_temperature),
            description = stringResource(R.string.gen_temperature_desc),
            value = temperature,
            valueText = formatDecimal((temperature * 10f).roundToInt() / 10f, 1),
            valueRange = 0f..2f,
            steps = 0, // 目盛りは描かず、表示・保存時に 0.1 刻みへ丸める
            onValueChange = { temperature = it },
            onCommit = {
                vm.setParams(settings.params.copy(temperature = (temperature * 10f).roundToInt() / 10f))
            },
        )
        ValueSlider(
            name = stringResource(R.string.gen_top_p),
            description = stringResource(R.string.gen_top_p_desc),
            value = topP,
            valueText = formatDecimal((topP * 100f).roundToInt() / 100f, 2),
            valueRange = 0f..1f,
            steps = 0, // 目盛りを描くと重いので連続値にし、保存時に 0.01 刻みへ丸める
            onValueChange = { topP = it },
            onCommit = {
                vm.setParams(settings.params.copy(topP = (topP * 100f).roundToInt() / 100f))
            },
        )
        ValueSlider(
            name = stringResource(R.string.gen_max_tokens),
            description = stringResource(R.string.gen_max_tokens_desc),
            value = maxTokens,
            valueText = maxTokens.roundToInt().toString(),
            valueRange = 64f..4096f,
            steps = 0, // 目盛りを描くと重いので連続値にし、表示・保存時に整数へ丸める
            onValueChange = { maxTokens = it },
            onCommit = {
                vm.setParams(settings.params.copy(maxTokens = maxTokens.roundToInt()))
            },
        )
        ValueSlider(
            name = stringResource(R.string.gen_context),
            description = stringResource(R.string.gen_context_desc),
            value = contextLength,
            valueText = ((contextLength / 512f).roundToInt() * 512).toString(),
            valueRange = 512f..8192f,
            steps = 0, // 目盛りは描かず、表示・保存時に 512 刻みへ丸める
            onValueChange = { contextLength = it },
            onCommit = {
                vm.setParams(
                    settings.params.copy(contextLength = (contextLength / 512f).roundToInt() * 512)
                )
            },
        )

        ValueSlider(
            name = stringResource(R.string.gen_threads),
            description = stringResource(R.string.gen_threads_desc),
            value = threads,
            valueText = threads.roundToInt().toString(),
            valueRange = 1f..8f,
            steps = 0, // 目盛りは描かず、表示・保存時に整数へ丸める
            onValueChange = { threads = it },
            onCommit = {
                vm.setParams(settings.params.copy(threads = threads.roundToInt()))
            },
        )

        SectionHeader(stringResource(R.string.settings_about))
        Text(stringResource(R.string.about_version, vm.versionName))
        Row(
            Modifier.fillMaxWidth().clickable { onOpenPrivacy() }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_privacy_policy), modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionHeader(stringResource(R.string.settings_licenses))
        // ライブラリ名は固有名なので strings.xml に入れず固定文字列にする
        LicenseRow("llama.cpp", "MIT License")
        // ライセンス名は各ライブラリの POM(Google Maven / Maven Central)の記載で確認済み(2026-10-25)
        LicenseRow("AndroidX (Jetpack Compose, Room, DataStore, Navigation, Lifecycle, Activity)", "Apache License 2.0")
        LicenseRow("AndroidX Credentials (credentials, credentials-play-services-auth)", "Apache License 2.0")
        LicenseRow("Kotlin, kotlinx.coroutines", "Apache License 2.0")
        LicenseRow("Google Identity (googleid)", "Android Software Development Kit License\nhttps://developer.android.com/studio/terms.html")
        LicenseRow("Google Play services (play-services-auth, base, basement, tasks, fido, and related)", "Android Software Development Kit License\nhttps://developer.android.com/studio/terms.html")
        LicenseRow("Firebase Authentication", "Android Software Development Kit License\nhttps://developer.android.com/studio/terms.html")
        LicenseRow("Firebase (Cloud Firestore, Common, Components, and related)", "Apache License 2.0")
        LicenseRow("gRPC (grpc-java, including the OkHttp-based transport), PerfMark", "Apache License 2.0")
        LicenseRow("Protocol Buffers (protobuf-javalite)", "BSD 3-Clause License")
        LicenseRow("Guava, Gson, Okio, JSR305, Error Prone annotations", "Apache License 2.0")
        // 著作権表示は上流の原文で確認済み(2026-10-23)
        LicenseRow("QuickJS (bellard/quickjs)", "MIT License\nCopyright (c) 2017-2021 Fabrice Bellard\nCopyright (c) 2017-2021 Charlie Gordon")
        LicenseRow("quickjs-kt (dokar3)", "Apache License 2.0\nCopyright 2024 dokar3")
        LicenseRow("c-vector (eteran/c-vector, bundled with quickjs-kt)", "MIT License\nCopyright (c) 2015 Evan Teran")
        Text(
            stringResource(R.string.licenses_models_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun LicenseRow(name: String, license: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun RadioRow(label: String, value: String, selectedValue: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selectedValue, onClick = { onSelect(value) })
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ValueSlider(
    name: String,
    description: String,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$name: $valueText")
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onCommit,
        )
    }
}

// 表示用の数値整形(小数桁数は丸め桁数と揃える)
private fun formatDecimal(v: Float, decimals: Int): String =
    String.format(Locale.getDefault(), "%.${decimals}f", v)

// 表示用のサイズ整形(GB/MB 表記はコード側で固定。ChatScreen の formatSize と同じ)
private fun formatStorage(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0)
    } else {
        String.format(Locale.getDefault(), "%d MB", (mb).toLong())
    }
}
