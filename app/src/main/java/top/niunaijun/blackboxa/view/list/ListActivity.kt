package top.niunaijun.blackboxa.view.list

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.ui.AppRow
import top.niunaijun.blackboxa.ui.AppScaffold
import top.niunaijun.blackboxa.ui.CenterProgress
import top.niunaijun.blackboxa.ui.EmptyState
import top.niunaijun.blackboxa.ui.SearchField
import top.niunaijun.blackboxa.ui.setThemedContent
import top.niunaijun.blackboxa.util.InjectionUtil

/** Picks a device app (or an APK file) to clone into a virtual user. */
class ListActivity : ComponentActivity() {

    private lateinit var viewModel: ListViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this, InjectionUtil.getListFactory())[ListViewModel::class.java]
        viewModel.getInstallAppList(intent.getIntExtra("userID", 0))
        setThemedContent { ListScreen() }
    }

    @Composable
    private fun ListScreen() {
        val apps by viewModel.appsLiveData.observeAsState()
        var query by remember { mutableStateOf("") }
        AppScaffold(
            title = stringResource(R.string.installed_app),
            onBack = { finish() },
            actions = {
                IconButton(onClick = { pickApk.launch("application/vnd.android.package-archive") }) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.choose))
                }
            }
        ) { padding ->
            Column(Modifier.padding(top = padding.calculateTopPadding())) {
                SearchField(query, { query = it }, stringResource(R.string.search_apps))
                val list = apps
                when {
                    list == null -> CenterProgress()
                    list.isEmpty() -> EmptyState(stringResource(R.string.empty_empty))
                    else -> {
                        val shown = list.filter {
                            it.name.contains(query, true) || it.packageName.contains(query, true)
                        }
                        LazyColumn(contentPadding = PaddingValues(bottom = padding.calculateBottomPadding())) {
                            items(shown, key = { it.packageName }) { app ->
                                AppRow(
                                    icon = app.icon,
                                    title = app.name,
                                    subtitle = app.packageName,
                                    onClick = { finishWithResult(app.packageName) },
                                    trailing = if (app.isInstall) {
                                        { AssistChip(onClick = {}, label = { Text(stringResource(R.string.installed_badge)) }) }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private val pickApk = registerForActivityResult(ActivityResultContracts.GetContent()) {
        it?.let { uri -> finishWithResult(uri.toString()) }
    }

    private fun finishWithResult(source: String) {
        intent.putExtra("source", source)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ListActivity::class.java))
        }
    }
}
