package com.delifar.app.ui.components

import android.graphics.Color as AndroidColor
import android.graphics.RectF
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.delifar.app.network.GeoPoint
import com.delifar.app.network.GeoValidation
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

data class MapStorePin(
    val storeId: String,
    val title: String,
    val lat: Double,
    val lon: Double,
    val colorHex: String,
    val activeJobCount: Int
)

private const val ROUTE_SOURCE_ID = "delifar-route-source"
private const val ROUTE_LAYER_ID = "delifar-route-layer"
private const val PINS_SOURCE_ID = "delifar-pins-source"
private const val PINS_CLUSTER_LAYER_ID = "delifar-pins-cluster-layer"
private const val PINS_CLUSTER_COUNT_LAYER_ID = "delifar-pins-cluster-count-layer"
private const val PINS_HALO_LAYER_ID = "delifar-pins-halo-layer"
private const val PINS_CIRCLE_LAYER_ID = "delifar-pins-circle-layer"
private const val PINS_LABEL_LAYER_ID = "delifar-pins-label-layer"

private const val PICKER_SOURCE_ID = "delifar-picker-source"
private const val PICKER_HALO_LAYER_ID = "delifar-picker-halo-layer"
private const val PICKER_DOT_LAYER_ID = "delifar-picker-dot-layer"

@Composable
private fun rememberLifecycleManagedMapView(): MapView {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
        }
    }
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { mapView.onPause() }
            runCatching { mapView.onStop() }
            runCatching { mapView.onDestroy() }
        }
    }
    return mapView
}

/**
 * Native MapLibre fleet & delivery map backed by free OpenFreeMap vector tiles.
 */
@Composable
fun DeliFarMapLibreView(
    styleUrl: String,
    pins: List<MapStorePin>,
    selectedStoreId: String?,
    routePoints: List<GeoPoint>,
    isStreetRoute: Boolean,
    defaultCenter: GeoPoint,
    fitTriggerKey: Any?,
    onSelectStore: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val mapView = rememberLifecycleManagedMapView()
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyleUrl by remember { mutableStateOf<String?>(null) }
    var styleReady by remember { mutableStateOf(false) }

    val latestOnSelect by rememberUpdatedState(onSelectStore)
    val latestPins by rememberUpdatedState(pins)
    val latestSelectedId by rememberUpdatedState(selectedStoreId)
    val latestRoute by rememberUpdatedState(routePoints)
    val latestStreetRoute by rememberUpdatedState(isStreetRoute)

    fun syncOverlayData(style: Style) {
        // Update pins GeoJSON
        val pinFeatures = latestPins.filter { GeoValidation.isValidCoordinate(it.lat, it.lon) }.map { pin ->
            Feature.fromGeometry(Point.fromLngLat(pin.lon, pin.lat)).apply {
                addStringProperty("storeId", pin.storeId)
                addStringProperty("title", pin.title)
                addStringProperty("color", pin.colorHex)
                addBooleanProperty("selected", pin.storeId == latestSelectedId)
                addNumberProperty("jobs", pin.activeJobCount)
            }
        }
        val pinCollection = FeatureCollection.fromFeatures(pinFeatures)
        val existingPinSource = style.getSourceAs<GeoJsonSource>(PINS_SOURCE_ID)
        if (existingPinSource != null) {
            existingPinSource.setGeoJson(pinCollection)
        } else {
            val clusterOptions = GeoJsonOptions()
                .withCluster(true)
                .withClusterMaxZoom(13)
                .withClusterRadius(44)
            style.addSource(GeoJsonSource(PINS_SOURCE_ID, pinCollection, clusterOptions))
        }

        // Update route GeoJSON
        val validRoute = latestRoute.filter { it.isValid() }
        val routeCollection = if (validRoute.size >= 2) {
            val line = LineString.fromLngLats(validRoute.map { Point.fromLngLat(it.lon, it.lat) })
            FeatureCollection.fromFeature(Feature.fromGeometry(line))
        } else {
            FeatureCollection.fromFeatures(emptyList())
        }
        val existingRouteSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
        if (existingRouteSource != null) {
            existingRouteSource.setGeoJson(routeCollection)
        } else {
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, routeCollection))
        }

        if (style.getLayer(ROUTE_LAYER_ID) == null) {
            val routeLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(AndroidColor.parseColor("#183A37")),
                PropertyFactory.lineWidth(5.0f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOpacity(0.9f)
            )
            style.addLayer(routeLayer)
        }
        (style.getLayer(ROUTE_LAYER_ID) as? LineLayer)?.setProperties(
            PropertyFactory.lineDasharray(if (latestStreetRoute) arrayOf(1f, 0f) else arrayOf(2.5f, 1.5f))
        )

        if (style.getLayer(PINS_CLUSTER_LAYER_ID) == null) {
            val clusterLayer = CircleLayer(PINS_CLUSTER_LAYER_ID, PINS_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(
                    Expression.step(
                        Expression.get("point_count"),
                        Expression.literal(17.0f),
                        Expression.stop(10, 20.0f),
                        Expression.stop(50, 24.0f)
                    )
                ),
                PropertyFactory.circleColor(AndroidColor.parseColor("#183A37")),
                PropertyFactory.circleStrokeWidth(2.5f),
                PropertyFactory.circleStrokeColor(AndroidColor.WHITE)
            )
            clusterLayer.setFilter(Expression.has("point_count"))
            style.addLayer(clusterLayer)
        }

        if (style.getLayer(PINS_CLUSTER_COUNT_LAYER_ID) == null) {
            val clusterCountLayer = SymbolLayer(PINS_CLUSTER_COUNT_LAYER_ID, PINS_SOURCE_ID).withProperties(
                PropertyFactory.textField(Expression.toString(Expression.get("point_count"))),
                PropertyFactory.textSize(12.5f),
                PropertyFactory.textColor(AndroidColor.WHITE),
                PropertyFactory.textIgnorePlacement(true),
                PropertyFactory.textAllowOverlap(true)
            )
            clusterCountLayer.setFilter(Expression.has("point_count"))
            style.addLayer(clusterCountLayer)
        }

        if (style.getLayer(PINS_HALO_LAYER_ID) == null) {
            val haloLayer = CircleLayer(PINS_HALO_LAYER_ID, PINS_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(
                    Expression.switchCase(
                        Expression.eq(Expression.get("selected"), Expression.literal(true)),
                        Expression.literal(16.0f),
                        Expression.literal(11.5f)
                    )
                ),
                PropertyFactory.circleColor(
                    Expression.switchCase(
                        Expression.eq(Expression.get("selected"), Expression.literal(true)),
                        Expression.literal("#D97706"),
                        Expression.literal("#FFFFFF")
                    )
                ),
                PropertyFactory.circleOpacity(0.95f)
            )
            haloLayer.setFilter(Expression.not(Expression.has("point_count")))
            style.addLayer(haloLayer)
        }

        if (style.getLayer(PINS_CIRCLE_LAYER_ID) == null) {
            val circleLayer = CircleLayer(PINS_CIRCLE_LAYER_ID, PINS_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(
                    Expression.switchCase(
                        Expression.eq(Expression.get("selected"), Expression.literal(true)),
                        Expression.literal(11.0f),
                        Expression.literal(8.5f)
                    )
                ),
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleStrokeWidth(2.0f),
                PropertyFactory.circleStrokeColor(AndroidColor.WHITE)
            )
            circleLayer.setFilter(Expression.not(Expression.has("point_count")))
            style.addLayer(circleLayer)
        }

        if (style.getLayer(PINS_LABEL_LAYER_ID) == null) {
            val labelLayer = SymbolLayer(PINS_LABEL_LAYER_ID, PINS_SOURCE_ID).withProperties(
                PropertyFactory.textField(Expression.get("title")),
                PropertyFactory.textSize(12.0f),
                PropertyFactory.textOffset(arrayOf(0f, 1.55f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textColor(AndroidColor.parseColor("#111827")),
                PropertyFactory.textHaloColor(AndroidColor.WHITE),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.textAllowOverlap(false)
            )
            labelLayer.setFilter(Expression.not(Expression.has("point_count")))
            style.addLayer(labelLayer)
        }
    }

    fun fitCameraToPins(map: MapLibreMap) {
        val valid = latestPins.filter { GeoValidation.isValidCoordinate(it.lat, it.lon) }
        when {
            valid.size == 1 -> {
                val p = valid.first()
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(p.lat, p.lon))
                            .zoom(13.8)
                            .build()
                    ),
                    450
                )
            }
            valid.size > 1 -> {
                val builder = LatLngBounds.Builder()
                valid.forEach { builder.include(LatLng(it.lat, it.lon)) }
                runCatching {
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 120), 500)
                }
            }
            else -> {
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(defaultCenter.lat, defaultCenter.lon))
                            .zoom(10.5)
                            .build()
                    ),
                    350
                )
            }
        }
    }

    LaunchedEffect(mapLibreMap, styleUrl) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (loadedStyleUrl != styleUrl) {
            styleReady = false
            loadedStyleUrl = styleUrl
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                styleReady = true
                syncOverlayData(style)
                fitCameraToPins(map)
            }
        }
    }

    LaunchedEffect(styleReady, pins, selectedStoreId, routePoints, isStreetRoute) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        if (style.isFullyLoaded) {
            syncOverlayData(style)
        }
    }

    LaunchedEffect(styleReady, fitTriggerKey, pins.size) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (styleReady) {
            fitCameraToPins(map)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
                    getMapAsync { map ->
                        mapLibreMap = map
                        map.uiSettings.apply {
                            isCompassEnabled = true
                            isAttributionEnabled = true
                            isLogoEnabled = false
                            isRotateGesturesEnabled = true
                            isTiltGesturesEnabled = false
                        }
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(defaultCenter.lat, defaultCenter.lon))
                            .zoom(10.5)
                            .build()

                        map.addOnMapClickListener { latLng ->
                            val screenPoint = map.projection.toScreenLocation(latLng)
                            val hitBox = RectF(
                                screenPoint.x - 40f,
                                screenPoint.y - 40f,
                                screenPoint.x + 40f,
                                screenPoint.y + 40f
                            )
                            val clusterHits = map.queryRenderedFeatures(hitBox, PINS_CLUSTER_LAYER_ID)
                            if (clusterHits.isNotEmpty()) {
                                val nextZoom = (map.cameraPosition.zoom + 2.2).coerceAtMost(16.0)
                                map.animateCamera(
                                    CameraUpdateFactory.newCameraPosition(
                                        CameraPosition.Builder()
                                            .target(latLng)
                                            .zoom(nextZoom)
                                            .build()
                                    ),
                                    320
                                )
                                return@addOnMapClickListener true
                            }
                            val hitFeatures = map.queryRenderedFeatures(hitBox, PINS_CIRCLE_LAYER_ID, PINS_HALO_LAYER_ID)
                            val storeId = hitFeatures.firstOrNull()?.getStringProperty("storeId")
                            if (!storeId.isNullOrBlank()) {
                                latestOnSelect(storeId)
                            } else {
                                latestOnSelect(null)
                            }
                            true
                        }

                        if (loadedStyleUrl != styleUrl) {
                            loadedStyleUrl = styleUrl
                            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                                styleReady = true
                                syncOverlayData(style)
                                fitCameraToPins(map)
                            }
                        }
                    }
                }
            }
        )

        // One-handed zoom & recenter controls (44dp comfortable touch targets)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shadowElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(
                    onClick = { mapLibreMap?.animateCamera(CameraUpdateFactory.zoomIn(), 250) },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Zoom in", modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = { mapLibreMap?.animateCamera(CameraUpdateFactory.zoomOut(), 250) },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Zoom out", modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = { mapLibreMap?.let { fitCameraToPins(it) } },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = "Fit stops", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * Interactive MapLibre pin preview & manual adjustment map for StoreEditorScreen.
 * Users can view the geocoded pin, tap anywhere on the map to adjust/place the pin manually,
 * zoom in for rooftop precision, and then confirm the coordinates before saving.
 */
@Composable
fun StorePinPickerMap(
    styleUrl: String,
    pin: GeoPoint?,
    confirmed: Boolean,
    fallbackCenter: GeoPoint,
    cameraFocusNonce: Int,
    onPinMoved: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier
) {
    val mapView = rememberLifecycleManagedMapView()
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyleUrl by remember { mutableStateOf<String?>(null) }
    var styleReady by remember { mutableStateOf(false) }

    val latestOnPinMoved by rememberUpdatedState(onPinMoved)
    val latestPin by rememberUpdatedState(pin)
    val latestConfirmed by rememberUpdatedState(confirmed)

    fun syncPin(style: Style) {
        val currentPin = latestPin
        val fc = if (currentPin != null && currentPin.isValid()) {
            val feat = Feature.fromGeometry(Point.fromLngLat(currentPin.lon, currentPin.lat)).apply {
                addBooleanProperty("confirmed", latestConfirmed)
            }
            FeatureCollection.fromFeature(feat)
        } else {
            FeatureCollection.fromFeatures(emptyList())
        }
        val existing = style.getSourceAs<GeoJsonSource>(PICKER_SOURCE_ID)
        if (existing != null) {
            existing.setGeoJson(fc)
        } else {
            style.addSource(GeoJsonSource(PICKER_SOURCE_ID, fc))
        }

        if (style.getLayer(PICKER_HALO_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(PICKER_HALO_LAYER_ID, PICKER_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(18.0f),
                    PropertyFactory.circleColor(
                        Expression.switchCase(
                            Expression.eq(Expression.get("confirmed"), Expression.literal(true)),
                            Expression.literal("#15803D"),
                            Expression.literal("#D97706")
                        )
                    ),
                    PropertyFactory.circleOpacity(0.28f)
                )
            )
        }
        if (style.getLayer(PICKER_DOT_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(PICKER_DOT_LAYER_ID, PICKER_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(10.0f),
                    PropertyFactory.circleColor(
                        Expression.switchCase(
                            Expression.eq(Expression.get("confirmed"), Expression.literal(true)),
                            Expression.literal("#15803D"),
                            Expression.literal("#D97706")
                        )
                    ),
                    PropertyFactory.circleStrokeWidth(2.5f),
                    PropertyFactory.circleStrokeColor(AndroidColor.WHITE)
                )
            )
        }
    }

    LaunchedEffect(mapLibreMap, styleUrl) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (loadedStyleUrl != styleUrl) {
            styleReady = false
            loadedStyleUrl = styleUrl
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                styleReady = true
                syncPin(style)
            }
        }
    }

    LaunchedEffect(styleReady, pin, confirmed) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        if (style.isFullyLoaded) {
            syncPin(style)
        }
    }

    LaunchedEffect(styleReady, cameraFocusNonce) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val target = pin?.takeIf { it.isValid() } ?: fallbackCenter
        val zoom = if (pin != null && pin.isValid()) 16.0 else 10.5
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(target.lat, target.lon))
                    .zoom(zoom)
                    .build()
            ),
            450
        )
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
                    // Prevent outer LazyColumn from intercepting map drags/gestures
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                    getMapAsync { map ->
                        mapLibreMap = map
                        map.uiSettings.apply {
                            isCompassEnabled = false
                            isLogoEnabled = false
                            isAttributionEnabled = true
                            isTiltGesturesEnabled = false
                        }
                        val initial = latestPin?.takeIf { it.isValid() } ?: fallbackCenter
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(initial.lat, initial.lon))
                            .zoom(if (latestPin != null) 16.0 else 10.5)
                            .build()

                        map.addOnMapClickListener { latLng ->
                            if (GeoValidation.isValidCoordinate(latLng.latitude, latLng.longitude)) {
                                latestOnPinMoved(GeoPoint(latLng.latitude, latLng.longitude))
                            }
                            true
                        }

                        if (loadedStyleUrl != styleUrl) {
                            loadedStyleUrl = styleUrl
                            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                                styleReady = true
                                syncPin(style)
                            }
                        }
                    }
                }
            }
        )

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shadowElevation = 3.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(
                    onClick = { mapLibreMap?.animateCamera(CameraUpdateFactory.zoomIn(), 200) },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Zoom in", modifier = Modifier.size(16.dp))
                }
                IconButton(
                    onClick = { mapLibreMap?.animateCamera(CameraUpdateFactory.zoomOut(), 200) },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Zoom out", modifier = Modifier.size(16.dp))
                }
                if (pin != null && pin.isValid()) {
                    IconButton(
                        onClick = {
                            mapLibreMap?.animateCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(LatLng(pin.lat, pin.lon))
                                        .zoom(16.5)
                                        .build()
                                ),
                                300
                            )
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Center on pin", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
