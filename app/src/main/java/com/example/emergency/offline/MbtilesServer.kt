package com.example.emergency.offline

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Tiny localhost HTTP server that streams Mapbox-Vector-Tile (.pbf) blobs
 * out of a single MBTiles SQLite file. Bridges the gap between MapLibre
 * Android 10.x (which only speaks `http(s)://` for tile sources) and our
 * on-device vector packs.
 *
 * After Step 3 (plan section 3) callers point this at either the bundled
 * Tier-0 skeleton (`bundled/skeleton.mbtiles`, z0-z6 worldwide) or a
 * downloaded per-region detail pack (`regions/<id>/tiles.mbtiles`,
 * z7-z14). A future change (Step 4/5) will compose multiple sources
 * behind a single endpoint.
 *
 * MBTiles vector tiles are stored gzip-compressed in `tile_data` per the
 * MBTiles 1.3 spec. We forward them as-is with `Content-Encoding: gzip`
 * so MapLibre's tile loader transparently decompresses on read.
 *
 * Bound to 127.0.0.1 with an OS-assigned port. The active port is exposed
 * via [tileUrlTemplate] for the style JSON.
 *
 * Lifecycle: callers should [start] before the map style is loaded and
 * [stop] when the map view is destroyed. The underlying SQLite handle is
 * shared across handler threads; reads are safe under SQLite's default
 * isolation, but the database is opened read-only just to be sure no
 * accidental write ever fires.
 */
class MbtilesServer(
    private val mbtilesFile: File,
) : NanoHTTPD("127.0.0.1", /* port = */ 0) {

    private var db: SQLiteDatabase? = null

    override fun start() {
        if (!mbtilesFile.exists()) {
            error("MBTiles file missing: ${mbtilesFile.absolutePath}")
        }
        // OPEN_READONLY also prevents SQLite from creating a -journal file
        // next to the asset, which is important when the file lives in a
        // shared filesDir we expect to be append-only between version bumps.
        db = SQLiteDatabase.openDatabase(
            mbtilesFile.absolutePath,
            /* factory = */ null,
            SQLiteDatabase.OPEN_READONLY,
        )
        // NanoHTTPD's two-arg start(timeout, daemon). The constant is declared
        // on the superclass; qualify it explicitly because Kotlin doesn't
        // hoist Java statics into subclass scope automatically.
        super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, /* daemon = */ true)
        Log.d(TAG, "MBTiles server listening on $tileUrlTemplate")
    }

    override fun stop() {
        super.stop()
        db?.close()
        db = null
    }

    val tileUrlTemplate: String
        get() = "http://127.0.0.1:$listeningPort/{z}/{x}/{y}.pbf"

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "Request: ${session.uri}")
        val match = TILE_PATH.matchEntire(session.uri)
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "bad path",
            )
        val (zStr, xStr, yStr) = match.destructured
        val z = zStr.toInt()
        val x = xStr.toInt()
        val xyzY = yStr.toInt()
        // MBTiles stores tiles with a flipped y axis (TMS scheme). MapLibre
        // requests in slippy/XYZ; convert.
        val tmsY = (1 shl z) - 1 - xyzY

        val data = readTile(z, x, tmsY)
        if (data == null) {
            Log.w(TAG, "Tile MISS z=$z x=$x tmsY=$tmsY (xyzY=$xyzY)")
            // Empty 204 keeps MapLibre from logging a hard error and lets
            // it overzoom from the nearest available zoom level.
            return newFixedLengthResponse(
                Response.Status.NO_CONTENT, MIME_PBF, "",
            )
        }
        Log.d(TAG, "Tile HIT z=$z x=$x tmsY=$tmsY size=${data.size}")

        return newFixedLengthResponse(
            Response.Status.OK,
            MIME_PBF,
            ByteArrayInputStream(data),
            data.size.toLong(),
        ).apply {
            // Vector MBTiles store the .pbf payload already gzip-compressed.
            // Forwarding the encoding header lets MapLibre / OkHttp do the
            // single decompress on read.
            addHeader("Content-Encoding", "gzip")
            addHeader("Cache-Control", "public, max-age=31536000, immutable")
        }
    }

    private fun readTile(z: Int, x: Int, tmsY: Int): ByteArray? {
        val database = db ?: return null
        return database.rawQuery(
            "SELECT tile_data FROM tiles " +
                "WHERE zoom_level = ? AND tile_column = ? AND tile_row = ? LIMIT 1",
            arrayOf(z.toString(), x.toString(), tmsY.toString()),
        ).use { c ->
            if (c.moveToFirst()) c.getBlob(0) else null
        }
    }

    companion object {
        private const val TAG = "MbtilesServer"
        private const val MIME_PBF = "application/x-protobuf"
        private val TILE_PATH = Regex("""^/(\d+)/(\d+)/(\d+)\.pbf$""")
    }
}
