package top.niunaijun.blackboxa.view.setting

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.gms.GmsManagerActivity

class SettingFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.setting, rootKey)


        invalidHideState {
            val rootHidePreference: Preference = (findPreference("root_hide")!!)
            val hideRoot = AppManager.mBlackBoxLoader.hideRoot()
            rootHidePreference.setDefaultValue(hideRoot)
            rootHidePreference
        }

        invalidHideState {
            val daemonPreference: Preference = (findPreference("daemon_enable")!!)
            val mDaemonEnable = AppManager.mBlackBoxLoader.daemonEnable()
            daemonPreference.setDefaultValue(mDaemonEnable)
            daemonPreference
        }

        invalidHideState {
            val vpnPreference: Preference = (findPreference("use_vpn_network")!!)
            val mUseVpnNetwork = AppManager.mBlackBoxLoader.useVpnNetwork()
            vpnPreference.setDefaultValue(mUseVpnNetwork)
            vpnPreference
        }

        invalidHideState {
            val disableFlagSecurePreference: Preference = (findPreference("disable_flag_secure")!!)
            val mDisableFlagSecure = AppManager.mBlackBoxLoader.disableFlagSecure()
            disableFlagSecurePreference.setDefaultValue(mDisableFlagSecure)
            disableFlagSecurePreference
        }
        initSendLogs()
        initSpoof()
        initGms()
        initXposed()
    }

    private fun initXposed() {
        findPreference<Preference>("xposed_manager")?.setOnPreferenceClickListener {
            top.niunaijun.blackboxa.view.xposed.XposedModulesActivity.start(requireContext())
            true
        }
    }

    private fun initGms() {
        val gmsManagerPreference: Preference = (findPreference("gms_manager")!!)
        gmsManagerPreference.setOnPreferenceClickListener {
            GmsManagerActivity.start(requireContext())
            true
        }
    }

    private fun initSpoof() {
        try {
            val current = top.niunaijun.blackbox.core.spoof.SpoofManager.loadProfile()
            val keys = mapOf(
                    "spoof_model" to "ro.product.model",
                    "spoof_brand" to "ro.product.brand",
                    "spoof_manufacturer" to "ro.product.manufacturer",
                    "spoof_device" to "ro.product.device",
                    "spoof_fingerprint" to "ro.build.fingerprint"
            )
            for ((prefKey, propKey) in keys) {
                val pref: androidx.preference.EditTextPreference? = findPreference(prefKey)
                pref ?: continue
                pref.text = current[propKey]
                pref.summary = current[propKey]
                pref.setOnPreferenceChangeListener { _, newValue ->
                    try {
                        val updated = top.niunaijun.blackbox.core.spoof.SpoofManager
                                .loadProfile().toMutableMap()
                        val text = (newValue as? String).orEmpty()
                        if (text.isEmpty()) {
                            updated.remove(propKey)
                        } else {
                            updated[propKey] = text
                        }
                        top.niunaijun.blackbox.core.spoof.SpoofManager.saveProfile(updated)
                        pref.summary = text.ifEmpty { null }
                    } catch (e: Exception) {
                        android.util.Log.e("SettingFragment", "spoof save failed: ${e.message}")
                    }
                    true
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SettingFragment", "spoof init failed: ${e.message}")
        }
    }

    private fun invalidHideState(block: () -> Preference) {
        val pref = block()
        pref.setOnPreferenceChangeListener { preference, newValue ->
            val tmpHide = (newValue == true)
            when (preference.key) {
                "root_hide" -> {

                    AppManager.mBlackBoxLoader.invalidHideRoot(tmpHide)
                }
                "daemon_enable" -> {
                    AppManager.mBlackBoxLoader.invalidDaemonEnable(tmpHide)
                }
                "use_vpn_network" -> {
                    AppManager.mBlackBoxLoader.invalidUseVpnNetwork(tmpHide)
                }
                "disable_flag_secure" -> {
                    AppManager.mBlackBoxLoader.invalidDisableFlagSecure(tmpHide)
                }
            }

            toast(R.string.restart_module)
            return@setOnPreferenceChangeListener true
        }
    }
    private fun initSendLogs() {
        val sendLogsPreference: Preference? = findPreference("send_logs")
        sendLogsPreference?.setOnPreferenceClickListener {
            it.isEnabled = false
            BlackBoxCore.get()
                    .sendLogs(
                            "Manual Log Upload from Settings",
                            true,
                            object : BlackBoxCore.LogSendListener {
                                override fun onSuccess() {
                                    activity?.runOnUiThread { sendLogsPreference.isEnabled = true }
                                }

                                override fun onFailure(error: String?) {
                                    activity?.runOnUiThread { sendLogsPreference.isEnabled = true }
                                }
                            }
                    )
            toast("Sending logs... (Check notifications for status)")
            true
        }
    }
}
