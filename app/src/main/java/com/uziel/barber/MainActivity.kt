package com.uziel.barber

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Calendar

data class Appt(
    val id: Long,
    val name: String,
    val service: String,
    val mins: Int,
    val day: String, // yyyy-MM-dd
    val time: String // HH:mm
)

/**
 * Fully native UI - no WebView anywhere in this file or in the app.
 * All appointment data lives in SharedPreferences (on-device, private
 * storage, JSON-encoded) - no server, no cloud, no Google services.
 *
 * This build also installs a crash catcher: if anything goes wrong, the
 * error is saved to disk instead of silently killing the app, and shown
 * on screen the *next* time the app is opened - no computer/USB needed
 * to see what happened.
 */
class MainActivity : AppCompatActivity() {

    private val PREFS = "uziel_appointments_prefs"
    private val KEY = "appointments"
    private val CRASH_KEY = "last_crash"

    private lateinit var fname: EditText
    private lateinit var fservice: Spinner
    private lateinit var fday: Button
    private lateinit var fslots: Spinner
    private lateinit var nextAvailBox: TextView
    private lateinit var fbtn: Button
    private lateinit var confirmBox: TextView
    private lateinit var viewForm: View
    private lateinit var viewSchedule: LinearLayout
    private lateinit var tabForm: Button
    private lateinit var tabSchedule: Button

    private val services = listOf(
        Triple("חסידית", 10, "תספורת חסידית — 10 דקות"),
        Triple("מכונה", 15, "תספורת מכונה — 15 דקות"),
        Triple("פלוס זקן", 20, "תספורת פלוס זקן — 20 דקות")
    )

    private var selectedDay: String? = null
    private var availableSlots: List<Int> = emptyList()
    private var selectedSlotMinutes: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installCrashHandler()

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastCrash = prefs.getString(CRASH_KEY, null)
        if (lastCrash != null) {
            showCrashScreen(lastCrash)
            return
        }

        try {
            setContentView(R.layout.activity_main)

            fname = findViewById(R.id.fname)
            fservice = findViewById(R.id.fservice)
            fday = findViewById(R.id.fday)
            fslots = findViewById(R.id.fslots)
            nextAvailBox = findViewById(R.id.nextAvailBox)
            fbtn = findViewById(R.id.fbtn)
            confirmBox = findViewById(R.id.confirmBox)
            viewForm = findViewById(R.id.viewForm)
            viewSchedule = findViewById(R.id.viewSchedule)
            tabForm = findViewById(R.id.tabForm)
            tabSchedule = findViewById(R.id.tabSchedule)

            val serviceLabels = services.map { it.third }
            fservice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, serviceLabels)

            fname.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { updateSaveState() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            fservice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    renderSlots()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            fday.setOnClickListener { showDatePicker() }

            fslots.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedSlotMinutes = if (position in availableSlots.indices) availableSlots[position] else null
                    updateSaveState()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            fbtn.setOnClickListener { saveAppointment() }

            tabForm.setOnClickListener { showForm() }
            tabSchedule.setOnClickListener { showSchedule() }

            renderSlots()
            updateSaveState()
        } catch (e: Throwable) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            saveCrash(sw.toString())
            showCrashScreen(sw.toString())
        }
    }

    // ---------- Crash catcher ----------

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                saveCrash(sw.toString())
            } catch (ignored: Throwable) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrash(text: String) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(CRASH_KEY, text).apply()
    }

    private fun showCrashScreen(text: String) {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(32, 80, 32, 32)

        val title = TextView(this)
        title.text = "האפליקציה נתקלה בשגיאה. אפשר לצלם את המסך הזה ולשלוח כדי שנתקן:"
        title.setTextColor(0xFFA32D2D.toInt())
        title.textSize = 14f
        title.setPadding(0, 0, 0, 24)
        layout.addView(title)

        val tv = TextView(this)
        tv.text = text
        tv.setTextIsSelectable(true)
        tv.textSize = 11f
        layout.addView(tv)

        val btn = Button(this)
        btn.text = "נקה ונסה שוב"
        btn.setOnClickListener {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(CRASH_KEY).apply()
            recreate()
        }
        layout.addView(btn)

        scroll.addView(layout)
        setContentView(scroll)
    }

    // ---------- Storage (SharedPreferences, on-device only) ----------

    private fun loadAppointments(): MutableList<Appt> {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<Appt>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Appt(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    service = o.getString("service"),
                    mins = o.getInt("mins"),
                    day = o.getString("day"),
                    time = o.getString("time")
                )
            )
        }
        return list
    }

    private fun saveAppointments(list: List<Appt>) {
        val arr = JSONArray()
        for (a in list) {
            val o = JSONObject()
            o.put("id", a.id)
            o.put("name", a.name)
            o.put("service", a.service)
            o.put("mins", a.mins)
            o.put("day", a.day)
            o.put("time", a.time)
            arr.put(o)
        }
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    // ---------- Business hours / slot logic ----------

    private fun pad(n: Int) = if (n < 10) "0$n" else "$n"

    private fun todayStr(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.YEAR)}-${pad(c.get(Calendar.MONTH) + 1)}-${pad(c.get(Calendar.DAY_OF_MONTH))}"
    }

    private fun addDays(dayStr: String, n: Int): String {
        val parts = dayStr.split("-").map { it.toInt() }
        val c = Calendar.getInstance()
        c.set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
        c.add(Calendar.DAY_OF_MONTH, n)
        return "${c.get(Calendar.YEAR)}-${pad(c.get(Calendar.MONTH) + 1)}-${pad(c.get(Calendar.DAY_OF_MONTH))}"
    }

    private fun dayOfWeek(dayStr: String): Int {
        val parts = dayStr.split("-").map { it.toInt() }
        val c = Calendar.getInstance()
        c.set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
        return c.get(Calendar.DAY_OF_WEEK)
    }

    private fun hoursFor(dayStr: String): Pair<Int, Int>? {
        return when (dayOfWeek(dayStr)) {
            Calendar.SATURDAY -> null
            Calendar.FRIDAY -> Pair(9 * 60, 14 * 60)
            else -> Pair(9 * 60, 20 * 60)
        }
    }

    private fun timeToMinutes(t: String): Int {
        val p = t.split(":").map { it.toInt() }
        return p[0] * 60 + p[1]
    }

    private fun minutesToLabel(mins: Int): String {
        return "${pad(mins / 60)}:${pad(mins % 60)}"
    }

    private fun currentDuration(): Int = services[fservice.selectedItemPosition].second

    private fun computeAvailableSlots(dayStr: String, duration: Int): List<Int> {
        val hrs = hoursFor(dayStr) ?: return emptyList()
        val existing = loadAppointments()
            .filter { it.day == dayStr }
            .map { val s = timeToMinutes(it.time); Pair(s, s + it.mins) }
        val slots = mutableListOf<Int>()
        val isToday = dayStr == todayStr()
        val nowMins = if (isToday) {
            val c = Calendar.getInstance()
            c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        } else -1
        var t = hrs.first
        while (t + duration <= hrs.second) {
            if (!isToday || t >= nowMins) {
                val overlaps = existing.any { t < it.second && (t + duration) > it.first }
                if (!overlaps) slots.add(t)
            }
            t += 5
        }
        return slots
    }

    private fun findNextAvailable(fromDay: String, duration: Int, maxDays: Int = 90): Pair<String, Int>? {
        var day = fromDay
        for (i in 0 until maxDays) {
            day = addDays(day, 1)
            val slots = computeAvailableSlots(day, duration)
            if (slots.isNotEmpty()) return Pair(day, slots.first())
        }
        return null
    }

    // ---------- UI ----------

    private fun showDatePicker() {
        val c = Calendar.getInstance()
        val dialog = android.app.DatePickerDialog(this, { _, y, m, d ->
            selectedDay = "$y-${pad(m + 1)}-${pad(d)}"
            fday.text = "$d/${m + 1}/$y"
            renderSlots()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        dialog.datePicker.minDate = System.currentTimeMillis() - 1000
        dialog.show()
    }

    private fun renderSlots() {
        selectedSlotMinutes = null
        nextAvailBox.text = ""
        nextAvailBox.setOnClickListener(null)
        val day = selectedDay
        if (day == null) {
            fslots.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("בחר קודם יום"))
            availableSlots = emptyList()
            updateSaveState()
            return
        }
        val duration = currentDuration()
        val hrs = hoursFor(day)
        if (hrs == null) {
            fslots.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("סגור ביום זה"))
            availableSlots = emptyList()
            showNextAvailable(day, duration)
            updateSaveState()
            return
        }
        val slots = computeAvailableSlots(day, duration)
        availableSlots = slots
        if (slots.isEmpty()) {
            fslots.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("אין שעות פנויות"))
            showNextAvailable(day, duration)
        } else {
            val labels = slots.mapIndexed { idx, t ->
                if (idx == 0) "${minutesToLabel(t)} (מומלץ)" else minutesToLabel(t)
            }
            fslots.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            selectedSlotMinutes = slots.first()
        }
        updateSaveState()
    }

    private fun showNextAvailable(day: String, duration: Int) {
        val found = findNextAvailable(day, duration)
        if (found == null) {
            nextAvailBox.text = "לא נמצא תור פנוי בשלושת החודשים הקרובים."
            return
        }
        val (fDay, fSlot) = found
        val parts = fDay.split("-").map { it.toInt() }
        val weekdays = arrayOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")
        val dow = dayOfWeek(fDay) - 1
        nextAvailBox.text = "התור הפנוי הבא: יום ${weekdays[dow]}, ${parts[2]}/${parts[1]}/${parts[0]} בשעה ${minutesToLabel(fSlot)}\n(הקש כאן כדי לעבור לתאריך הזה)"
        nextAvailBox.setOnClickListener {
            selectedDay = fDay
            fday.text = "${parts[2]}/${parts[1]}/${parts[0]}"
            renderSlots()
        }
    }

    private fun updateSaveState() {
        val name = fname.text.toString().trim()
        fbtn.isEnabled = name.isNotEmpty() && selectedDay != null && selectedSlotMinutes != null
    }

    private fun saveAppointment() {
        val name = fname.text.toString().trim()
        val day = selectedDay ?: return
        val slot = selectedSlotMinutes ?: return
        val service = services[fservice.selectedItemPosition]
        val label = service.first
        val mins = service.second

        val fresh = computeAvailableSlots(day, mins)
        if (!fresh.contains(slot)) {
            Toast.makeText(this, "השעה שנבחרה כבר אינה פנויה, בחר שעה אחרת", Toast.LENGTH_LONG).show()
            renderSlots()
            return
        }

        val list = loadAppointments()
        list.add(Appt(System.currentTimeMillis(), name, label, mins, day, minutesToLabel(slot)))
        saveAppointments(list)

        val parts = day.split("-")
        confirmBox.visibility = View.VISIBLE
        confirmBox.text = "התור נקבע בהצלחה\nשם: $name\nסוג תספורת: $label ($mins דקות)\nיום: ${parts[2]}/${parts[1]}/${parts[0]}\nשעה: ${minutesToLabel(slot)}"

        fname.setText("")
        selectedDay = null
        fday.text = "בחר יום"
        renderSlots()
    }

    private fun showForm() {
        viewForm.visibility = View.VISIBLE
        viewSchedule.visibility = View.GONE
        tabForm.setBackgroundColor(0xFF712B13.toInt())
        tabForm.setTextColor(0xFFFFFFFF.toInt())
        tabSchedule.setBackgroundColor(0xFFFFFFFF.toInt())
        tabSchedule.setTextColor(0xFF5F5E5A.toInt())
    }

    private fun showSchedule() {
        viewForm.visibility = View.GONE
        viewSchedule.visibility = View.VISIBLE
        tabSchedule.setBackgroundColor(0xFF712B13.toInt())
        tabSchedule.setTextColor(0xFFFFFFFF.toInt())
        tabForm.setBackgroundColor(0xFFFFFFFF.toInt())
        tabForm.setTextColor(0xFF5F5E5A.toInt())
        renderSchedule()
    }

    private fun renderSchedule() {
        viewSchedule.removeAllViews()
        val list = loadAppointments().sortedWith(compareBy({ it.day }, { it.time }))
        if (list.isEmpty()) {
            val tv = TextView(this)
            tv.text = "אין עדיין תורים ביומן"
            tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
            tv.setTextColor(0xFF888780.toInt())
            tv.setPadding(0, 60, 0, 60)
            viewSchedule.addView(tv)
            return
        }
        val byDay = LinkedHashMap<String, MutableList<Appt>>()
        for (a in list) byDay.getOrPut(a.day) { mutableListOf() }.add(a)

        val weekdays = arrayOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")

        for ((day, appts) in byDay) {
            val dow = dayOfWeek(day) - 1
            val parts = day.split("-")
            val header = TextView(this)
            header.text = "יום ${weekdays[dow]} - ${parts[2]}/${parts[1]}/${parts[0]}"
            header.setTextColor(0xFF712B13.toInt())
            header.setTypeface(null, android.graphics.Typeface.BOLD)
            header.setPadding(0, 24, 0, 12)
            viewSchedule.addView(header)

            for (a in appts) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.setBackgroundColor(0xFFFFFFFF.toInt())
                row.setPadding(24, 20, 24, 20)
                val rowParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                rowParams.setMargins(0, 0, 0, 12)
                row.layoutParams = rowParams

                val del = Button(this)
                del.text = "מחיקה"
                del.setTextColor(0xFFA32D2D.toInt())
                del.setBackgroundColor(0x00000000)
                del.setOnClickListener {
                    val updated = loadAppointments().filter { it.id != a.id }
                    saveAppointments(updated)
                    renderSchedule()
                }
                row.addView(del)

                val info = TextView(this)
                info.text = "${a.name}\n${a.service} (${a.mins} דק')"
                info.setTextColor(0xFF2C2C2A.toInt())
                val infoParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                info.layoutParams = infoParams
                row.addView(info)

                val time = TextView(this)
                time.text = a.time
                time.setTextColor(0xFF712B13.toInt())
                time.setTypeface(null, android.graphics.Typeface.BOLD)
                row.addView(time)

                viewSchedule.addView(row)
            }
        }
    }
}
