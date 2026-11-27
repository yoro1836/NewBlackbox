package top.niunaijun.blackboxa.view.fake

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
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import top.niunaijun.blackbox.entity.location.BLocation
import top.niunaijun.blackbox.fake.frameworks.BLocationManager
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.FakeLocationBean
import top.niunaijun.blackboxa.ui.AppRow
import top.niunaijun.blackboxa.ui.AppScaffold
import top.niunaijun.blackboxa.ui.CenterProgress
import top.niunaijun.blackboxa.ui.ConfirmDialog
import top.niunaijun.blackboxa.ui.EmptyState
import top.niunaijun.blackboxa.ui.SearchField
import top.niunaijun.blackboxa.ui.setThemedContent
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.toast

/** Per-app fake location: tap an app to pick a point on the map. */
class FakeManagerActivity : ComponentActivity() {

    private lateinit var viewModel: FakeLocationViewModel
    private val userId get() = intent.getIntExtra("userID", 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this, InjectionUtil.getFakeLocationFactory())[FakeLocationViewModel::class.java]
        viewModel.getInstallAppList(userId)
        setThemedContent { Screen() }
    }

    @Composable
    private fun Screen() {
        val apps by viewModel.appsLiveData.observeAsState()
        var query by remember { mutableStateOf("") }
        var disabling by remember { mutableStateOf<FakeLocationBean?>(null) }
        AppScaffold(title = stringResource(R.string.fake_location), onBack = { finish() }) { padding ->
            Column(Modifier.padding(top = padding.calculateTopPadding())) {
                SearchField(query, { query = it }, stringResource(R.string.search_apps))
                val list = apps
                when {
                    list == null -> CenterProgress()
                    list.isEmpty() -> EmptyState(stringResource(R.string.empty_empty))
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = padding.calculateBottomPadding())) {
                        items(list.filter { it.name.contains(query, true) || it.packageName.contains(query, true) },
                            key = { it.packageName }) { item ->
                            val on = item.fakeLocation != null && item.fakeLocationPattern != BLocationManager.CLOSE_MODE
                            AppRow(
                                icon = item.icon,
                                title = item.name,
                                subtitle = if (on) String.format("%.5f, %.5f", item.fakeLocation!!.latitude, item.fakeLocation!!.longitude)
                                else stringResource(R.string.real_location),
                                onClick = {
                                    locationResult.launch(
                                        Intent(this@FakeManagerActivity, FollowMyLocationOverlay::class.java)
                                            .putExtra("location", item.fakeLocation)
                                            .putExtra("pkg", item.packageName)
                                    )
                                },
                                trailing = if (on) {
                                    { IconButton(onClick = { disabling = item }) { Icon(Icons.Outlined.LocationOff, null) } }
                                } else null
                            )
                        }
                    }
                }
            }
        }
        disabling?.let { item ->
            ConfirmDialog(
                title = stringResource(R.string.close_fake_location),
                text = stringResource(R.string.close_app_fake_location, item.name),
                onConfirm = {
                    BLocationManager.disableFakeLocation(userId, item.packageName)
                    toast(getString(R.string.close_fake_location_success, item.name))
                    viewModel.getInstallAppList(userId)
                },
                onDismiss = { disabling = null }
            )
        }
    }

    private val locationResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != RESULT_OK) return@registerForActivityResult
        val data = it.data ?: return@registerForActivityResult
        val latitude = data.getDoubleExtra("latitude", 0.0)
        val longitude = data.getDoubleExtra("longitude", 0.0)
        val pkg = data.getStringExtra("pkg") ?: return@registerForActivityResult
        viewModel.setPattern(userId, pkg, BLocationManager.OWN_MODE)
        viewModel.setLocation(userId, pkg, BLocation(latitude, longitude))
        toast(getString(R.string.set_location, latitude.toString(), longitude.toString()))
        viewModel.getInstallAppList(userId)
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, FakeManagerActivity::class.java))
        }
    }
}
