package com.october.fixer

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** [onPick] starts a pick and reports its outcome; it returns the cancel flag, or null if nothing started. */
internal class ServerPicker(
    view: View,
    private val dialog: Dialog,
    private val onPick: (Long, (PickResult) -> Unit) -> AtomicBoolean?,
) {
    private val input = view.findViewById<EditText>(R.id.pickerQuery)
    private val status = view.findViewById<TextView>(R.id.pickerStatus)
    private val empty = view.findViewById<TextView>(R.id.pickerEmpty)
    private val list = view.findViewById<ListView>(R.id.pickerResults)
    private val handler = Handler(Looper.getMainLooper())
    private val search = Executors.newFixedThreadPool(2)
    private val images = Executors.newFixedThreadPool(3)
    private val cache = LruCache<String, Bitmap>(32)
    private val imageJobs = HashMap<String, Future<*>>()
    private val missing = HashSet<String>()
    private var pending: AtomicBoolean? = null
    private var revision = 0
    private var searchJob: Future<*>? = null
    private var places = emptyList<RobloxCatalog.Place>()
    private var iconUrls = emptyMap<Long, String>()
    private val adapter = PlaceAdapter(LayoutInflater.from(view.context))
    private val prefs = view.context.getSharedPreferences(FixVpnService.PREFS, Context.MODE_PRIVATE)

    init {
        list.adapter = adapter
        list.setOnItemClickListener { _, _, index, _ -> places.getOrNull(index)?.let { pick(it.id) } }
        view.findViewById<View>(R.id.pickerClose).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.pickerSearch).setOnClickListener { submit() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            submit()
            true
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = update(s.toString().trim(), 350)
        })
        dialog.setOnDismissListener {
            pending?.set(true)
            handler.removeCallbacksAndMessages(null)
            search.shutdownNow()
            images.shutdownNow()
            imageJobs.clear()
            cache.evictAll()
        }
        view.requestFocus()
        update("", 0)
    }

    private fun loadRecents(): List<RobloxCatalog.Place> =
        RecentPlaces.decode(prefs.getString(KEY_RECENT, null)).ifEmpty { listOf(MM2) }

    private fun remember(id: Long) {
        val recents = loadRecents()
        val place = (places + recents).firstOrNull { it.id == id } ?: RobloxCatalog.Place(id, "Плейс $id")
        prefs.edit().putString(KEY_RECENT, RecentPlaces.encode(RecentPlaces.add(recents, place))).apply()
    }

    private fun showRecents(current: Int) {
        val shown = loadRecents()
        places = shown
        status.text = "Недавние"
        empty.visibility = View.GONE
        adapter.notifyDataSetChanged()
        searchJob = search.submit {
            val urls = RobloxCatalog.iconUrls(shown)
            handler.post {
                if (isCurrent(current)) {
                    iconUrls = urls
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    /** The dialog stays open until the server list answers; only a found server closes it. */
    private fun pick(id: Long) {
        hideKeyboard()
        if (pending != null) return
        status.text = "Ищу сервер…"
        pending = onPick(id) { result ->
            pending = null
            if (!dialog.isShowing) return@onPick
            if (result is PickResult.Server) {
                remember(id)
                dialog.dismiss()
            } else {
                status.text = message(result)
                empty.visibility = View.GONE // the idle hint «Нажми «Найти»» would sit under the error
            }
        }
        if (pending == null) status.text = "Игры Roblox"
    }

    private fun hideKeyboard() {
        val keyboard = input.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun submit() {
        val query = input.text.toString().trim()
        val id = RobloxCatalog.extractPlaceId(query)
        if (id != null) {
            pick(id)
        } else {
            hideKeyboard()
            if (places.isEmpty()) update(query, 0)
        }
    }

    private fun update(query: String, delay: Long) {
        val current = ++revision
        handler.removeCallbacksAndMessages(null)
        searchJob?.cancel(true)
        imageJobs.values.forEach { it.cancel(true) }
        imageJobs.clear()
        missing.clear()
        places = emptyList()
        iconUrls = emptyMap()
        adapter.notifyDataSetChanged()
        if (query.isEmpty()) {
            showRecents(current)
            return
        }
        empty.visibility = View.VISIBLE

        val placeId = RobloxCatalog.extractPlaceId(query)
        status.text = "Игры Roblox"
        empty.text = when {
            placeId != null -> "Плейс $placeId. Нажми «Найти», чтобы подобрать сервер."
            query.length < 2 -> "Введи хотя бы две буквы."
            query.length > 100 || query.contains("://") || query.startsWith("//") -> "Вставь ссылку на игру Roblox или введи название."
            else -> null
        }
        if (!empty.text.isNullOrEmpty()) return

        empty.text = "Ищу похожие игры…"
        handler.postDelayed({
            if (!isCurrent(current)) return@postDelayed
            searchJob = search.submit {
                val found = RobloxCatalog.query(query)
                handler.post { if (isCurrent(current)) showResults(found) }
                if (found.isNullOrEmpty() || Thread.currentThread().isInterrupted) return@submit
                val urls = RobloxCatalog.iconUrls(found)
                handler.post {
                    if (isCurrent(current)) {
                        iconUrls = urls
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }, delay)
    }

    private fun isCurrent(id: Int): Boolean = dialog.isShowing && revision == id

    private fun showResults(found: List<RobloxCatalog.Place>?) {
        places = found.orEmpty()
        status.text = if (places.isEmpty()) "Игры Roblox" else "Похожие игры · ${places.size}"
        empty.text = if (found == null) "Roblox не ответил. Попробуй ещё раз."
            else "Совпадений нет. Попробуй другое название или ID плейса."
        empty.visibility = if (places.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        list.setSelection(0)
    }

    private fun photo(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        if (url in imageJobs || url in missing || !dialog.isShowing) return null
        val current = revision
        imageJobs[url] = images.submit {
            val bitmap = RobloxImages.load(url)
            handler.post {
                if (!isCurrent(current)) return@post
                imageJobs.remove(url)
                if (bitmap == null) {
                    missing.add(url)
                } else {
                    cache.put(url, bitmap)
                    for (i in 0 until list.childCount) {
                        val image = list.getChildAt(i).findViewById<ImageView>(R.id.placePhoto)
                        if (image.tag == url) showPhoto(image, bitmap)
                    }
                }
            }
        }
        return null
    }

    private fun showPhoto(image: ImageView, bitmap: Bitmap?) {
        if (bitmap == null) {
            image.scaleType = ImageView.ScaleType.CENTER_INSIDE
            image.setImageResource(R.drawable.ic_shades)
        } else {
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setImageBitmap(bitmap)
        }
    }

    companion object {
        private const val KEY_RECENT = "recent_places"
        private val MM2 = RobloxCatalog.Place(Mm2.PLACE_ID, "Murder Mystery 2")

        fun message(result: PickResult): String = when (result) {
            is PickResult.Server -> "Сервер найден"
            PickResult.PlaceNotFound -> "Плейс не найден"
            PickResult.NoServers -> "Сейчас нет открытых серверов"
            PickResult.AllFull -> "Все серверы заполнены"
            PickResult.RateLimited -> "Roblox просит подождать — попробуй через минуту"
            PickResult.Offline -> "Нет связи с Roblox"
        }
    }

    private inner class PlaceAdapter(private val inflater: LayoutInflater) : BaseAdapter() {
        override fun getCount() = places.size
        override fun getItem(position: Int) = places[position]
        override fun getItemId(position: Int) = getItem(position).id
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: inflater.inflate(R.layout.item_roblox_place, parent, false)
            val place = getItem(position)
            row.findViewById<TextView>(R.id.placeName).text = place.name
            row.findViewById<TextView>(R.id.placeDetails).text = place.creator ?: "Плейс ${place.id}"
            val image = row.findViewById<ImageView>(R.id.placePhoto)
            val url = iconUrls[place.id]
            image.tag = url
            showPhoto(image, url?.let(::photo))
            return row
        }
    }
}
