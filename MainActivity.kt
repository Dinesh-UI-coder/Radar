package com.example.radarsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.LineString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException

// --- CONFIGURAZIONE CHIAVI (DA INSERIRE PRIVATAMENTE) ---
val client = ""//OPENSKY
val password = ""//OPENSKY
var mapTilerApiKey = ""// Maptiler
var serviceKeyAlpha = "" // AeroDataBox
var serviceKeyBeta = ""  // LogoStream
// -------------------------------------------------------

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { map ->
            map.setStyle("https://api.maptiler.com/maps/streets-v2/style.json?key=$mapTilerApiKey") { style ->
                // Logica inizializzazione mappa...
            }

            map.addOnMapClickListener { point ->
                val pixel = map.projection.toScreenLocation(point)
                val features = map.queryRenderedFeatures(pixel, "aircraft-layer")

                if (features.isNotEmpty()) {
                    val feature = features[0]
                    val icao = feature.getStringProperty("icao24")
                    val callsign = feature.getStringProperty("callsign")

                    Thread {
                        // Chiamata alla funzione di tracciamento depurata
                        val trackJson = parseTrackDetailed(icao, accessToken, callsign)

                        runOnUiThread {
                            if (trackJson != null) {
                                val source = map.style?.getSourceAs<GeoJsonSource>("track-source")
                                source?.setGeoJson(trackJson)
                            }
                        }
                    }.start()
                }
                true
            }
        }
    }

    // --- FUNZIONI DI PARSING E FETCH ---

    fun fetchJson(url: String, token: String? = null): String? {
        val client = OkHttpClient()
        val requestBuilder = Request.Builder().url(url)
        if (token != null) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }
        return try {
            client.newCall(requestBuilder.build()).execute().use { it.body?.string() }
        } catch (e: Exception) {
            null
        }
    }

    fun parseTrack(icao24: String?, token: String?): String? {
        if (icao24 == null) return null
        val url = "https://opensky-network.org/api/tracks/all?icao24=$icao24&time=0"
        val jsonString = fetchJson(url, token) ?: return null

        return try {
            val root = JSONObject(jsonString)
            val path = root.getJSONArray("path")
            val coords = mutableListOf<Point>()
            for (i in 0 until path.length()) {
                val p = path.getJSONArray(i)
                coords.add(Point.fromLngLat(p.getDouble(2), p.getDouble(1)))
            }
            val line = LineString.fromLngLatS(coords)
            val feature = Feature.fromGeometry(line)
            feature.addStringProperty("icao24", icao24)
            FeatureCollection.fromFeature(feature).toJson()
        } catch (e: Exception) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun parseTrackDetailed(icao: String?, token: String?, callsign: String?): String? {
        // Fonte primaria: OpenSky
        val jsonPrimary = parseTrack(icao, token)

        // Fonte secondaria: Dettagli Aeromobile (ex AeroDataBox)
        val aircraftInfo = if (!icao.isNullOrBlank()) {
            fetchAircraftData(icao, serviceKeyAlpha)
        } else null

        val featuresArray = JSONArray()
        try {
            if (jsonPrimary != null) {
                val mainCollection = JSONObject(jsonPrimary).getJSONArray("features")
                for (i in 0 until mainCollection.length()) {
                    val feat = mainCollection.getJSONObject(i)
                    val props = feat.getJSONObject("properties")

                    props.put("source", "integrated_provider")

                    // Integrazione dati extra se disponibili
                    aircraftInfo?.let {
                        val extra = JSONObject(it).optJSONArray("features")?.optJSONObject(0)?.optJSONObject("properties")
                        extra?.keys()?.forEach { key ->
                            props.put(key, extra.get(key))
                        }
                    }
                    featuresArray.put(feat)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        val result = JSONObject()
        result.put("type", "FeatureCollection")
        result.put("features", featuresArray)
        return result.toString()
    }

    // --- HELPER DI RETE ---

    fun fetchAircraftData(icao: String, key: String): String? {
        val url = "https://aerodatabox.p.rapidapi.com/aircrafts/icao24/$icao"
        // Esempio di chiamata con header personalizzati
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .addHeader("x-rapidapi-key", key)
            .build()
        return try {
            client.newCall(request).execute().use { it.body?.string() }
        } catch (e: Exception) { null }
    }

    fun mergeDepartures(airportIcao: String, airportIata: String): String? {
        // Funzione per unire i dati delle partenze senza riferimenti a FR24
        val url = "https://opensky-network.org/api/flights/departure?airport=$airportIcao&begin=0&end=0"
        return fetchJson(url)
    }

    // --- GESTIONE LOGHI E BANDIERE ---

    fun fetchAirlineLogo(icao: String?): String? {
        if (icao.isNullOrBlank()) return null
        val url = "https://api.logostream.dev/airline/$icao?key=$serviceKeyBeta"
        return url // Ritorna l'URL del logo
    }

    fun fetchCountryFlag(countryCode: String?): String? {
        if (countryCode.isNullOrBlank()) return null
        val code = countryCode.lowercase()
        return "https://api.logostream.dev/country/$code?key=$serviceKeyBeta"
    }

    // Lifecycle della MapView
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onDestroy() { super.onDestroy(); mapView.onDestroy() }
}