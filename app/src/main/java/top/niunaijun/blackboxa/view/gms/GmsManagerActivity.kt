package top.niunaijun.blackboxa.view.gms

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Switch
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onDismiss
import com.afollestad.materialdialogs.list.listItems
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.data.MicroGDownloader
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.GmsBean
import top.niunaijun.blackboxa.databinding.ActivityGmsBinding
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.LoadingActivity


class GmsManagerActivity : LoadingActivity() {

    private lateinit var viewModel: GmsViewModel

    private lateinit var mAdapter: RVAdapter<GmsBean>

    private val viewBinding: ActivityGmsBinding by inflate()

    private var progressDialog: MaterialDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.gms_manager, true)
        initViewModel()

        initRecyclerView()
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this, InjectionUtil.getGmsFactory())[GmsViewModel::class.java]
        showLoading()

        viewModel.mInstalledLiveData.observe(this) {
            hideLoading()
            mAdapter.setItems(it)
        }

        viewModel.mUpdateInstalledLiveData.observe(this) { result ->
            if (result == null) {
                return@observe
            }

            val items = mAdapter.getItems()
            for (index in items.indices) {
                val bean = items[index]
                if (bean.userID == result.userID) {
                    if (result.success) {
                        bean.isInstalledGms = !bean.isInstalledGms
                        bean.isMicroG = bean.isInstalledGms && result.isMicroG
                    }
                    mAdapter.replaceAt( index,bean)
                    break
                }
            }

            hideLoading()
            progressDialog?.dismiss()
            progressDialog = null

            if (result.success) {
                toast(result.msg)
            } else {
                MaterialDialog(this).show {
                    title(R.string.gms_manager)
                    message(text = result.msg)
                    positiveButton(R.string.done)
                }
            }
        }

        viewModel.mProgressLiveData.observe(this) { text ->
            progressDialog?.message(text = getString(R.string.microg_hint) + "\n\n" + text)
        }

        viewModel.getInstalledUser()
    }

    private fun initRecyclerView() {
        mAdapter = RVAdapter<GmsBean>(this, GmsAdapter({ openMicroGSettings(it.userID) }, { signOut(it.userID) }))
            .bind(viewBinding.recyclerView)
            .setItemClickListener { view, item, _ ->
                val checkbox = view.findViewById<Switch>(R.id.checkbox)
                if (item.isInstalledGms) {
                    uninstallGms(item.userID, checkbox)
                } else {
                    installGms(item.userID, checkbox)
                }
            }
        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)

    }

    private fun installGms(userID: Int, checkbox: Switch){
        var chosen = false
        MaterialDialog(this).show {
            title(R.string.gms_choose)
            listItems(items = listOf(
                getString(R.string.gms_choose_microg, MicroGDownloader.VERSION),
                getString(R.string.gms_choose_host)
            )) { _, index, _ ->
                chosen = true
                if (index == 0) {
                    installMicroG(userID)
                } else {
                    installHostGms(userID, checkbox)
                }
            }
            negativeButton(R.string.cancel)
            onDismiss {
                if (!chosen) {
                    checkbox.isChecked = !checkbox.isChecked
                }
            }
        }
    }

    private fun installMicroG(userID: Int) {
        progressDialog = MaterialDialog(this).show {
            title(R.string.gms_manager)
            message(text = getString(R.string.microg_hint))
            cancelable(false)
            cancelOnTouchOutside(false)
        }
        viewModel.installMicroG(userID, cacheDir)
    }

    private fun installHostGms(userID: Int, checkbox: Switch){
        MaterialDialog(this).show {
            title(R.string.enable_gms)
            message(R.string.enable_gms_hint)
            positiveButton(R.string.done){
                showLoading()
                viewModel.installGms(userID)
            }
            negativeButton(R.string.cancel){
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }

    private fun signOut(userID: Int) {
        val accounts = try {
            BlackBoxCore.get().getGoogleAccounts(userID)
        } catch (e: Throwable) {
            emptyArray()
        }
        if (accounts.isEmpty()) {
            toast(getString(R.string.google_no_accounts))
            return
        }
        MaterialDialog(this).show {
            title(R.string.google_sign_out)
            listItems(items = accounts.map { it.name }) { _, index, _ ->
                confirmSignOut(userID, accounts[index])
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun confirmSignOut(userID: Int, account: android.accounts.Account) {
        MaterialDialog(this).show {
            title(R.string.google_sign_out)
            message(text = getString(R.string.google_sign_out_confirm, account.name))
            positiveButton(R.string.done) {
                val ok = try {
                    BlackBoxCore.get().removeGoogleAccount(account, userID)
                } catch (e: Throwable) {
                    false
                }
                toast(getString(if (ok) R.string.google_signed_out else R.string.google_sign_out_failed))
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun openMicroGSettings(userID: Int) {
        val ok = try {
            BlackBoxCore.get().launchApk("com.google.android.gms", userID)
        } catch (e: Throwable) {
            false
        }
        if (!ok) {
            toast(getString(R.string.microg_settings_failed))
        }
    }

    private fun uninstallGms(userID: Int, checkbox: Switch){
        MaterialDialog(this).show {
            title(R.string.disable_gms)
            message(R.string.disable_gms_hint)
            positiveButton(R.string.done){
                showLoading()
                viewModel.uninstallGms(userID)
            }
            negativeButton(R.string.cancel){
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }


    companion object{
        fun start(context: Context){
            val intent = Intent(context,GmsManagerActivity::class.java)
            context.startActivity(intent)
        }
    }
}