package top.niunaijun.blackboxa.data

import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.bean.GmsBean
import top.niunaijun.blackboxa.bean.GmsInstallBean
import top.niunaijun.blackboxa.util.getString


class GmsRepository {


    fun getGmsInstalledList(mInstalledLiveData: MutableLiveData<List<GmsBean>>) {
        val userList = arrayListOf<GmsBean>()

        val userIds = BlackBoxCore.get().users.map { it.id }.ifEmpty { listOf(0) }
        userIds.forEach { userId ->
            val userName =
                AppManager.mRemarkSharedPreferences.getString("Remark$userId", "User $userId") ?: ""
            val isInstalled = BlackBoxCore.get().isInstallGms(userId)
            val isMicroG = isInstalled && BlackBoxCore.get().isMicroG(userId)
            val bean = GmsBean(userId, userName, isInstalled, isMicroG)
            userList.add(bean)
        }

        mInstalledLiveData.postValue(userList)
    }

    fun installGms(
        userID: Int,
        mUpdateInstalledLiveData: MutableLiveData<GmsInstallBean>
    ) {
        val installResult = BlackBoxCore.get().installGms(userID)

        val result = if (installResult.success) {
            getString(R.string.install_success)
        } else {
            getString(R.string.install_fail, installResult.msg)
        }

        val bean = GmsInstallBean(userID,installResult.success,result)
        mUpdateInstalledLiveData.postValue(bean)
    }


    /** Downloads (or reuses) the pinned microG APKs and installs them for the user. */
    fun installMicroG(
        userID: Int,
        cacheDir: java.io.File,
        progress: (String) -> Unit,
        mUpdateInstalledLiveData: MutableLiveData<GmsInstallBean>
    ) {
        val msg = try {
            val dir = java.io.File(cacheDir, "microg")
            val apks = listOf(MicroGDownloader.GMS_CORE, MicroGDownloader.COMPANION).map { apk ->
                MicroGDownloader.fetch(apk, dir) { done, total ->
                    progress(getString(R.string.microg_downloading, apk.label,
                        (done / (1024 * 1024)).toString(), (total / (1024 * 1024)).toString()))
                }
            }
            progress(getString(R.string.microg_installing))
            val result = BlackBoxCore.get().installMicroG(userID, apks[0], apks[1])
            if (result.success) {
                dir.deleteRecursively()
                null
            } else {
                result.msg ?: "?"
            }
        } catch (e: Throwable) {
            e.toString()
        }
        val bean = if (msg == null) {
            GmsInstallBean(userID, true, getString(R.string.install_success), true)
        } else {
            GmsInstallBean(userID, false, getString(R.string.install_fail, msg), true)
        }
        mUpdateInstalledLiveData.postValue(bean)
    }

    fun uninstallGms(
        userID: Int,
        mUpdateInstalledLiveData: MutableLiveData<GmsInstallBean>
    ) {
        var isSuccess = false
        if (BlackBoxCore.get().isInstallGms(userID)) {
            isSuccess = BlackBoxCore.get().uninstallGms(userID)
        }

        val result = if (isSuccess) {
            getString(R.string.uninstall_success)
        } else {
            getString(R.string.uninstall_fail)
        }

        val bean = GmsInstallBean(userID,isSuccess,result)

        mUpdateInstalledLiveData.postValue(bean)
    }
}