package top.niunaijun.blackboxa.view.fake

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import top.niunaijun.blackbox.entity.location.BLocation
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.ui.AppScaffold
import top.niunaijun.blackboxa.ui.setThemedContent

/** Map picker: tap to move the marker, then confirm. */
class FollowMyLocationOverlay : ComponentActivity() {

    private var point by mutableStateOf(GeoPoint(37.5665, 126.9780))
    private var map: MapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        @Suppress("DEPRECATION")
        val location: BLocation? = intent.getParcelableExtra("location")
        if (location != null) point = GeoPoint(location.latitude, location.longitude)

        setThemedContent {
            AppScaffold(
                title = stringResource(R.string.fake_location_pick),
                subtitle = String.format("%.5f, %.5f", point.latitude, point.longitude),
                onBack = { finish() },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = { finishWithResult() },
                        icon = { Icon(Icons.Filled.Check, null) },
                        text = { Text(stringResource(R.string.fake_location_use)) }
                    )
                }
            ) { padding ->
                AndroidView(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    factory = { ctx ->
                        MapView(ctx).also { mv ->
                            map = mv
                            mv.setTileSource(TileSourceFactory.MAPNIK)
                            mv.setMultiTouchControls(true)
                            val marker = Marker(mv)
                            marker.position = point
                            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            mv.overlays.add(marker)
                            mv.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                    point = p
                                    marker.position = p
                                    mv.invalidate()
                                    return true
                                }

                                override fun longPressHelper(p: GeoPoint) = false
                            }))
                            mv.controller.setZoom(12.5)
                            mv.controller.setCenter(point)
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        map?.onResume()
    }

    override fun onPause() {
        super.onPause()
        map?.onPause()
    }

    private fun finishWithResult() {
        intent.putExtra("latitude", point.latitude)
        intent.putExtra("longitude", point.longitude)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }
}
