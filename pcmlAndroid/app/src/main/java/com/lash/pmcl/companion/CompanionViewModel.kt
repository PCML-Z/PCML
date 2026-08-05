package com.lash.pmcl.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PairedDevice(
    val token: String,
    val deviceName: String,
    val serverName: String,
    val host: String,
    val port: Int
)

class CompanionViewModel : ViewModel() {
    val client = CompanionClient(viewModelScope)

    private val _pairingState = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val pairingState: StateFlow<PairingUiState> = _pairingState.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices.asStateFlow()

    /**
     * 发起配对：向 desktopHost:port 发送配对码。
     * 配对码格式：6 位数字（"000000"–"999999"）。
     */
    fun pair(host: String, port: Int, code: String, deviceName: String) {
        _pairingState.value = PairingUiState.Pairing
        viewModelScope.launch {
            val result = client.pair(host, port, code, deviceName)
            if (result != null) {
                val (token, serverName) = result
                val device = PairedDevice(token, deviceName, serverName, host, port)
                _pairedDevices.value = _pairedDevices.value + device
                _pairingState.value = PairingUiState.Success(serverName)
            } else {
                _pairingState.value = PairingUiState.Error("配对失败，请检查配对码与网络连接")
            }
        }
    }

    /** 使用已保存令牌重连 */
    fun reconnect(device: PairedDevice) {
        client.connect(device.host, device.port, device.token)
    }

    /** 断开当前连接 */
    fun disconnect() {
        client.disconnect()
    }

    override fun onCleared() {
        client.destroy()
        super.onCleared()
    }
}

sealed class PairingUiState {
    data object Idle : PairingUiState()
    data object Pairing : PairingUiState()
    data class Success(val serverName: String) : PairingUiState()
    data class Error(val message: String) : PairingUiState()
}
