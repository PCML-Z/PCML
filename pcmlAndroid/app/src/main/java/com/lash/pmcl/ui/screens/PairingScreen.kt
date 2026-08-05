package com.lash.pmcl.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lash.pmcl.companion.CompanionViewModel
import com.lash.pmcl.companion.ConnectionState
import com.lash.pmcl.companion.PairingUiState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.net.NetworkInterface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onBack: () -> Unit = {},
    viewModel: CompanionViewModel = viewModel()
) {
    val pairingState by viewModel.pairingState.collectAsStateWithLifecycle()
    val connectionState by viewModel.client.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("28520") }
    var code by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("${Build.MODEL} (Android)") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备配对") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 说明
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "在桌面端 PMCL 打开「设备配对」窗口，",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "将下方信息填入手机端完成配对。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // 桌面端 IP
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("桌面端 IP 地址") },
                placeholder = { Text("例如 192.168.1.100") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 端口
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() } },
                label = { Text("端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp)
            )

            // 配对码
            OutlinedTextField(
                value = code,
                onValueChange = {
                    val digits = it.filter { c -> c.isDigit() }.take(6)
                    code = digits
                },
                label = { Text("配对码（6 位数字）") },
                placeholder = { Text("000000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(160.dp)
            )

            // 设备名称
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text("设备名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 配对按钮
            Button(
                onClick = {
                    viewModel.pair(host, port.toIntOrNull() ?: 28520, code, deviceName)
                },
                enabled = host.isNotBlank() && code.length == 6 && deviceName.isNotBlank()
                        && pairingState !is PairingUiState.Pairing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (pairingState is PairingUiState.Pairing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Filled.Link, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("配对")
            }

            // 状态显示
            when (val state = pairingState) {
                is PairingUiState.Success -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "已配对至 ${state.serverName}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("WebSocket 连接已建立，可使用远程控制功能。")
                        }
                    }
                }
                is PairingUiState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            state.message,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                else -> {}
            }

            // WebSocket 连接状态
            val connState = connectionState
            if (connState !is ConnectionState.Disconnected && connState !is ConnectionState.Connecting) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "连接状态",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when (connState) {
                                is ConnectionState.Connected -> "已连接: ${connState.serverName}"
                                is ConnectionState.Error -> "错误: ${connState.message}"
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // 本地 IP 信息（帮助用户确认本机 IP）
            val localIp = remember { getLocalIpAddress() }
            if (localIp != null) {
                Text(
                    "本机 IP: $localIp",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** 获取本机局域网 IPv4 地址 */
private fun getLocalIpAddress(): String? {
    return try {
        NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.firstOrNull {
                !it.isLoopbackAddress && it.hostAddress?.contains(":") == false
            }
            ?.hostAddress
    } catch (_: Exception) { null }
}
