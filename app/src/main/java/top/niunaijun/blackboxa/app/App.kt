package top.niunaijun.blackboxa.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.gpu.VulkanDriverManager
import top.niunaijun.blackboxa.data.FrameGen
import top.niunaijun.blackboxa.data.LsfgPack


class App : Application() {

    companion object {

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private lateinit var mContext: Context

        @JvmStatic
        fun getContext(): Context {
            return mContext
        }
    }

    override fun attachBaseContext(base: Context?) {
        try {
            super.attachBaseContext(base)

            try {
                BlackBoxCore.get().closeCodeInit()
            } catch (e: Exception) {
                Log.e("App", "Error in closeCodeInit: ${e.message}")
            }

            try {
                BlackBoxCore.get().onBeforeMainApplicationAttach(this, base)
            } catch (e: Exception) {
                Log.e("App", "Error in onBeforeMainApplicationAttach: ${e.message}")
            }

            mContext = base!!

            try {
                AppManager.doAttachBaseContext(base)
            } catch (e: Exception) {
                Log.e("App", "Error in doAttachBaseContext: ${e.message}")
            }

            installFrameGen(mContext)

            try {

                BlackBoxCore.get().onAfterMainApplicationAttach(this, base)

            } catch (e: Exception) {

                Log.e("App", "Error in onAfterMainApplicationAttach: ${e.message}")

            }
        } catch (e: Exception) {
            Log.e("App", "Critical error in attachBaseContext: ${e.message}")
            if (base != null) {
                mContext = base
            }
        }
    }

    /**
     * Runs in every process, the virtual app processes included: there the engine asks it
     * whether to hand the app our Vulkan wrapper (frame generation) instead of libvulkan.
     */
    private fun installFrameGen(base: Context) {
        try {
            VulkanDriverManager.setWrapperProvider(object : VulkanDriverManager.WrapperProvider {
                override fun wrapperFor(packageName: String): String? {
                    if (!FrameGen.get(base, packageName).on) return null
                    return FrameGen.library(base)?.absolutePath
                }

                override fun wrapperConfig(packageName: String): String =
                    "log=${FrameGen.logFile(base, packageName).absolutePath};" +
                        "lsfg=${LsfgPack.file(base).absolutePath};" +
                        FrameGen.get(base, packageName).encode()
            })
        } catch (e: Throwable) {
            Log.e("App", "Frame generation setup failed: ${e.message}")
        }
    }

    override fun onCreate() {
        try {
            super.onCreate()
            AppManager.doOnCreate(mContext)
        } catch (e: Exception) {
            Log.e("App", "Error in onCreate: ${e.message}")
        }
    }
}